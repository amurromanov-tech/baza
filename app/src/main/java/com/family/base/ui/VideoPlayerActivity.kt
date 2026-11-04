package com.family.base.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.family.base.BaseApplication
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.repository.GalleryRepository
import com.family.base.databinding.ActivityVideoPlayerBinding
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🆕 v20.5.14: экран видеоплеера (ExoPlayer / Media3).
 *
 * 🆕 v20.5.15: добавлена кнопка «💾 Сохранить видео» — скачивает оригинал
 * через WebDAV (downloadOriginal) и кладёт в Movies/BAZA через MediaStore.
 *  - API 29+: без разрешений (MediaStore.Video).
 *  - API 24-28: WRITE_EXTERNAL_STORAGE.
 *
 * 🆕 v20.5.14-fix: useController и resizeMode выставляются здесь,
 * в коде, потому что в XML Media3 таких атрибутов нет (это Java-сеттеры).
 *
 * Что играет:
 *  - MP4, MKV, WebM, 3GP, MOV — ExoPlayer справляется.
 *  - AVI — ExoPlayer НЕ поддерживает контейнер, сюда не доходит:
 *    GalleryActivity для .avi сразу открывает внешний плеер.
 *
 * Fallback:
 *  - Если ExoPlayer упал с ошибкой — показываем errorContainer
 *    с кнопкой «Открыть в другом плеере».
 *  - Кнопка в toolbar — тоже всегда доступна.
 *
 * Жизненный цикл:
 *  - onStart: инициализируем ExoPlayer.
 *  - onStop: release() — обязательно, иначе утечка и звук в фоне.
 */
class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "video_url"
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_MIME = "video_mime"

        // 🆕 v20.5.15: путь на Диске — для «Сохранить видео».
        const val EXTRA_GALLERY_PATH = "video_gallery_path"

        private const val TAG = "VideoPlayerActivity"

        private const val REQ_WRITE_STORAGE = 1002
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null

    private var videoUrl: String? = null
    private var videoTitle: String? = null
    private var videoMime: String? = null

    /** 🆕 v20.5.15: путь на Диске — для сохранения оригинала. */
    private var galleryPath: String? = null

    /** 🆕 v20.5.15: репозиторий и панель сохранения. */
    private lateinit var repository: GalleryRepository
    private lateinit var bottomPanel: LinearLayout
    private lateinit var btnSave: ImageButton
    private lateinit var saveProgress: ProgressBar

    /** 🆕 v20.5.15: защита от двойного нажатия «Сохранить». */
    private var isSaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== VideoPlayerActivity onCreate START ===")

        try {
            binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            finish()
            return
        }

        repository = GalleryRepository(AppDatabase.getInstance(this))

        videoUrl = intent.getStringExtra(EXTRA_URL)
        videoTitle = intent.getStringExtra(EXTRA_TITLE)
        videoMime = intent.getStringExtra(EXTRA_MIME)
        galleryPath = intent.getStringExtra(EXTRA_GALLERY_PATH)

        Logger.log(
            TAG,
            "onCreate: url=$videoUrl, title=$videoTitle, mime=$videoMime, path=$galleryPath"
        )

        if (videoUrl.isNullOrEmpty()) {
            Logger.log(TAG, "onCreate: url is empty, finish")
            Toast.makeText(this, R.string.video_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupErrorButtons()
        setupPlayerView()
        setupSavePanel()

        Logger.log(TAG, "=== VideoPlayerActivity onCreate FINISHED ===")
    }

    /**
     * 🆕 v20.5.14-fix: настройки PlayerView, которых нет в XML.
     *  - useController = true — встроенные контролы (play/pause, seek).
     *  - resizeMode = FIT — вписываем видео в экран без обрезки.
     */
    private fun setupPlayerView() {
        binding.playerView.useController = true
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        binding.playerView.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.toolbar.title = videoTitle ?: getString(R.string.video_title)

        binding.toolbar.setNavigationOnClickListener {
            Logger.log(TAG, "toolbar up clicked")
            finish()
        }

        binding.btnOpenExternal.setOnClickListener {
            Logger.log(TAG, "btnOpenExternal clicked")
            openInExternalPlayer()
        }
    }

    private fun setupErrorButtons() {
        binding.btnOpenExternalError.setOnClickListener {
            Logger.log(TAG, "btnOpenExternalError clicked")
            openInExternalPlayer()
        }
    }

    // ============================================================
    // 🆕 v20.5.15: ПАНЕЛЬ «СОХРАНИТЬ» (программно, как в FullscreenImage)
    // ============================================================

    private fun setupSavePanel() {
        // Если нет пути на Диске — сохранить нечего, панель не создаём.
        if (galleryPath.isNullOrEmpty()) {
            Logger.log(TAG, "setupSavePanel: no galleryPath, skip")
            return
        }

        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(
                this@VideoPlayerActivity,
                R.drawable.bg_bottom_panel
            ) ?: run {
                val d = android.graphics.drawable.GradientDrawable()
                d.setColor(0x66000000)
                d.cornerRadius = dp(24).toFloat()
                d
            }
            visibility = View.VISIBLE
        }

        btnSave = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_save)
            contentDescription = getString(R.string.video_save)
            background = ContextCompat.getDrawable(
                this@VideoPlayerActivity,
                android.R.drawable.btn_default
            )?.apply { alpha = 0x40 } ?: run {
                val d = android.graphics.drawable.GradientDrawable()
                d.setColor(0x33FFFFFF)
                d.cornerRadius = dp(24).toFloat()
                d
            }
            setColorFilter(android.graphics.Color.WHITE)
            val size = dp(48)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onSaveClicked() }
        }

        bottomPanel.addView(btnSave)

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(80)
        }

        // Панель кладём в корневой контейнер — он FrameLayout (binding.root).
        (binding.root as? FrameLayout)?.addView(bottomPanel, lp)
            ?: run {
                Logger.log(TAG, "setupSavePanel: root is not FrameLayout, skip panel")
            }

        // Прогресс-оверлей при сохранении.
        saveProgress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        val progressLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        (binding.root as? FrameLayout)?.addView(saveProgress, progressLp)
    }

    // ============================================================
    // 🆕 v20.5.15: СОХРАНЕНИЕ ВИДЕО
    // ============================================================

    private fun onSaveClicked() {
        val path = galleryPath ?: return
        if (isSaving) {
            Logger.log(TAG, "onSaveClicked: already saving, skip")
            return
        }

        // API ≤28: нужно WRITE_EXTERNAL_STORAGE.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                Logger.log(TAG, "onSaveClicked: requesting WRITE_EXTERNAL_STORAGE")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQ_WRITE_STORAGE
                )
                return
            }
        }

        doSaveVideo(path)
    }

    private fun doSaveVideo(path: String) {
        isSaving = true
        if (::saveProgress.isInitialized) saveProgress.visibility = View.VISIBLE

        val fileName = videoTitle?.takeIf { it.isNotEmpty() }
            ?: path.substringAfterLast('/')

        Logger.log(TAG, "doSaveVideo: start for '$path' (fileName=$fileName)")

        BaseApplication.applicationScope.launch {
            val bytes = repository.downloadOriginal(path)
            if (bytes == null) {
                Logger.log(TAG, "doSaveVideo: downloadOriginal returned null")
                withContext(Dispatchers.Main) {
                    if (::saveProgress.isInitialized) saveProgress.visibility = View.GONE
                    isSaving = false
                    Toast.makeText(
                        this@VideoPlayerActivity,
                        R.string.video_save_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            val uri = repository.saveVideoToMediaStore(bytes, fileName)
            withContext(Dispatchers.Main) {
                if (::saveProgress.isInitialized) saveProgress.visibility = View.GONE
                isSaving = false
                if (uri != null) {
                    Logger.log(TAG, "doSaveVideo: OK → $uri")
                    Toast.makeText(
                        this@VideoPlayerActivity,
                        getString(R.string.video_save_ok, GalleryRepository.MEDIA_VIDEO_SUBDIR),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Logger.log(TAG, "doSaveVideo: saveVideoToMediaStore returned null")
                    Toast.makeText(
                        this@VideoPlayerActivity,
                        R.string.video_save_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                galleryPath?.let { doSaveVideo(it) }
            } else {
                Toast.makeText(
                    this,
                    R.string.video_save_permission_denied,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
    // EXOPLAYER
    // ============================================================

    override fun onStart() {
        super.onStart()
        Logger.log(TAG, "onStart: initializing player")
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        Logger.log(TAG, "onStop: releasing player")
        releasePlayer()
    }

    private fun initializePlayer() {
        val url = videoUrl ?: return

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        binding.playerView.player = exoPlayer

        binding.progressBar.visibility = View.VISIBLE
        binding.errorContainer.visibility = View.GONE

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Logger.log(TAG, "onPlaybackStateChanged: $playbackState")
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    Player.STATE_READY -> {
                        binding.progressBar.visibility = View.GONE
                        binding.errorContainer.visibility = View.GONE
                    }
                    Player.STATE_ENDED -> {
                        binding.progressBar.visibility = View.GONE
                    }
                    Player.STATE_IDLE -> {
                        // Ничего не делаем, ошибки обрабатываются ниже.
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Logger.log(TAG, "onPlayerError: ${error.errorCodeName} — ${error.message}", error)
                binding.progressBar.visibility = View.GONE
                binding.errorContainer.visibility = View.VISIBLE
                binding.tvError.text = getString(R.string.video_error_open_external)
            }
        })

        try {
            val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(url))
            if (!videoMime.isNullOrEmpty()) {
                mediaItemBuilder.setMimeType(videoMime)
            }
            exoPlayer.setMediaItem(mediaItemBuilder.build())
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            Logger.log(TAG, "initializePlayer: prepared, playing")
        } catch (e: Exception) {
            Logger.log(TAG, "initializePlayer error: ${e.message}", e)
            binding.progressBar.visibility = View.GONE
            binding.errorContainer.visibility = View.VISIBLE
        }
    }

    private fun releasePlayer() {
        try {
            binding.playerView.player = null
            player?.release()
            player = null
            Logger.log(TAG, "releasePlayer: done")
        } catch (e: Exception) {
            Logger.log(TAG, "releasePlayer error: ${e.message}", e)
        }
    }

    // ============================================================
    // FALLBACK — ВНЕШНИЙ ПЛЕЕР
    // ============================================================

    private fun openInExternalPlayer() {
        val url = videoUrl
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, R.string.video_error, Toast.LENGTH_SHORT).show()
            return
        }

        val mime = videoMime?.takeIf { it.isNotEmpty() } ?: "video/*"

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            Logger.log(TAG, "openInExternalPlayer: launched for $url (mime=$mime)")
        } catch (e: ActivityNotFoundException) {
            Logger.log(TAG, "openInExternalPlayer: no app for $mime", e)
            Toast.makeText(this, R.string.video_no_player, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Logger.log(TAG, "openInExternalPlayer error: ${e.message}", e)
            Toast.makeText(this, R.string.video_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

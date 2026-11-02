package com.family.base.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.family.base.R
import com.family.base.databinding.ActivityVideoPlayerBinding
import com.family.base.util.Logger

/**
 * 🆕 v20.5.14: экран видеоплеера (ExoPlayer / Media3).
 *
 * Что играет:
 *  - MP4, MKV, WebM, 3GP, MOV — ExoPlayer справляется.
 *  - AVI — ExoPlayer НЕ поддерживает контейнер, сюда не доходит:
 *    GalleryActivity для .avi сразу открывает внешний плеер
 *    (Intent.ACTION_VIEW). См. VideoFormats.isExoPlayerSupported().
 *
 * Fallback:
 *  - Если ExoPlayer упал с ошибкой (битый файл, редкий кодек) —
 *    показываем errorContainer с кнопкой «Открыть в другом плеере».
 *  - Кнопка в toolbar — тоже всегда доступна.
 *
 * Жизненный цикл:
 *  - onStart: инициализируем ExoPlayer.
 *  - onStop: release() — обязательно, иначе утечка и звук в фоне.
 *  - onNewIntent не нужен (launchMode standard).
 */
class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "video_url"
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_MIME = "video_mime"

        private const val TAG = "VideoPlayerActivity"
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null

    private var videoUrl: String? = null
    private var videoTitle: String? = null
    private var videoMime: String? = null

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

        videoUrl = intent.getStringExtra(EXTRA_URL)
        videoTitle = intent.getStringExtra(EXTRA_TITLE)
        videoMime = intent.getStringExtra(EXTRA_MIME)

        Logger.log(TAG, "onCreate: url=$videoUrl, title=$videoTitle, mime=$videoMime")

        if (videoUrl.isNullOrEmpty()) {
            Logger.log(TAG, "onCreate: url is empty, finish")
            Toast.makeText(this, R.string.video_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupErrorButtons()

        Logger.log(TAG, "=== VideoPlayerActivity onCreate FINISHED ===")
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
                binding.tvError.text = getString(
                    R.string.video_error_open_external
                )
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
}

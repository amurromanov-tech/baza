package com.family.base.ui

import android.Manifest
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.load
import com.family.base.BaseApplication
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.model.GalleryItem
import com.family.base.data.repository.GalleryRepository
import com.family.base.util.Logger
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Полноэкранный просмотр изображения.
 *
 * 🆕 v20.4.9: два режима:
 *  1. Локальный файл (EXTRA_IMAGE_PATH) — как раньше.
 *  2. URL с Диска (EXTRA_IMAGE_URL) — legacy, сохранён для совместимости.
 *
 * 🆕 v20.6.1: третий режим — EXTRA_GALLERY_PATH (путь на Диске).
 * Превью скачивается через WebDAV API с Basic-аутентификацией
 * (GalleryRepository.downloadPreview). Прямые preview-ссылки REST API
 * отдают 403 без куки браузера, а WebDAV работает.
 *
 * 🆕 v20.5.12 (Шаг 2):
 *  - Кнопки «💾 Сохранить», «🔗 Копировать ссылку», «ℹ️ Информация»
 *    добавляются программно в нижнюю панель (layout не трогаем).
 *  - «Сохранить»: downloadOriginal() → saveToMediaStore() → Pictures/BAZA.
 *  - «Копировать ссылку»: fileUrl (вариант B) в ClipboardManager.
 *  - «Информация»: имя, путь, размер, дата создания.
 *  - Прогресс-бар поверх экрана при скачивании оригинала.
 *
 * Приоритет: GALLERY_PATH > IMAGE_URL > IMAGE_PATH.
 */
class FullscreenImageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_IMAGE_URL = "image_url"
        const val EXTRA_GALLERY_PATH = "gallery_path"
        const val EXTRA_TITLE = "title"

        // 🆕 v20.5.12: extras для «Информации» и «Сохранить»
        const val EXTRA_FILE_URL = "file_url"
        const val EXTRA_SIZE_BYTES = "size_bytes"
        const val EXTRA_CREATED_MILLIS = "created_millis"

        private const val TAG = "FullscreenImage"

        private const val DISMISS_THRESHOLD_RATIO = 0.25f
        private const val ZOOM_THRESHOLD = 1.05f
        private const val SWIPE_START_DP = 24f

        private const val REQ_WRITE_STORAGE = 1001
    }

    private lateinit var rootContainer: FrameLayout
    private lateinit var photoContainer: FrameLayout
    private lateinit var photoView: PhotoView
    private lateinit var tvHint: TextView
    private lateinit var tvTitle: TextView
    private lateinit var btnClose: ImageView

    /** 🆕 v20.5.12: панель кнопок внизу. */
    private lateinit var bottomPanel: LinearLayout
    private lateinit var btnSave: ImageButton
    private lateinit var btnCopyLink: ImageButton
    private lateinit var btnInfo: ImageButton
    private lateinit var progressOverlay: ProgressBar

    private var isDragging = false
    private var dragStartY = 0f
    private var currentTranslationY = 0f
    private var dismissThreshold = 0f
    private var swipeStartThreshold = 0f

    // 🆕 v20.5.12: данные текущего элемента для «Сохранить/Ссылка/Инфо».
    private var currentGalleryPath: String? = null
    private var currentFileUrl: String? = null
    private var currentTitle: String? = null
    private var currentSizeBytes: Long? = null
    private var currentCreatedMillis: Long? = null

    /** 🆕 v20.5.12: репозиторий для downloadOriginal/saveToMediaStore. */
    private lateinit var repository: GalleryRepository

    /** 🆕 v20.5.12: защита от двойного нажатия «Сохранить». */
    private var isSaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== FullscreenImageActivity onCreate START ===")

        setContentView(R.layout.activity_fullscreen_image)

        rootContainer = findViewById(R.id.rootContainer)
        photoContainer = findViewById(R.id.photoContainer)
        photoView = findViewById(R.id.photoView)
        tvHint = findViewById(R.id.tvHint)
        tvTitle = findViewById(R.id.tvTitle)
        btnClose = findViewById(R.id.btnClose)

        repository = GalleryRepository(AppDatabase.getInstance(this))

        currentGalleryPath = intent.getStringExtra(EXTRA_GALLERY_PATH)
        val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)
        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        currentTitle = intent.getStringExtra(EXTRA_TITLE)
        currentFileUrl = intent.getStringExtra(EXTRA_FILE_URL)
        currentSizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, -1L).takeIf { it >= 0 }
        currentCreatedMillis = intent.getLongExtra(EXTRA_CREATED_MILLIS, -1L).takeIf { it >= 0 }

        tvTitle.text = currentTitle ?: ""
        btnClose.setOnClickListener { finish() }

        val density = resources.displayMetrics.density
        dismissThreshold = resources.displayMetrics.heightPixels * DISMISS_THRESHOLD_RATIO
        swipeStartThreshold = SWIPE_START_DP * density

        // 🆕 v20.5.12: панель кнопок + прогресс-оверлей.
        setupBottomPanel()
        setupProgressOverlay()

        // ============================================================
        // 🆕 v20.6.1: приоритет GALLERY_PATH > IMAGE_URL > IMAGE_PATH.
        // ============================================================
        when {
            !currentGalleryPath.isNullOrEmpty() -> {
                Logger.log(TAG, "Loading image via WebDAV (gallery): $currentGalleryPath")
                loadFromWebDav(currentGalleryPath!!)
            }
            !imageUrl.isNullOrEmpty() -> {
                Logger.log(TAG, "Loading image by URL (legacy): $imageUrl")
                photoView.load(imageUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_item_default)
                    error(R.drawable.ic_item_default)
                }
            }
            !imagePath.isNullOrEmpty() -> {
                Logger.log(TAG, "Loading image from local file: $imagePath")
                val loaded = loadFromFile(imagePath)
                if (!loaded) {
                    finish()
                    return
                }
            }
            else -> {
                Logger.log(TAG, "No image path, URL or gallery path provided")
                finish()
                return
            }
        }

        hideSystemBars()
        Logger.log(TAG, "=== FullscreenImageActivity onCreate FINISHED ===")
    }

    // ============================================================
    // 🆕 v20.5.12: НИЖНЯЯ ПАНЕЛЬ КНОПОК (программно)
    // ============================================================

    private fun setupBottomPanel() {
        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(this@FullscreenImageActivity, R.drawable.bg_bottom_panel)
                ?: run {
                    // fallback: полупрозрачный чёрный
                    val d = android.graphics.drawable.GradientDrawable()
                    d.setColor(0x66000000)
                    d.cornerRadius = dp(24).toFloat()
                    d
                }
            visibility = View.VISIBLE
        }

        btnSave = makePanelButton(
            iconRes = android.R.drawable.ic_menu_save,
            contentDesc = getString(R.string.gallery_save)
        ) { onSaveClicked() }

        btnCopyLink = makePanelButton(
            iconRes = android.R.drawable.ic_menu_share,
            contentDesc = getString(R.string.gallery_copy_link)
        ) { onCopyLinkClicked() }

        btnInfo = makePanelButton(
            iconRes = android.R.drawable.ic_menu_info_details,
            contentDesc = getString(R.string.gallery_info_title)
        ) { onInfoClicked() }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1).apply { weight = 1f }
        }

        // Кнопка «Сохранить» — только для галереи (есть путь на Диске).
        if (currentGalleryPath.isNullOrEmpty()) {
            btnSave.visibility = View.GONE
        }
        // «Копировать ссылку» — только если есть fileUrl (вариант B).
        if (currentFileUrl.isNullOrEmpty()) {
            btnCopyLink.visibility = View.GONE
        }
        // «Информация» — только для галереи (есть путь).
        if (currentGalleryPath.isNullOrEmpty()) {
            btnInfo.visibility = View.GONE
        }

        bottomPanel.addView(btnSave)
        bottomPanel.addView(btnCopyLink)
        bottomPanel.addView(btnInfo)
        bottomPanel.addView(spacer)

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(64)
            leftMargin = dp(16)
            rightMargin = dp(16)
        }

        rootContainer.addView(bottomPanel, lp)
    }

    private fun makePanelButton(
        iconRes: Int,
        contentDesc: String,
        onClick: () -> Unit
    ): ImageButton {
        return ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = contentDesc
            background = ContextCompat.getDrawable(
                this@FullscreenImageActivity,
                android.R.drawable.btn_default
            )?.apply { alpha = 0x40 } ?: run {
                val d = android.graphics.drawable.GradientDrawable()
                d.setColor(0x33FFFFFF)
                d.cornerRadius = dp(24).toFloat()
                d
            }
            tintList = null
            setColorFilter(android.graphics.Color.WHITE)
            val size = dp(48)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
        }
    }

    private fun setupProgressOverlay() {
        progressOverlay = ProgressBar(this).apply {
            visibility = View.GONE
            indeterminate = true
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        rootContainer.addView(progressOverlay, lp)
    }

    // ============================================================
    // 🆕 v20.5.12: «💾 СОХРАНИТЬ»
    // ============================================================

    private fun onSaveClicked() {
        val path = currentGalleryPath ?: return
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

        doSave(path)
    }

    private fun doSave(path: String) {
        isSaving = true
        progressOverlay.visibility = View.VISIBLE

        val fileName = currentTitle?.takeIf { it.isNotEmpty() }
            ?: path.substringAfterLast('/')

        Logger.log(TAG, "doSave: start for '$path' (fileName=$fileName)")

        BaseApplication.applicationScope.launch {
            val bytes = repository.downloadOriginal(path)
            if (bytes == null) {
                Logger.log(TAG, "doSave: downloadOriginal returned null")
                withContext(Dispatchers.Main) {
                    progressOverlay.visibility = View.GONE
                    isSaving = false
                    Toast.makeText(
                        this@FullscreenImageActivity,
                        R.string.gallery_save_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            val uri = repository.saveToMediaStore(bytes, fileName)
            withContext(Dispatchers.Main) {
                progressOverlay.visibility = View.GONE
                isSaving = false
                if (uri != null) {
                    Logger.log(TAG, "doSave: OK → $uri")
                    Toast.makeText(
                        this@FullscreenImageActivity,
                        getString(R.string.gallery_save_ok, GalleryRepository.MEDIA_SUBDIR),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Logger.log(TAG, "doSave: saveToMediaStore returned null")
                    Toast.makeText(
                        this@FullscreenImageActivity,
                        R.string.gallery_save_error,
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
                currentGalleryPath?.let { doSave(it) }
            } else {
                Toast.makeText(
                    this,
                    R.string.gallery_save_permission_denied,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
    // 🆕 v20.5.12: «🔗 КОПИРОВАТЬ ССЫЛКУ» (вариант B — fileUrl)
    // ============================================================

    private fun onCopyLinkClicked() {
        val url = currentFileUrl
        if (url.isNullOrEmpty()) {
            Logger.log(TAG, "onCopyLinkClicked: fileUrl is empty")
            Toast.makeText(this, R.string.gallery_link_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("BAZA", url))
            Logger.log(TAG, "onCopyLinkClicked: copied, length=${url.length}")
            Toast.makeText(this, R.string.gallery_link_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Logger.log(TAG, "onCopyLinkClicked error: ${e.message}", e)
            Toast.makeText(this, R.string.gallery_link_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 🆕 v20.5.12: «ℹ️ ИНФОРМАЦИЯ»
    // ============================================================

    private fun onInfoClicked() {
        val path = currentGalleryPath ?: return
        val title = currentTitle ?: path.substringAfterLast('/')

        val sb = StringBuilder()
        sb.append(getString(R.string.gallery_info_name)).append(": ").append(title).append("\n\n")
        sb.append(getString(R.string.gallery_info_path)).append(":\n").append(path).append("\n\n")

        val size = currentSizeBytes
        if (size != null && size > 0) {
            sb.append(getString(R.string.gallery_info_size)).append(": ")
                .append(formatSize(size)).append("\n\n")
        }

        val created = currentCreatedMillis
        if (created != null && created > 0) {
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            sb.append(getString(R.string.gallery_info_date)).append(": ")
                .append(fmt.format(Date(created))).append("\n")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.gallery_info_title)
            .setMessage(sb.toString().trim())
            .setPositiveButton(R.string.gallery_close, null)
            .show()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    // ============================================================
    // ЗАГРУЗКА
    // ============================================================

    /**
     * 🆕 v20.6.1: загрузка через WebDAV (Basic-аутентификация).
     * Качаем превью size=M в память и грузим через Coil.load(byteArray).
     */
    private fun loadFromWebDav(path: String) {
        BaseApplication.applicationScope.launch {
            val bytes = repository.downloadPreview(path)
            if (bytes == null) {
                Logger.log(TAG, "WebDAV preview null for $path")
                return@launch
            }
            withContext(Dispatchers.Main) {
                photoView.load(bytes) {
                    crossfade(true)
                    placeholder(R.drawable.ic_item_default)
                    error(R.drawable.ic_item_default)
                }
            }
        }
    }

    /**
     * Старый режим: локальный файл.
     */
    private fun loadFromFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) {
            Logger.log(TAG, "Image file not found: $path")
            return false
        }
        return try {
            photoView.setImageURI(Uri.fromFile(file))
            true
        } catch (e: Exception) {
            Logger.log(TAG, "Error loading image: ${e.message}")
            false
        }
    }

    // ============================================================
    // СВАЙП ВНИЗ ДЛЯ ЗАКРЫТИЯ
    // ============================================================
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (photoView.scale > ZOOM_THRESHOLD) {
            if (isDragging) {
                animateBackToOrigin()
                isDragging = false
            }
            return super.dispatchTouchEvent(ev)
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = ev.rawY
                currentTranslationY = 0f
                isDragging = false
                return super.dispatchTouchEvent(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = ev.rawY - dragStartY
                if (!isDragging && deltaY > swipeStartThreshold) {
                    isDragging = true
                }
                if (isDragging) {
                    val effectiveDelta = deltaY.coerceAtLeast(0f)
                    currentTranslationY = effectiveDelta
                    applySwipeTransform(effectiveDelta)
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    if (currentTranslationY > dismissThreshold) {
                        animateDismissAndFinish()
                    } else {
                        animateBackToOrigin()
                    }
                    isDragging = false
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun applySwipeTransform(deltaY: Float) {
        photoContainer.translationY = deltaY
        val progress = (deltaY / dismissThreshold).coerceIn(0f, 1f)
        rootContainer.alpha = 1f - progress * 0.7f
        tvHint.alpha = 1f - progress
        tvTitle.alpha = 1f - progress
        if (::bottomPanel.isInitialized) bottomPanel.alpha = 1f - progress
    }

    private fun animateBackToOrigin() {
        ValueAnimator.ofFloat(currentTranslationY, 0f).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                photoContainer.translationY = value
                val progress = (value / dismissThreshold).coerceIn(0f, 1f)
                rootContainer.alpha = 1f - progress * 0.7f
                tvHint.alpha = 1f - progress
                tvTitle.alpha = 1f - progress
                if (::bottomPanel.isInitialized) bottomPanel.alpha = 1f - progress
            }
            start()
        }
        currentTranslationY = 0f
    }

    private fun animateDismissAndFinish() {
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        ValueAnimator.ofFloat(currentTranslationY, screenHeight).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                photoContainer.translationY = value
                val progress = (value / dismissThreshold).coerceIn(0f, 1f)
                rootContainer.alpha = 1f - progress * 0.7f
                tvHint.alpha = 1f - progress
                tvTitle.alpha = 1f - progress
                if (::bottomPanel.isInitialized) bottomPanel.alpha = 1f - progress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    finish()
                    overridePendingTransition(0, android.R.anim.fade_out)
                }
            })
            start()
        }
    }

    private fun hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                val controller = window.insetsController
                controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error hiding system bars: ${e.message}")
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

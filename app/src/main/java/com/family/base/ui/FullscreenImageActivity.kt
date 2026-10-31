package com.family.base.ui

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.family.base.BaseApplication
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.repository.GalleryRepository
import com.family.base.util.Logger
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Полноэкранный просмотр изображения.
 *
 * 🆕 v20.4.9: два режима:
 *  1. Локальный файл (EXTRA_IMAGE_PATH) — как раньше.
 *  2. URL с Диска (EXTRA_IMAGE_URL) — legacy, сохранён для совместимости.
 *
 * 🆕 v20.6.1: третий режим — EXTRA_GALLERY_PATH (путь на Диске).
 * Превью скачивается через WebDAV API с OAuth-токеном
 * (GalleryRepository.downloadPreview). Прямые preview-ссылки REST API
 * отдают 403 без куки браузера, а WebDAV работает.
 *
 * Приоритет: GALLERY_PATH > IMAGE_URL > IMAGE_PATH.
 */
class FullscreenImageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_IMAGE_URL = "image_url"
        const val EXTRA_GALLERY_PATH = "gallery_path"
        const val EXTRA_TITLE = "title"
        private const val TAG = "FullscreenImage"

        private const val DISMISS_THRESHOLD_RATIO = 0.25f
        private const val ZOOM_THRESHOLD = 1.05f
        private const val SWIPE_START_DP = 24f
    }

    private lateinit var rootContainer: FrameLayout
    private lateinit var photoContainer: FrameLayout
    private lateinit var photoView: PhotoView
    private lateinit var tvHint: TextView
    private lateinit var tvTitle: TextView
    private lateinit var btnClose: ImageView

    private var isDragging = false
    private var dragStartY = 0f
    private var currentTranslationY = 0f
    private var dismissThreshold = 0f
    private var swipeStartThreshold = 0f

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

        val galleryPath = intent.getStringExtra(EXTRA_GALLERY_PATH)
        val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)
        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE)

        tvTitle.text = title ?: ""
        btnClose.setOnClickListener { finish() }

        val density = resources.displayMetrics.density
        dismissThreshold = resources.displayMetrics.heightPixels * DISMISS_THRESHOLD_RATIO
        swipeStartThreshold = SWIPE_START_DP * density

        // ============================================================
        // 🆕 v20.6.1: приоритет GALLERY_PATH > IMAGE_URL > IMAGE_PATH.
        // ============================================================
        when {
            !galleryPath.isNullOrEmpty() -> {
                Logger.log(TAG, "Loading image via WebDAV (gallery): $galleryPath")
                loadFromWebDav(galleryPath)
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

    /**
     * 🆕 v20.6.1: загрузка через WebDAV (OAuth-токен).
     * Качаем превью size=M в память и грузим через Coil.load(byteArray).
     */
    private fun loadFromWebDav(path: String) {
        val db = AppDatabase.getInstance(this)
        val repo = GalleryRepository(db)

        BaseApplication.applicationScope.launch {
            val bytes = repo.downloadPreview(path)
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

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

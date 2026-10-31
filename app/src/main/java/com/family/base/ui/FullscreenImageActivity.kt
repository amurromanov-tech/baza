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
import coil.ImageRequest
import coil.load
import com.family.base.R
import com.family.base.util.Logger
import com.github.chrisbanes.photoview.PhotoView
import java.io.File

/**
 * Полноэкранный просмотр изображения.
 *
 * 🆕 v20.4.9 (галерея): поддерживает ДВА режима:
 *
 *  1. Локальный файл (как раньше, для фото предметов):
 *     putExtra(EXTRA_IMAGE_PATH, "/data/.../images/xxx.jpg")
 *
 *  2. URL с Яндекс.Диска (для галереи «🖼️ Галерея» → /Романовы):
 *     putExtra(EXTRA_IMAGE_URL, "https://downloader.disk.yandex.ru/preview/...")
 *
 *     В этом режиме картинка грузится Coil'ом по URL — оригинал НЕ
 *     скачивается, файл на телефон не попадает (Coil кэширует в
 *     своём cacheDir приложения, невидимом для системной галереи).
 *
 * Приоритет: если задан EXTRA_IMAGE_URL — используем его.
 * Иначе — старый режим с EXTRA_IMAGE_PATH.
 *
 * Свайп вниз для закрытия, скрытие системных баров, pinch-to-zoom —
 * без изменений (PhotoView наследует ImageView, Coil.load работает).
 *
 * 🆕 v20.5.4: добавлен ImageRequest.Listener для диагностики Coil.
 * В лог пишется onSuccess (размер, mime) и onError (точная причина:
 * HTTP-код, exception). Нужно, чтобы понять, почему превью Диска
 * не грузятся.
 */
class FullscreenImageActivity : AppCompatActivity() {

    companion object {
        /** Локальный файл (как раньше). */
        const val EXTRA_IMAGE_PATH = "image_path"

        /** 🆕 v20.4.9: URL с Диска (превью). Приоритетнее EXTRA_IMAGE_PATH. */
        const val EXTRA_IMAGE_URL = "image_url"

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

        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)
        val title = intent.getStringExtra(EXTRA_TITLE)

        tvTitle.text = title ?: ""
        btnClose.setOnClickListener { finish() }

        val density = resources.displayMetrics.density
        dismissThreshold = resources.displayMetrics.heightPixels * DISMISS_THRESHOLD_RATIO
        swipeStartThreshold = SWIPE_START_DP * density

        // ============================================================
        // 🆕 v20.4.9: два режима — URL (галерея) или локальный файл.
        // Приоритет у URL: если он задан, локальный путь игнорируется.
        // ============================================================
        if (!imageUrl.isNullOrEmpty()) {
            Logger.log(TAG, "Loading image by URL (gallery): $imageUrl")
            loadFromUrl(imageUrl)
        } else if (!imagePath.isNullOrEmpty()) {
            Logger.log(TAG, "Loading image from local file: $imagePath")
            val loaded = loadFromFile(imagePath)
            if (!loaded) {
                finish()
                return
            }
        } else {
            Logger.log(TAG, "No image path or URL provided")
            finish()
            return
        }

        hideSystemBars()
        Logger.log(TAG, "=== FullscreenImageActivity onCreate FINISHED ===")
    }

    /**
     * 🆕 v20.4.9: загрузка по URL через Coil.
     * Оригинал не качается — Coil грузит превью с Диска напрямую.
     * Ошибки обрабатываются плейсхолдером (пользователь может закрыть).
     *
     * 🆕 v20.5.4: добавлен ImageRequest.Listener — точная диагностика.
     */
    private fun loadFromUrl(url: String) {
        try {
            val request = ImageRequest.Builder(this)
                .data(url)
                .crossfade(true)
                .placeholder(R.drawable.ic_item_default)
                .error(R.drawable.ic_item_default)
                .listener(
                    onStart = { req ->
                        Logger.log(TAG, "Coil onStart: ${req.data}")
                    },
                    onSuccess = { req, result ->
                        Logger.log(
                            TAG,
                            "Coil onSuccess: ${req.data} " +
                                "(w=${result.image.width}, h=${result.image.height}, " +
                                "mime=${result.image.mimeType})"
                        )
                    },
                    onError = { req, result ->
                        Logger.log(
                            TAG,
                            "Coil onError: ${req.data} → ${result.throwable.message}",
                            result.throwable
                        )
                    }
                )
                .build()
            photoView.load(request)
        } catch (e: Exception) {
            Logger.log(TAG, "Error loading image by URL: ${e.message}", e)
            // Не закрываем экран: пользователь увидит error-плейсхолдер
            // и сможет выйти свайпом/крестиком.
        }
    }

    /**
     * Старый режим: локальный файл. Возвращает false, если файла нет.
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

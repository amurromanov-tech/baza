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
import com.family.base.R
import com.family.base.util.Logger
import com.github.chrisbanes.photoview.PhotoView
import java.io.File

class FullscreenImageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_TITLE = "title"
        private const val TAG = "FullscreenImage"

        // Свайп вниз: доля высоты экрана, после которой закрываем
        private const val DISMISS_THRESHOLD_RATIO = 0.25f

        // Порог, при котором масштаб считаем «не увеличен»
        private const val ZOOM_THRESHOLD = 1.05f
    }

    private lateinit var rootContainer: FrameLayout
    private lateinit var photoContainer: FrameLayout
    private lateinit var photoView: PhotoView
    private lateinit var tvHint: TextView
    private lateinit var tvTitle: TextView
    private lateinit var btnClose: ImageView

    private var isZoomed = false
    private var isDragging = false
    private var dragStartY = 0f
    private var currentTranslationY = 0f
    private var dismissThreshold = 0f

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
        val title = intent.getStringExtra(EXTRA_TITLE)

        tvTitle.text = title ?: ""
        btnClose.setOnClickListener { finish() }

        dismissThreshold = resources.displayMetrics.heightPixels * DISMISS_THRESHOLD_RATIO

        if (imagePath.isNullOrEmpty()) {
            Logger.log(TAG, "No image path provided")
            finish()
            return
        }

        val file = File(imagePath)
        if (!file.exists()) {
            Logger.log(TAG, "Image file not found: $imagePath")
            finish()
            return
        }

        Logger.log(TAG, "Loading image: $imagePath")

        try {
            photoView.setImageURI(Uri.fromFile(file))
        } catch (e: Exception) {
            Logger.log(TAG, "Error loading image: ${e.message}")
            finish()
            return
        }

        // Отслеживаем масштаб (увеличено ли фото)
        photoView.setOnScaleChangeListener { _, _, _ ->
            isZoomed = photoView.scale > ZOOM_THRESHOLD
            Logger.log(TAG, "Scale changed: ${photoView.scale}, isZoomed=$isZoomed")
        }

        setupSwipeToDismiss()
        hideSystemBars()

        Logger.log(TAG, "=== FullscreenImageActivity onCreate FINISHED ===")
    }

    // ============================================================
    // СВАЙП ВНИЗ ДЛЯ ЗАКРЫТИЯ (с анимацией)
    // ============================================================
    private fun setupSwipeToDismiss() {
        photoView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isZoomed || photoView.scale > ZOOM_THRESHOLD) {
                        isDragging = false
                        return@setOnTouchListener false
                    }
                    dragStartY = event.rawY
                    currentTranslationY = 0f
                    isDragging = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isZoomed || photoView.scale > ZOOM_THRESHOLD) {
                        isDragging = false
                        return@setOnTouchListener false
                    }

                    val deltaY = event.rawY - dragStartY

                    if (deltaY > 0) {
                        isDragging = true
                        currentTranslationY = deltaY
                        applySwipeTransform(deltaY)
                        true
                    } else {
                        if (isDragging) {
                            animateBackToOrigin()
                            isDragging = false
                        }
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        if (currentTranslationY > dismissThreshold) {
                            animateDismissAndFinish()
                        } else {
                            animateBackToOrigin()
                        }
                        isDragging = false
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    /**
     * Применяет трансформации во время свайпа.
     */
    private fun applySwipeTransform(deltaY: Float) {
        photoContainer.translationY = deltaY

        val progress = (deltaY / dismissThreshold).coerceIn(0f, 1f)

        rootContainer.alpha = 1f - progress * 0.7f
        tvHint.alpha = 1f - progress
        tvTitle.alpha = 1f - progress
    }

    /**
     * Плавный возврат фото на исходное место.
     */
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

    /**
     * Плавное «утаскивание» фото за пределы экрана и закрытие.
     */
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

    // ============================================================
    // СКРЫТИЕ СИСТЕМНЫХ ПАНЕЛЕЙ (immersive mode)
    // ============================================================
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

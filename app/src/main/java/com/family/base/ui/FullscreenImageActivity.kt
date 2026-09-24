package com.family.base.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== FullscreenImageActivity onCreate START ===")

        setContentView(R.layout.activity_fullscreen_image)

        val photoView = findViewById<PhotoView>(R.id.photoView)
        val btnClose = findViewById<ImageView>(R.id.btnClose)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)

        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE)

        tvTitle.text = title ?: ""
        btnClose.setOnClickListener { finish() }

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

        setupSwipeToDismiss(photoView)
        hideSystemBars()

        Logger.log(TAG, "=== FullscreenImageActivity onCreate FINISHED ===")
    }

    // ============================================================
    // СВАЙП ВНИЗ ДЛЯ ЗАКРЫТИЯ
    // Работает только когда фото не увеличено (scale <= 1.05)
    // ============================================================
    private fun setupSwipeToDismiss(photoView: PhotoView) {
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val deltaY = e2.y - e1.y
                    val deltaX = e2.x - e1.x

                    // Только если фото не увеличено, свайп вниз быстрый и вертикальный
                    if (photoView.scale <= 1.05f &&
                        deltaY > 150 &&
                        Math.abs(deltaY) > Math.abs(deltaX) * 2
                    ) {
                        Logger.log(TAG, "Swipe down detected, closing")
                        finish()
                        return true
                    }
                    return false
                }
            }
        )

        photoView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false  // не перехватываем — PhotoView продолжает работать
        }
    }

    // ============================================================
    // СКРЫТИЕ СИСТЕМНЫХ ПАНЕЛЕЙ (immersive mode)
    // ============================================================
    private fun hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+)
                window.setDecorFitsSystemWindows(false)
                val controller = window.insetsController
                controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                // Android 10 и ниже
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

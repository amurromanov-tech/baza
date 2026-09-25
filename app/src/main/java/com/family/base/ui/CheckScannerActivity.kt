package com.family.base.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.family.base.databinding.ActivityCheckScannerBinding
import com.family.base.util.Logger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.cyrillic.CyrillicTextRecognizerOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CheckScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckScannerBinding
    private val TAG = "CheckScannerActivity"
    private val CAMERA_PERMISSION_REQUEST = 300

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    // ===== LAUNCHER ДЛЯ CheckPreviewActivity =====
    private val checkPreviewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Logger.log(TAG, "CheckPreview returned RESULT_OK — передаю дальше в MainActivity")
            // Пробрасываем результат дальше
            setResult(RESULT_OK, result.data)
            finish()
        } else {
            Logger.log(TAG, "CheckPreview cancelled — возвращаемся в сканер")
            // Пользователь отменил — можно снова фотографировать
            showLoading(false)
        }
    }

    companion object {
        const val EXTRA_RECOGNIZED_TEXT = "recognized_text"
        const val EXTRA_IMAGE_PATH = "image_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== onCreate START ===")

        try {
            binding = ActivityCheckScannerBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            finish()
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        binding.btnCapture.setOnClickListener { takePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    // ============================================================
    // ЗАПУСК КАМЕРЫ
    // ============================================================
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

                Logger.log(TAG, "Camera started")
            } catch (e: Exception) {
                Logger.log(TAG, "Camera start error", e)
                Toast.makeText(this, "Ошибка запуска камеры", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ============================================================
    // СНЯТЬ ФОТО
    // ============================================================
    private fun takePhoto() {
        val capture = imageCapture ?: run {
            Toast.makeText(this, "Камера не готова", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true, "Фотографирую…")

        val photoFile = File(cacheDir, "check_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Logger.log(TAG, "Capture failed: ${exc.message}", exc)
                    showLoading(false)
                    Toast.makeText(
                        this@CheckScannerActivity,
                        "Ошибка съёмки: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileOptions) {
                    Logger.log(TAG, "Photo saved: ${photoFile.absolutePath}")
                    recognizeText(photoFile)
                }
            }
        )
    }

    // ============================================================
    // РАСПОЗНАВАНИЕ ТЕКСТА
    // ============================================================
    private fun recognizeText(photoFile: File) {
        showLoading(true, "Распознаю текст…")

        try {
            val bitmap = loadBitmapWithExif(photoFile)
            if (bitmap == null) {
                showLoading(false)
                Toast.makeText(this, "Не удалось загрузить фото", Toast.LENGTH_SHORT).show()
                return
            }

            val image = InputImage.fromBitmap(bitmap, 0)

            val recognizer = TextRecognition.getClient(
                CyrillicTextRecognizerOptions.Builder().build()
            )

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val fullText = visionText.text
                    Logger.log(TAG, "Recognized text length: ${fullText.length}")
                    Logger.log(TAG, "Text preview: ${fullText.take(300)}")

                    if (fullText.isBlank()) {
                        showLoading(false)
                        Toast.makeText(
                            this,
                            "Не удалось распознать текст. Попробуйте ещё раз.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@addOnSuccessListener
                    }

                    // Сохраняем фото на будущее
                    val permanentFile = File(filesDir, "last_check.jpg")
                    try {
                        photoFile.copyTo(permanentFile, overwrite = true)
                    } catch (e: Exception) {
                        Logger.log(TAG, "copyTo permanentFile error: ${e.message}")
                    }

                    // ===== Открываем CheckPreviewActivity через launcher =====
                    val intent = Intent(this, CheckPreviewActivity::class.java).apply {
                        putExtra(EXTRA_RECOGNIZED_TEXT, fullText)
                        putExtra(EXTRA_IMAGE_PATH, permanentFile.absolutePath)
                    }
                    checkPreviewLauncher.launch(intent)
                    // НЕ вызываем finish() — ждём результат
                }
                .addOnFailureListener { e ->
                    Logger.log(TAG, "OCR failed: ${e.message}", e)
                    showLoading(false)
                    Toast.makeText(
                        this,
                        "Ошибка распознавания: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

        } catch (e: Exception) {
            Logger.log(TAG, "recognizeText error", e)
            showLoading(false)
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Загружает Bitmap с учётом EXIF-ориентации.
     */
    private fun loadBitmapWithExif(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }

            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Logger.log(TAG, "loadBitmapWithExif error: ${e.message}")
            null
        }
    }

    // ============================================================
    // ПРОГРЕСС
    // ============================================================
    private fun showLoading(show: Boolean, text: String = "") {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show && text.isNotEmpty()) {
            binding.tvLoadingText.text = text
        }
        binding.btnCapture.isEnabled = !show
    }

    // ============================================================
    // РАЗРЕШЕНИЯ
    // ============================================================
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Без камеры сканировать чек нельзя", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraExecutor.shutdown()
        } catch (e: Exception) {
            Logger.log(TAG, "executor shutdown error: ${e.message}")
        }
        Logger.log(TAG, "onDestroy called")
    }
}

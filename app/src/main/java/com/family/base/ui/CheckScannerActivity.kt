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
import androidx.lifecycle.lifecycleScope
import com.family.base.databinding.ActivityCheckScannerBinding
import com.family.base.util.Logger
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CheckScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckScannerBinding
    private val TAG = "CheckScannerActivity"
    private val CAMERA_PERMISSION_REQUEST = 300

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    // ===== TESSERACT =====
    private var tessApi: TessBaseAPI? = null

    private val tessDataDir: File
        get() = File(filesDir, "tesseract")

    // ===== LAUNCHER ДЛЯ CheckPreviewActivity =====
    private val checkPreviewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Logger.log(TAG, "CheckPreview returned RESULT_OK — передаю дальше в MainActivity")
            setResult(RESULT_OK, result.data)
            finish()
        } else {
            Logger.log(TAG, "CheckPreview cancelled — возвращаемся в сканер")
            showLoading(false)
        }
    }

    companion object {
        const val EXTRA_RECOGNIZED_TEXT = "recognized_text"
        const val EXTRA_IMAGE_PATH = "image_path"

        private const val TESS_LANGUAGES = "rus+eng"
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

        // Инициализация Tesseract
        initTesseract()

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
    // ИНИЦИАЛИЗАЦИЯ TESSERACT
    // ============================================================
    private fun initTesseract() {
        showLoading(true, "Подготовка OCR…")

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    copyTessDataIfNeeded("rus.traineddata")
                    copyTessDataIfNeeded("eng.traineddata")

                    val api = TessBaseAPI()
                    val dataPath = tessDataDir.absolutePath
                    val initialized = api.init(dataPath, TESS_LANGUAGES)

                    if (!initialized) {
                        throw IllegalStateException("Tesseract init failed for path: $dataPath")
                    }

                    api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                    api.setVariable("preserve_interword_spaces", "1")

                    tessApi = api

                    Logger.log(TAG, "Tesseract initialized, path=$dataPath, langs=$TESS_LANGUAGES")
                }

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Logger.log(TAG, "Tesseract ready")
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Tesseract init error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(
                        this@CheckScannerActivity,
                        "Ошибка OCR: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun copyTessDataIfNeeded(fileName: String) {
        val tessdataDir = File(tessDataDir, "tessdata")
        if (!tessdataDir.exists()) tessdataDir.mkdirs()

        val targetFile = File(tessdataDir, fileName)
        if (targetFile.exists() && targetFile.length() > 0) {
            Logger.log(TAG, "Traineddata already exists: ${targetFile.absolutePath}")
            return
        }

        try {
            assets.open("tessdata/$fileName").use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Logger.log(TAG, "Copied traineddata: $fileName → ${targetFile.absolutePath}")
        } catch (e: Exception) {
            Logger.log(TAG, "Failed to copy $fileName: ${e.message}", e)
            throw e
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

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Logger.log(TAG, "Photo saved: ${photoFile.absolutePath}")
                    recognizeText(photoFile)
                }
            }
        )
    }

    // ============================================================
    // РАСПОЗНАВАНИЕ ТЕКСТА (TESSERACT)
    // ============================================================
    private fun recognizeText(photoFile: File) {
        showLoading(true, "Распознаю текст…")

        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val bitmap = loadBitmapWithExif(photoFile)
                        ?: throw IllegalStateException("Не удалось загрузить фото")

                    val api = tessApi
                        ?: throw IllegalStateException("Tesseract не инициализирован")

                    api.setImage(bitmap)
                    val result = api.utF8Text ?: ""

                    bitmap.recycle()
                    api.clear()

                    result
                }

                Logger.log(TAG, "Tesseract recognized length: ${text.length}")
                Logger.log(TAG, "Text preview: ${text.take(300)}")

                if (text.isBlank()) {
                    showLoading(false)
                    Toast.makeText(
                        this@CheckScannerActivity,
                        "Не удалось распознать текст. Попробуйте ещё раз.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val permanentFile = File(filesDir, "last_check.jpg")
                try {
                    photoFile.copyTo(permanentFile, overwrite = true)
                } catch (e: Exception) {
                    Logger.log(TAG, "copyTo permanentFile error: ${e.message}")
                }

                val intent = Intent(this@CheckScannerActivity, CheckPreviewActivity::class.java).apply {
                    putExtra(EXTRA_RECOGNIZED_TEXT, text)
                    putExtra(EXTRA_IMAGE_PATH, permanentFile.absolutePath)
                }
                checkPreviewLauncher.launch(intent)

            } catch (e: Exception) {
                Logger.log(TAG, "recognizeText error: ${e.message}", e)
                showLoading(false)
                Toast.makeText(
                    this@CheckScannerActivity,
                    "Ошибка распознавания: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadBitmapWithExif(file: File): Bitmap? {
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null

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

    private fun showLoading(show: Boolean, text: String = "") {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show && text.isNotEmpty()) {
            binding.tvLoadingText.text = text
        }
        binding.btnCapture.isEnabled = !show
    }

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
            tessApi?.recycle()
            tessApi = null
        } catch (e: Exception) {
            Logger.log(TAG, "tessApi.recycle error: ${e.message}")
        }
        try {
            cameraExecutor.shutdown()
        } catch (e: Exception) {
            Logger.log(TAG, "executor shutdown error: ${e.message}")
        }
        Logger.log(TAG, "onDestroy called")
    }
}

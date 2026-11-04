package com.family.base.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.family.base.databinding.ActivityBarcodeScannerBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class BarcodeScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBarcodeScannerBinding
    private val TAG = "BarcodeScannerActivity"
    private val CAMERA_PERMISSION_REQUEST = 200

    private var isScanning = true

    companion object {
        /**
         * 🆕 Режим сканирования: обычный штрих-код или QR чека.
         * Если передан EXTRA_SCAN_MODE = MODE_RECEIPT_QR, то после
         * распознавания QR-кода сразу открывается ReceiptPreviewActivity
         * (запрос к proverkacheka.com), а не возвращается результат.
         */
        const val EXTRA_SCAN_MODE = "extra_scan_mode"
        const val MODE_DEFAULT = "default"
        const val MODE_RECEIPT_QR = "receipt_qr"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBarcodeScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                            processImageProxy(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска камеры", e)
                Toast.makeText(this, "Ошибка запуска камеры", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!isScanning) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_AZTEC
            )
            .build()

        val scanner = BarcodeScanning.getClient(options)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty() && isScanning) {
                    isScanning = false
                    val barcode = barcodes.first()
                    val value = barcode.rawValue ?: ""
                    Log.d(TAG, "Распознано: $value")

                    runOnUiThread {
                        Toast.makeText(this, "Код: $value", Toast.LENGTH_SHORT).show()
                    }

                    val scanMode = intent.getStringExtra(EXTRA_SCAN_MODE) ?: MODE_DEFAULT
                    if (scanMode == MODE_RECEIPT_QR) {
                        // 🆕 QR чека → сразу на экран предпросмотра чека
                        val intent = android.content.Intent(
                            this,
                            ReceiptPreviewActivity::class.java
                        ).apply {
                            putExtra(ReceiptPreviewActivity.EXTRA_QR_RAW, value)
                        }
                        startActivity(intent)
                        finish()
                    } else {
                        // Обычный режим — вернуть результат в вызывающую Activity
                        val resultIntent = android.content.Intent()
                        resultIntent.putExtra("barcode", value)
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Ошибка распознавания", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
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
                Toast.makeText(this, "Необходимо разрешение на камеру", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}

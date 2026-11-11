package com.family.base.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
         * Режим сканирования:
         *  - MODE_DEFAULT — обычный штрих-код (1D + все 2D, как раньше);
         *  - MODE_RECEIPT_QR — QR чека → сразу ReceiptPreviewActivity;
         *  - MODE_QR_DATAMATRIX — 🆕 v21.0.7: QR/DataMatrix Честного Знака,
         *    высокое разрешение (1280×720), только 2D-форматы.
         */
        const val EXTRA_SCAN_MODE = "extra_scan_mode"
        const val MODE_DEFAULT = "default"
        const val MODE_RECEIPT_QR = "receipt_qr"
        const val MODE_QR_DATAMATRIX = "qr_datamatrix"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBarcodeScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 🆕 v21.0.7: подсказка зависит от режима
        val scanMode = intent.getStringExtra(EXTRA_SCAN_MODE) ?: MODE_DEFAULT
        binding.tvHint.text = if (scanMode == MODE_QR_DATAMATRIX) {
            "Наведите камеру на QR / DataMatrix (Честный ЗНАК)"
        } else {
            "Наведите камеру на штрих-код"
        }

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
        val scanMode = intent.getStringExtra(EXTRA_SCAN_MODE) ?: MODE_DEFAULT
        val isQrMode = scanMode == MODE_QR_DATAMATRIX

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                // 🆕 v21.0.7: в QR-режиме поднимаем разрешение анализатора
                // до 1280×720 — DataMatrix «Честного Знака» плотный,
                // дефолтных 640×480 ML Kit не хватает.
                // Используем ResolutionSelector (setTargetResolution deprecated в CameraX 1.3+).
                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

                if (isQrMode) {
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                    analysisBuilder.setResolutionSelector(resolutionSelector)
                }

                val imageAnalyzer = analysisBuilder.build().also {
                    it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        processImageProxy(imageProxy, isQrMode)
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
    private fun processImageProxy(imageProxy: ImageProxy, isQrMode: Boolean) {
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

        // 🆕 v21.0.7: в QR-режиме ограничиваем форматы только 2D —
        // это ускоряет детекцию и снижает ложные срабатывания на 1D.
        val options = if (isQrMode) {
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_PDF417
                )
                .build()
        } else {
            BarcodeScannerOptions.Builder()
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
        }

        val scanner = BarcodeScanning.getClient(options)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty() && isScanning) {
                    isScanning = false
                    val barcode = barcodes.first()
                    val value = barcode.rawValue ?: ""
                    Log.d(TAG, "Распознано: format=${barcode.format}, raw=$value")

                    runOnUiThread {
                        Toast.makeText(this, "Код: $value", Toast.LENGTH_SHORT).show()
                    }

                    val scanMode = intent.getStringExtra(EXTRA_SCAN_MODE) ?: MODE_DEFAULT
                    if (scanMode == MODE_RECEIPT_QR) {
                        val intent = android.content.Intent(
                            this,
                            ReceiptPreviewActivity::class.java
                        ).apply {
                            putExtra(ReceiptPreviewActivity.EXTRA_QR_RAW, value)
                        }
                        startActivity(intent)
                        finish()
                    } else {
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

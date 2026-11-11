package com.family.base.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.budiyev.android.codescanner.AutoFocusMode
import com.budiyev.android.codescanner.CodeScanner
import com.budiyev.android.codescanner.CodeScannerView
import com.budiyev.android.codescanner.DecodeCallback
import com.budiyev.android.codescanner.ErrorCallback
import com.budiyev.android.codescanner.ScanMode
import com.family.base.databinding.ActivityQrScannerBinding
import com.family.base.util.Logger
import com.google.zxing.BarcodeFormat

/**
 * 🆕 v21.0.8: сканер QR/DataMatrix/PDF417 на базе ZXing (code-scanner).
 *
 * Зачем: ML Kit плохо распознаёт плотные PDF417 (медицинская маркировка
 * «Честного Знака») и мелкие DataMatrix. ZXing справляется лучше.
 * Поддерживаемые форматы: PDF_417, DATA_MATRIX, QR_CODE, AZTEC.
 *
 * Режим: SINGLE — одно успешное сканирование и возврат результата.
 * Автофокус: CONTINUOUS — камера сама цепляется за мелкий код.
 */
class QrScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var codeScanner: CodeScanner
    private val TAG = "QrScannerActivity"
    private val CAMERA_PERMISSION_REQUEST = 300

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            initScanner()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    private fun initScanner() {
        val scannerView: CodeScannerView = binding.scannerView
        codeScanner = CodeScanner(this, scannerView)

        // 🆕 v21.0.8: только 2D-форматы, которые нужны Честному Знаку.
        // PDF_417 — медицинская маркировка, DATA_MATRIX — молочка/вода,
        // QR_CODE — на всякий случай (некоторые производители).
        codeScanner.formats = listOf(
            BarcodeFormat.PDF_417,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.QR_CODE,
            BarcodeFormat.AZTEC
        )

        // Непрерывный автофокус — критично для мелких PDF417.
        codeScanner.autoFocusMode = AutoFocusMode.CONTINUOUS
        codeScanner.isAutoFocusEnabled = true
        codeScanner.isFlashEnabled = false
        codeScanner.scanMode = ScanMode.SINGLE

        codeScanner.decodeCallback = DecodeCallback { result ->
            val value = result.text ?: ""
            Logger.log(TAG, "Decoded: format=${result.barcodeFormat}, text=$value")

            runOnUiThread {
                if (value.isNotEmpty()) {
                    val intent = Intent().apply {
                        putExtra("barcode", value)
                    }
                    setResult(RESULT_OK, intent)
                    finish()
                } else {
                    // Пустой результат — разрешаем сканировать дальше
                    codeScanner.startPreview()
                }
            }
        }

        codeScanner.errorCallback = ErrorCallback { error ->
            Logger.log(TAG, "Scanner error: ${error.message}", error)
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Ошибка сканера: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Тап по превью — принудительный автофокус
        scannerView.setOnClickListener {
            codeScanner.startPreview()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::codeScanner.isInitialized) {
            codeScanner.startPreview()
        }
    }

    override fun onPause() {
        if (::codeScanner.isInitialized) {
            codeScanner.releaseResources()
        }
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initScanner()
            } else {
                Toast.makeText(this, "Необходимо разрешение на камеру", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}

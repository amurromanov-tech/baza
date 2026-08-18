package com.family.base.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.family.base.R
import com.family.base.data.TokenStorage
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.remote.OpenFoodFactsApi
import com.family.base.databinding.ActivityAddItemBinding
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AddItemActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddItemBinding
    private lateinit var tokenStorage: TokenStorage
    private lateinit var viewModel: MainViewModel
    private val TAG = "AddItemActivity"
    private var currentPhotoFile: File? = null
    private var currentPhotoBytes: ByteArray? = null
    private var scannedBarcode: String? = null
    private var isAutoMode = false
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    // Камера
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCamera()
        } else {
            Toast.makeText(this, "Нет доступа к камере", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            currentPhotoFile?.let { file ->
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                processPhoto(bitmap)
            }
        }
    }

    // Сканер штрих-кода
    private val barcodeScannerLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            currentPhotoFile?.let { file ->
                scanBarcode(file)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== AddItemActivity onCreate START ===")

        try {
            binding = ActivityAddItemBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Binding inflated successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        try {
            tokenStorage = TokenStorage(this)
            viewModel = MainViewModel(application)
            Logger.log(TAG, "ViewModel and TokenStorage initialized")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to initialize", e)
            return
        }

        setupListeners()
        showModeSelection()

        // DatePicker для даты (ручной режим)
        binding.etManualExpiry.setOnClickListener {
            showDatePickerDialog(isAuto = false)
        }
        binding.etAutoExpiry.setOnClickListener {
            showDatePickerDialog(isAuto = true)
        }

        // ===== АВТО-ДАТА ДЛЯ "ДРУГОЕ" =====
        binding.rgType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbOther) {
                val currentDate = dateFormat.format(Date())
                binding.etManualExpiry.setText(currentDate)
                binding.etManualExpiry.isEnabled = false
            } else {
                binding.etManualExpiry.isEnabled = true
                binding.etManualExpiry.text?.clear()
            }
        }

        Logger.log(TAG, "=== AddItemActivity onCreate FINISHED ===")
    }

    private fun showDatePickerDialog(isAuto: Boolean) {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val datePicker = DatePickerDialog(
            this,
            DatePickerDialog.OnDateSetListener { _, selectedYear, selectedMonth, selectedDay ->
                val dateStr = "${selectedDay.toString().padStart(2, '0')}.${(selectedMonth + 1).toString().padStart(2, '0')}.$selectedYear"
                if (isAuto) {
                    binding.etAutoExpiry.setText(dateStr)
                } else {
                    binding.etManualExpiry.setText(dateStr)
                }
                Logger.log(TAG, "Date selected: $dateStr")
            },
            year, month, day
        )
        datePicker.show()
    }

    private fun setupListeners() {
        Logger.log(TAG, "Setting up listeners")

        binding.btnAutoMode.setOnClickListener {
            Logger.log(TAG, "Auto mode selected")
            isAutoMode = true
            showAutoMode()
        }

        binding.btnManualMode.setOnClickListener {
            Logger.log(TAG, "Manual mode selected")
            isAutoMode = false
            showManualMode()
        }

        binding.btnScanBarcode.setOnClickListener {
            Logger.log(TAG, "Scan barcode clicked")
            checkCameraPermissionForBarcode()
        }

        binding.btnTakePhoto.setOnClickListener {
            Logger.log(TAG, "Take photo clicked")
            checkCameraPermissionForPhoto()
        }

        binding.btnSave.setOnClickListener {
            Logger.log(TAG, "Save clicked")
            saveItem()
        }

        binding.btnCancel.setOnClickListener {
            Logger.log(TAG, "Cancel clicked")
            finish()
        }
    }

    private fun showModeSelection() {
        Logger.log(TAG, "Showing mode selection")
        binding.modeSelection.visibility = android.view.View.VISIBLE
        binding.autoModeLayout.visibility = android.view.View.GONE
        binding.manualModeLayout.visibility = android.view.View.GONE
    }

    private fun showAutoMode() {
        Logger.log(TAG, "Showing auto mode")
        binding.modeSelection.visibility = android.view.View.GONE
        binding.autoModeLayout.visibility = android.view.View.VISIBLE
        binding.manualModeLayout.visibility = android.view.View.GONE
        binding.btnScanBarcode.isEnabled = true
    }

    private fun showManualMode() {
        Logger.log(TAG, "Showing manual mode")
        binding.modeSelection.visibility = android.view.View.GONE
        binding.autoModeLayout.visibility = android.view.View.GONE
        binding.manualModeLayout.visibility = android.view.View.VISIBLE
    }

    // ========== ШТРИХ-КОД ==========

    private fun checkCameraPermissionForBarcode() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                openCameraForBarcode()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showPermissionRationale()
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun openCameraForBarcode() {
        Logger.log(TAG, "Opening camera for barcode scanning")
        try {
            val photoFile = createTempFile("barcode_")
            currentPhotoFile = photoFile
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                photoFile
            )
            barcodeScannerLauncher.launch(uri)
        } catch (e: Exception) {
            Logger.log(TAG, "Error opening camera", e)
            Toast.makeText(this, "Ошибка открытия камеры", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanBarcode(file: File) {
        Logger.log(TAG, "Scanning barcode from photo")
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            val image = InputImage.fromBitmap(bitmap, 0)

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_ALL_FORMATS
                )
                .build()

            val scanner = BarcodeScanning.getClient(options)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        val barcode = barcodes[0]
                        scannedBarcode = barcode.rawValue
                        Logger.log(TAG, "Barcode scanned: $scannedBarcode")
                        searchProduct(scannedBarcode!!)
                    } else {
                        Logger.log(TAG, "No barcode found")
                        Toast.makeText(
                            this,
                            "Штрих-код не распознан, переключитесь на ручной режим",
                            Toast.LENGTH_LONG
                        ).show()
                        showManualMode()
                    }
                }
                .addOnFailureListener { e ->
                    Logger.log(TAG, "Error scanning barcode", e)
                    Toast.makeText(
                        this,
                        "Ошибка сканирования, переключитесь на ручной режим",
                        Toast.LENGTH_LONG
                    ).show()
                    showManualMode()
                }
                .addOnCompleteListener {
                    scanner.close()
                }
        } catch (e: Exception) {
            Logger.log(TAG, "Error scanning barcode", e)
            Toast.makeText(this, "Ошибка обработки штрих-кода", Toast.LENGTH_SHORT).show()
            showManualMode()
        }
    }

    private fun searchProduct(barcode: String) {
        Logger.log(TAG, "Searching product by barcode: $barcode")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = OpenFoodFactsApi.getInstance()
                val response = api.getProductByBarcode(barcode)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val productResponse = response.body()
                        if (productResponse != null && productResponse.status == 1) {
                            productResponse.product?.let { product ->
                                fillFieldsFromProduct(product)
                            }
                        } else {
                            Logger.log(TAG, "Product not found in OpenFoodFacts")
                            Toast.makeText(
                                this@AddItemActivity,
                                "Продукт не найден, переключитесь на ручной режим",
                                Toast.LENGTH_LONG
                            ).show()
                            showManualMode()
                        }
                    } else {
                        Logger.log(TAG, "API error: ${response.code()}")
                        Toast.makeText(
                            this@AddItemActivity,
                            "Ошибка поиска продукта, переключитесь на ручной режим",
                            Toast.LENGTH_LONG
                        ).show()
                        showManualMode()
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error searching product", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@AddItemActivity,
                        "Ошибка поиска продукта, переключитесь на ручной режим",
                        Toast.LENGTH_LONG
                    ).show()
                    showManualMode()
                }
            }
        }
    }

    private fun fillFieldsFromProduct(product: com.family.base.data.remote.model.Product) {
        Logger.log(TAG, "Filling fields from product")
        binding.etAutoName.setText(product.productName ?: "")
        binding.etAutoDescription.setText(product.description ?: product.genericName ?: "")
        binding.etAutoType.setText(product.categories?.split(",")?.firstOrNull()?.trim() ?: "food")

        product.expirationDate?.let { date ->
            try {
                val parsedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date)
                parsedDate?.let {
                    val dateStr = dateFormat.format(it)
                    binding.etAutoExpiry.setText(dateStr)
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error parsing expiry date", e)
            }
        }

        product.quantity?.let { qty ->
            try {
                val num = qty.replace(Regex("[^0-9]"), "").toIntOrNull()
                if (num != null && num > 0) {
                    binding.etAutoQuantity.setText(num.toString())
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error parsing quantity", e)
            }
        }

        product.imageUrl?.let { imageUrl ->
            loadProductImage(imageUrl)
        }

        binding.btnScanBarcode.isEnabled = false
    }

    private fun loadProductImage(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = OpenFoodFactsApi.getInstance()
                val response = api.downloadImage(url)
                if (response.isSuccessful) {
                    val bytes = response.body()?.bytes()
                    bytes?.let {
                        val bitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
                        withContext(Dispatchers.Main) {
                            val processedBytes = ImageUtils.processImage(bitmap)
                            currentPhotoBytes = processedBytes
                            val previewBitmap = ImageUtils.decodeByteArray(processedBytes)
                            previewBitmap?.let { bmp ->
                                binding.ivAutoPhoto.setImageBitmap(bmp)
                                binding.ivAutoPhoto.visibility = android.view.View.VISIBLE
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading product image", e)
            }
        }
    }

    // ========== ФОТО (РУЧНОЙ РЕЖИМ) ==========

    private fun checkCameraPermissionForPhoto() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                openCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showPermissionRationale()
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun openCamera() {
        Logger.log(TAG, "Opening camera for photo")
        try {
            val photoFile = createTempFile("photo_")
            currentPhotoFile = photoFile
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                photoFile
            )
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Logger.log(TAG, "Error opening camera", e)
            Toast.makeText(this, "Ошибка открытия камеры", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processPhoto(bitmap: Bitmap) {
        Logger.log(TAG, "Processing photo")
        try {
            val processedBytes = ImageUtils.processImage(bitmap)
            currentPhotoBytes = processedBytes

            val previewBitmap = ImageUtils.decodeByteArray(processedBytes)
            previewBitmap?.let {
                binding.ivManualPhoto.setImageBitmap(it)
                binding.ivManualPhoto.visibility = android.view.View.VISIBLE
                Logger.log(TAG, "Photo processed successfully")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error processing photo", e)
            Toast.makeText(this, "Ошибка обработки фото", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createTempFile(prefix: String): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = cacheDir
        return File.createTempFile("$prefix$timeStamp", ".jpg", storageDir)
    }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Доступ к камере")
            .setMessage("Для сканирования штрих-кода и создания фото нужен доступ к камере")
            .setPositiveButton("OK") { _, _ ->
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton("Отмена") { _, _ ->
                Toast.makeText(this, "Без камеры эта функция недоступна", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ========== СОХРАНЕНИЕ ==========

    private fun saveItem() {
        Logger.log(TAG, "saveItem called")

        val parentId = intent.getStringExtra("parent_id")
        val userEmail = tokenStorage.getUserEmail() ?: "unknown"

        val name = if (isAutoMode) {
            binding.etAutoName.text.toString().trim()
        } else {
            binding.etManualName.text.toString().trim()
        }

        if (name.isEmpty()) {
            Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
            return
        }

        val quantity = if (isAutoMode) {
            binding.etAutoQuantity.text.toString().toIntOrNull() ?: 1
        } else {
            binding.etManualQuantity.text.toString().toIntOrNull() ?: 1
        }

        // Срок годности — если тип "другое", не используем
        val itemType = if (isAutoMode) {
            binding.etAutoType.text.toString().ifEmpty { "other" }
        } else {
            when (binding.rgType.checkedRadioButtonId) {
                R.id.rbFood -> "food"
                R.id.rbMedicine -> "medicine"
                R.id.rbOther -> "other"
                else -> "other"
            }
        }

        val expiryDate = parseDate(if (isAutoMode) binding.etAutoExpiry.text.toString() else binding.etManualExpiry.text.toString())


        val description = if (isAutoMode) {
            binding.etAutoDescription.text.toString()
        } else {
            binding.etManualDescription.text.toString()
        }

        val price = binding.etPrice.text.toString().toDoubleOrNull()

        val item = ItemEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            parentId = parentId,
            quantity = quantity,
            barcode = scannedBarcode,
            description = description,
            expiryDate = expiryDate,
            addedBy = userEmail,
            updatedBy = userEmail,
            addedDate = System.currentTimeMillis(),
            updatedDate = System.currentTimeMillis(),
            daysUntilExpiry = Int.MAX_VALUE,
            isExpired = false,
            itemType = itemType,
            price = price
        )
        item.computeExpiryFields()

        Logger.log(TAG, "Saving item: $name, type: $itemType, parentId: $parentId, price: $price")

        viewModel.createItem(item, currentPhotoBytes)

        Toast.makeText(this, "Предмет создан", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun parseDate(dateStr: String): Long? {
        if (dateStr.isEmpty()) return null
        return try {
            val date = dateFormat.parse(dateStr)
            date?.time
        } catch (e: Exception) {
            Logger.log(TAG, "Error parsing date", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

package com.family.base.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.model.SubtypeCatalog
import com.family.base.data.remote.ProductLookupService
import com.family.base.data.repository.CatalogRepository
import com.family.base.databinding.ActivityAddItemBinding
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AddItemActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddItemBinding
    private lateinit var db: AppDatabase
    private lateinit var repository: CatalogRepository
    private lateinit var viewModel: MainViewModel
    private val productLookupService = ProductLookupService()

    private val TAG = "AddItemActivity"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val CAMERA_PERMISSION_REQUEST = 100

    private var parentFolderId: String? = null
    private var imageBytes: ByteArray? = null
    private var expiryDate: Long? = null
    private var barcode: String? = null

    private var photoUri: Uri? = null

    // ===== ТЕКУЩИЕ ПОДТИПЫ =====
    private var currentAutoType: String = "thing"
    private var currentManualType: String = "thing"
    private var selectedAutoSubtype: String? = null
    private var selectedManualSubtype: String? = null

    // ===== ВЫБОР ИЗ ГАЛЕРЕИ =====
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bitmap = ImageUtils.loadBitmapWithExif(this, it) ?: return@let
                val processedBytes = ImageUtils.processImage(bitmap)
                imageBytes = processedBytes
                binding.ivManualPhoto.setImageBitmap(bitmap)
                binding.ivManualPhoto.visibility = View.VISIBLE
                Logger.log(TAG, "Image selected from gallery, size=${processedBytes.size}")
            } catch (e: Exception) {
                Logger.log(TAG, "Error picking image", e)
                Toast.makeText(this, "Ошибка выбора фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== ФОТО С КАМЕРЫ =====
    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val uri = photoUri ?: return@registerForActivityResult
            try {
                val bitmap = ImageUtils.loadBitmapWithExif(this, uri) ?: return@registerForActivityResult
                val processedBytes = ImageUtils.processImage(bitmap)
                imageBytes = processedBytes
                binding.ivManualPhoto.setImageBitmap(bitmap)
                binding.ivManualPhoto.visibility = View.VISIBLE
                Logger.log(TAG, "Photo captured, size=${processedBytes.size}")
            } catch (e: Exception) {
                Logger.log(TAG, "Error processing camera photo", e)
                Toast.makeText(this, "Ошибка обработки фото", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Фото не сделано", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== СКАНЕР ШТРИХ-КОДА =====
    private val barcodeScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scannedValue = result.data?.getStringExtra("barcode")
            if (!scannedValue.isNullOrEmpty()) {
                Logger.log(TAG, "Scanned value: $scannedValue")

                if (scannedValue.all { it.isDigit() }) {
                    barcode = scannedValue
                    binding.etAutoBarcode.setText(scannedValue)
                    lookupProduct(scannedValue)
                } else {
                    binding.etAutoName.setText(scannedValue)
                    Toast.makeText(this, "QR-код распознан: $scannedValue", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityAddItemBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            finish()
            return
        }

        try {
            db = AppDatabase.getInstance(this)
            repository = CatalogRepository(db)
            viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to initialize", e)
            finish()
            return
        }

        parentFolderId = intent.getStringExtra("parent_id")

        val incomingBarcode = intent.getStringExtra("barcode")
        if (!incomingBarcode.isNullOrEmpty()) {
            barcode = incomingBarcode
        }

        setupListeners()
        initSubtypeDropdowns()
        updateSubtypesForAuto(currentAutoType)
        updateSubtypesForManual(currentManualType)

        // ===== ЕСЛИ ПРИШЁЛ КОД — AUTO-РЕЖИМ + ПОИСК =====
        incomingBarcode?.let { code ->
            binding.modeSelection.visibility = View.GONE
            binding.autoModeLayout.visibility = View.VISIBLE
            binding.manualModeLayout.visibility = View.GONE

            binding.etAutoBarcode.setText(code)

            if (code.all { it.isDigit() }) {
                lookupProduct(code)
            } else {
                binding.etAutoName.setText(code)
                Toast.makeText(this, "QR-код распознан: $code", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupListeners() {
        binding.btnManualMode.setOnClickListener {
            binding.modeSelection.visibility = View.GONE
            binding.manualModeLayout.visibility = View.VISIBLE
            binding.autoModeLayout.visibility = View.GONE
        }

        binding.btnAutoMode.setOnClickListener {
            binding.modeSelection.visibility = View.GONE
            binding.autoModeLayout.visibility = View.VISIBLE
            binding.manualModeLayout.visibility = View.GONE
        }

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { saveItem() }

        binding.etManualExpiry.setOnClickListener { showDatePickerDialog(binding.etManualExpiry) }
        binding.etAutoExpiry.setOnClickListener { showDatePickerDialog(binding.etAutoExpiry) }

        binding.btnTakePhoto.setOnClickListener { showImageSourceDialog() }

        binding.btnScanBarcode.setOnClickListener {
            barcodeScannerLauncher.launch(Intent(this, BarcodeScannerActivity::class.java))
        }

        // ===== ТИП AUTO → пересобрать подтипы =====
        binding.rgAutoType.setOnCheckedChangeListener { _, checkedId ->
            currentAutoType = when (checkedId) {
                R.id.rbAutoFood -> "food"
                R.id.rbAutoMedicine -> "medicine"
                R.id.rbAutoThing -> "thing"
                else -> "other"
            }
            selectedAutoSubtype = null
            updateSubtypesForAuto(currentAutoType)
        }

        // ===== ТИП MANUAL → пересобрать подтипы =====
        binding.rgType.setOnCheckedChangeListener { _, checkedId ->
            currentManualType = when (checkedId) {
                R.id.rbFood -> "food"
                R.id.rbMedicine -> "medicine"
                R.id.rbThing -> "thing"
                else -> "other"
            }
            selectedManualSubtype = null
            updateSubtypesForManual(currentManualType)
        }
    }

    // ============================================================
    // ПОДТИПЫ: ВЫПАДАЮЩИЕ СПИСКИ
    // ============================================================
    private fun initSubtypeDropdowns() {
        // Настраиваем оба AutoCompleteTextView
        setupDropdown(binding.autoCompleteSubtypeAuto, isAuto = true)
        setupDropdown(binding.autoCompleteSubtypeManual, isAuto = false)
    }

    private fun setupDropdown(view: MaterialAutoCompleteTextView, isAuto: Boolean) {
        view.setOnItemClickListener { _, _, position, _ ->
            val subtypes = SubtypeCatalog.getSubtypes(
                if (isAuto) currentAutoType else currentManualType
            )
            if (position in subtypes.indices) {
                val chosen = subtypes[position]
                if (isAuto) {
                    selectedAutoSubtype = chosen.key
                } else {
                    selectedManualSubtype = chosen.key
                }
                Logger.log(TAG, "Subtype chosen: ${chosen.key} (isAuto=$isAuto)")
            }
        }
    }

    private fun updateSubtypesForAuto(type: String) {
        val subtypes = SubtypeCatalog.getSubtypes(type)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            subtypes.map { it.displayName }
        )
        binding.autoCompleteSubtypeAuto.setAdapter(adapter)
        binding.autoCompleteSubtypeAuto.setText("", false)
        binding.autoCompleteSubtypeAuto.hint = "Выберите подтип"
    }

    private fun updateSubtypesForManual(type: String) {
        val subtypes = SubtypeCatalog.getSubtypes(type)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            subtypes.map { it.displayName }
        )
        binding.autoCompleteSubtypeManual.setAdapter(adapter)
        binding.autoCompleteSubtypeManual.setText("", false)
        binding.autoCompleteSubtypeManual.hint = "Выберите подтип"
    }

    // ============================================================
    // ПОИСК ТОВАРА + АВТО-ВЫБОР ТИПА
    // ============================================================
    private fun lookupProduct(barcode: String) {
        Toast.makeText(this, "Поиск товара...", Toast.LENGTH_SHORT).show()
        binding.etAutoName.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = productLookupService.lookupProduct(barcode)
                binding.etAutoName.isEnabled = true

                if (result.success && result.product != null) {
                    val product = result.product
                    Logger.log(TAG, "Product found: name=${product.name}, source=${product.source}")

                    val displayName = when {
                        !product.name.isNullOrEmpty() -> product.name
                        !product.brand.isNullOrEmpty() -> product.brand
                        !product.category.isNullOrEmpty() -> product.category
                        else -> barcode
                    }
                    binding.etAutoName.setText(displayName)

                    val descriptionText = buildString {
                        if (!product.brand.isNullOrEmpty() && product.brand != displayName) {
                            append("Бренд: ${product.brand}\n")
                        }
                        if (!product.category.isNullOrEmpty()) {
                            append("Категория: ${product.category}\n")
                        }
                        if (!product.description.isNullOrEmpty()) {
                            append("\n${product.description}")
                        }
                        if (product.source != null) {
                            append("\n\nИсточник: ${product.source}")
                        }
                    }.trim()

                    binding.etAutoDescription.setText(descriptionText)

                    autoSelectType(product.source, product.category)

                    Toast.makeText(
                        this@AddItemActivity,
                        "Найдено: $displayName (${product.source})",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@AddItemActivity,
                        "Товар не найден. Введите название вручную.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                binding.etAutoName.isEnabled = true
                Logger.log(TAG, "Error looking up product", e)
                Toast.makeText(this@AddItemActivity, "Ошибка поиска: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun autoSelectType(source: String?, category: String?) {
        val sourceLower = source?.lowercase(Locale.getDefault()) ?: ""
        val categoryLower = category?.lowercase(Locale.getDefault()) ?: ""

        val type = when {
            sourceLower.contains("open food facts") -> "food"
            sourceLower.contains("rxnorm") -> "medicine"
            sourceLower.contains("open library") -> "thing"
            sourceLower.contains("google books") -> "thing"
            categoryLower.contains("food") -> "food"
            categoryLower.contains("beverage") -> "food"
            categoryLower.contains("drink") -> "food"
            categoryLower.contains("snack") -> "food"
            categoryLower.contains("medicine") -> "medicine"
            categoryLower.contains("drug") -> "medicine"
            categoryLower.contains("vitamin") -> "medicine"
            else -> "thing"
        }

        Logger.log(TAG, "Auto-select type: source=$source → $type")

        currentAutoType = type
        when (type) {
            "food" -> binding.rgAutoType.check(R.id.rbAutoFood)
            "medicine" -> binding.rgAutoType.check(R.id.rbAutoMedicine)
            "thing" -> binding.rgAutoType.check(R.id.rbAutoThing)
            else -> binding.rgAutoType.check(R.id.rbAutoOther)
        }
        // после смены типа — пересобрать подтипы
        selectedAutoSubtype = null
        updateSubtypesForAuto(type)
    }

    private fun showImageSourceDialog() {
        val options = arrayOf("📸 Сделать фото", "🖼️ Выбрать из галереи")
        AlertDialog.Builder(this)
            .setTitle("Выберите источник фото")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (checkCameraPermission()) openCamera() else requestCameraPermission()
                    1 -> pickImageLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun checkCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }

    private fun openCamera() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val photoFile = File(cacheDir, "IMG_$timeStamp.jpg")
            photoUri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
            takePhotoLauncher.launch(photoUri)
        } catch (e: Exception) {
            Logger.log(TAG, "Error opening camera", e)
            Toast.makeText(this, "Ошибка открытия камеры: ${e.message}", Toast.LENGTH_SHORT).show()
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
                openCamera()
            } else {
                Toast.makeText(this, "Необходимо разрешение на использование камеры", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDatePickerDialog(targetEditText: com.google.android.material.textfield.TextInputEditText) {
        val calendar = Calendar.getInstance()
        val currentText = targetEditText.text.toString()
        if (currentText.isNotEmpty()) {
            try {
                val date = dateFormat.parse(currentText)
                date?.let { calendar.time = it }
            } catch (e: Exception) { /* ignore */ }
        }

        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val dateStr = String.format("%02d.%02d.%d", dayOfMonth, month + 1, year)
                targetEditText.setText(dateStr)
                try {
                    expiryDate = dateFormat.parse(dateStr)?.time
                } catch (e: Exception) {
                    expiryDate = null
                }
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ============================================================
    // СОХРАНЕНИЕ
    // ============================================================
    private fun saveItem() {
        val isAutoMode = binding.autoModeLayout.visibility == View.VISIBLE

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

        val description = if (isAutoMode) {
            binding.etAutoDescription.text.toString().trim()
        } else {
            binding.etManualDescription.text.toString().trim()
        }

        val price = binding.etPrice.text.toString().toDoubleOrNull()

        val finalBarcode = if (isAutoMode) {
            binding.etAutoBarcode.text.toString().trim().ifEmpty { barcode }
        } else {
            barcode
        }

        // ===== ТИП =====
        val itemType = if (isAutoMode) currentAutoType else currentManualType

        // ===== ПОДТИП (ОБЯЗАТЕЛЬНО) =====
        val itemSubtype = if (isAutoMode) selectedAutoSubtype else selectedManualSubtype

        if (itemSubtype.isNullOrEmpty()) {
            Toast.makeText(this, "Выберите подтип", Toast.LENGTH_SHORT).show()
            // Показать ошибку на поле
            if (isAutoMode) {
                binding.tilAutoSubtype.error = "Выберите подтип"
                binding.tilAutoSubtype.requestFocus()
            } else {
                binding.tilManualSubtype.error = "Выберите подтип"
                binding.tilManualSubtype.requestFocus()
            }
            return
        }

        // Сбрасываем ошибку, если всё ок
        binding.tilAutoSubtype.error = null
        binding.tilManualSubtype.error = null

        Logger.log(TAG, "Save: type=$itemType, subtype=$itemSubtype")

        val item = ItemEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            parentId = parentFolderId,
            quantity = quantity,
            barcode = finalBarcode,
            description = description,
            price = price,
            expiryDate = expiryDate,
            addedBy = "user",
            itemType = itemType,
            itemSubtype = itemSubtype,
            imageUrl = if (imageBytes != null) "${UUID.randomUUID()}.jpg" else null
        )
        item.computeExpiryFields()

        viewModel.createItem(item, imageBytes)

        Toast.makeText(this@AddItemActivity, "Предмет добавлен", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

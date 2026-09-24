package com.family.base.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import coil.load
import com.family.base.Config
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.remote.ProductLookupService
import com.family.base.data.repository.CatalogRepository
import com.family.base.databinding.ActivityItemDetailBinding
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ItemDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityItemDetailBinding
    private val TAG = "ItemDetailActivity"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private lateinit var db: AppDatabase
    private lateinit var repository: CatalogRepository
    private lateinit var viewModel: MainViewModel
    private val CAMERA_PERMISSION_REQUEST = 200

    private val productLookupService = ProductLookupService()

    private var newImageBytes: ByteArray? = null
    private var photoUri: Uri? = null
    private var itemId: String? = null

    private var isEditMode = false

    // ===== ВЫБОР ИЗ ГАЛЕРЕИ =====
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bitmap = ImageUtils.loadBitmapWithExif(this, it) ?: return@let
                val processedBytes = ImageUtils.processImage(bitmap)
                newImageBytes = processedBytes
                binding.ivPhoto.setImageBitmap(bitmap)
                binding.ivPhoto.visibility = View.VISIBLE
                binding.ivPhotoPlaceholder.visibility = View.GONE
                binding.btnAddPhoto.visibility = View.GONE
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
                newImageBytes = processedBytes
                binding.ivPhoto.setImageBitmap(bitmap)
                binding.ivPhoto.visibility = View.VISIBLE
                binding.ivPhotoPlaceholder.visibility = View.GONE
                binding.btnAddPhoto.visibility = View.GONE
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
                Logger.log(TAG, "Scanned barcode: $scannedValue")
                binding.etBarcode.setText(scannedValue)
                if (scannedValue.all { it.isDigit() }) {
                    showLookupDialog(scannedValue)
                } else {
                    Toast.makeText(this, "Код: $scannedValue", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== ItemDetailActivity onCreate START ===")

        try {
            binding = ActivityItemDetailBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        try {
            db = AppDatabase.getInstance(this)
            repository = CatalogRepository(db)
            viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to initialize", e)
            return
        }

        itemId = intent.getStringExtra("item_id")
        val editMode = intent.getBooleanExtra("edit_mode", false)
        val showHistory = intent.getBooleanExtra("show_history", false)

        if (itemId == null) {
            Toast.makeText(this, "Ошибка: предмет не найден", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupListeners()

        when {
            showHistory -> {
                isEditMode = false
                showHistory(itemId!!)
            }
            editMode -> {
                isEditMode = true
                showEditMode(itemId!!)
            }
            else -> {
                isEditMode = false
                showDetails(itemId!!)
            }
        }

        Logger.log(TAG, "=== ItemDetailActivity onCreate FINISHED ===")
    }

    private fun setupListeners() {
        // Назад
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Клик по фото — полноэкранный просмотр
        binding.ivPhoto.setOnClickListener { openFullscreenPhoto() }

        // Сохранить / Отмена
        binding.btnSave.setOnClickListener { saveChanges() }
        binding.btnCancelEdit.setOnClickListener { finish() }

        // +/−
        binding.btnPlus.setOnClickListener { changeQuantity(+1) }
        binding.btnMinus.setOnClickListener { changeQuantity(-1) }

        // Добавить фото
        binding.btnAddPhoto.setOnClickListener { showImageSourceDialog() }

        // Сканер штрих-кода
        binding.btnScanBarcode.setOnClickListener {
            val intent = Intent(this, BarcodeScannerActivity::class.java)
            barcodeScannerLauncher.launch(intent)
        }

        // Действия
        binding.btnActionEdit.setOnClickListener {
            itemId?.let {
                isEditMode = true
                showEditMode(it)
            }
        }
        binding.btnActionLend.setOnClickListener { showLendDialog() }
        binding.btnActionMove.setOnClickListener { showMoveDialog() }
        binding.btnActionCopy.setOnClickListener { showCopyDialog() }
        binding.btnActionArchive.setOnClickListener { showArchiveDialog() }
        binding.btnActionDelete.setOnClickListener { showDeleteDialog() }

        // Вернуть
        binding.btnReturnItem.setOnClickListener { showReturnDialog() }
    }

    private fun changeQuantity(delta: Int) {
        val currentText = binding.etQuantity.text.toString()
        val currentQty = currentText.toIntOrNull() ?: 1
        val newQty = (currentQty + delta).coerceAtLeast(0)
        binding.etQuantity.setText(newQty.toString())
    }

    // ============================================================
    // ПРОСМОТР ДЕТАЛЕЙ
    // ============================================================
    private fun showDetails(itemId: String) {
        isEditMode = false
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(itemId) }
                if (item == null) {
                    Toast.makeText(this@ItemDetailActivity, "Предмет не найден", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    binding.tvTitle.text = item.name

                    // Поля
                    binding.etName.setText(item.name)
                    binding.etName.isEnabled = false
                    binding.etQuantity.setText(item.quantity.toString())
                    binding.etQuantity.isEnabled = false
                    binding.etDescription.setText(item.description ?: "")
                    binding.etDescription.isEnabled = false
                    binding.etBarcode.setText(item.barcode ?: "")
                    binding.etBarcode.isEnabled = false

                    item.expiryDate?.let {
                        binding.etExpiry.setText(dateFormat.format(Date(it)))
                    }
                    binding.etExpiry.isEnabled = false

                    // Цена
                    if (item.price != null && item.price != 0.0) {
                        binding.tvPrice.visibility = View.VISIBLE
                        binding.tvPrice.text = "💰 Цена: ${item.price} ₽"
                        binding.etPrice.visibility = View.GONE
                        binding.tilPrice.visibility = View.GONE
                    } else {
                        binding.tvPrice.visibility = View.GONE
                        binding.etPrice.visibility = View.GONE
                        binding.tilPrice.visibility = View.GONE
                    }

                    // Кнопки +/− и сканер — скрыть в просмотре
                    binding.btnPlus.visibility = View.GONE
                    binding.btnMinus.visibility = View.GONE
                    binding.btnScanBarcode.visibility = View.GONE
                    binding.btnSave.visibility = View.GONE
                    binding.editButtonsLayout.visibility = View.GONE
                    binding.btnAddPhoto.visibility = View.GONE

                    // Описание — если пусто, скрыть секцию
                    val desc = item.description
                    if (desc.isNullOrEmpty()) {
                        binding.tvDescriptionTitle.visibility = View.GONE
                        binding.cardDescription.visibility = View.GONE
                    } else {
                        binding.tvDescriptionTitle.visibility = View.VISIBLE
                        binding.cardDescription.visibility = View.VISIBLE
                    }

                    // Фото
                    val localFile = ImageUtils.getLocalImageFile(this@ItemDetailActivity, itemId)
                    if (localFile != null && localFile.exists()) {
                        binding.ivPhoto.visibility = View.VISIBLE
                        binding.ivPhotoPlaceholder.visibility = View.GONE
                        binding.ivPhoto.load(localFile) { crossfade(true) }
                    } else {
                        binding.ivPhoto.visibility = View.GONE
                        binding.ivPhotoPlaceholder.visibility = View.VISIBLE
                    }

                    // Чипы
                    applyChips(item)

                    // Блок займа
                    if (item.isLent && !item.lentTo.isNullOrEmpty()) {
                        binding.cardLentInfo.visibility = View.VISIBLE
                        binding.tvLentPerson.text = "Кому: ${item.lentTo}"
                        item.lentDate?.let {
                            binding.tvLentDate.text = "Дата: ${dateFormat.format(Date(it))}"
                        }
                        if (!item.lentNote.isNullOrEmpty()) {
                            binding.tvLentNote.visibility = View.VISIBLE
                            binding.tvLentNote.text = "Заметка: ${item.lentNote}"
                        } else {
                            binding.tvLentNote.visibility = View.GONE
                        }
                    } else {
                        binding.cardLentInfo.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing details", e)
            }
        }
    }

    // ============================================================
    // РЕЖИМ РЕДАКТИРОВАНИЯ
    // ============================================================
    private fun showEditMode(itemId: String) {
        isEditMode = true
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(itemId) }
                if (item == null) {
                    finish()
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    binding.tvTitle.text = "Редактирование: ${item.name}"

                    binding.etName.setText(item.name)
                    binding.etName.isEnabled = true
                    binding.etQuantity.setText(item.quantity.toString())
                    binding.etQuantity.isEnabled = true
                    binding.etDescription.setText(item.description ?: "")
                    binding.etDescription.isEnabled = true
                    binding.etBarcode.setText(item.barcode ?: "")
                    binding.etBarcode.isEnabled = true

                    item.expiryDate?.let {
                        binding.etExpiry.setText(dateFormat.format(Date(it)))
                    }
                    binding.etExpiry.isEnabled = true
                    binding.etExpiry.setOnClickListener { showDatePickerDialog() }

                    binding.tvPrice.visibility = View.GONE
                    binding.etPrice.visibility = View.VISIBLE
                    binding.tilPrice.visibility = View.VISIBLE
                    binding.etPrice.setText(if (item.price != null && item.price != 0.0) item.price.toString() else "")

                    binding.btnPlus.visibility = View.VISIBLE
                    binding.btnMinus.visibility = View.VISIBLE
                    binding.btnScanBarcode.visibility = View.VISIBLE
                    binding.editButtonsLayout.visibility = View.VISIBLE
                    binding.btnSave.visibility = View.VISIBLE

                    // Описание — всегда видно в режиме редактирования
                    binding.tvDescriptionTitle.visibility = View.VISIBLE
                    binding.cardDescription.visibility = View.VISIBLE

                    // Фото
                    val localFile = ImageUtils.getLocalImageFile(this@ItemDetailActivity, itemId)
                    if (localFile != null && localFile.exists()) {
                        binding.ivPhoto.visibility = View.VISIBLE
                        binding.ivPhotoPlaceholder.visibility = View.GONE
                        binding.ivPhoto.load(localFile) { crossfade(true) }
                        binding.btnAddPhoto.visibility = View.GONE
                    } else {
                        binding.ivPhoto.visibility = View.GONE
                        binding.ivPhotoPlaceholder.visibility = View.VISIBLE
                        binding.btnAddPhoto.visibility = View.VISIBLE
                    }

                    binding.cardLentInfo.visibility = View.GONE

                    applyChips(item)
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing edit mode", e)
            }
        }
    }

    // ============================================================
    // ЧИПЫ-СТАТУСЫ
    // ============================================================
    private fun applyChips(item: ItemEntity) {
        // Тип
        val typeText = when (item.itemType) {
            "food" -> "🍎 Еда"
            "medicine" -> "💊 Лекарство"
            else -> "📦 Другое"
        }
        binding.chipType.text = typeText
        binding.chipType.visibility = View.VISIBLE

        // Просрочен
        if (item.isExpired) {
            binding.chipExpired.visibility = View.VISIBLE
        } else {
            binding.chipExpired.visibility = View.GONE
        }

        // Скоро истекает
        if (!item.isExpired && item.daysUntilExpiry in 0..3) {
            binding.chipSoon.visibility = View.VISIBLE
            binding.chipSoon.text = "⚠️ Осталось ${item.daysUntilExpiry} дн."
        } else {
            binding.chipSoon.visibility = View.GONE
        }

        // Выдан
        if (item.isLent && !item.lentTo.isNullOrEmpty()) {
            binding.chipLent.visibility = View.VISIBLE
        } else {
            binding.chipLent.visibility = View.GONE
        }
    }

    // ============================================================
    // ИСТОРИЯ
    // ============================================================
    private fun showHistory(itemId: String) {
        isEditMode = false
        lifecycleScope.launch {
            try {
                val history = withContext(Dispatchers.IO) { db.historyDao().getHistoryForItem(itemId) }
                withContext(Dispatchers.Main) {
                    binding.tvTitle.text = "История изменений"

                    binding.etName.visibility = View.GONE
                    binding.etQuantity.visibility = View.GONE
                    binding.etExpiry.visibility = View.GONE
                    binding.etBarcode.visibility = View.GONE
                    binding.tvPrice.visibility = View.GONE
                    binding.etPrice.visibility = View.GONE
                    binding.tilPrice.visibility = View.GONE
                    binding.btnSave.visibility = View.GONE
                    binding.editButtonsLayout.visibility = View.GONE
                    binding.btnPlus.visibility = View.GONE
                    binding.btnMinus.visibility = View.GONE
                    binding.btnAddPhoto.visibility = View.GONE
                    binding.cardLentInfo.visibility = View.GONE
                    binding.btnScanBarcode.visibility = View.GONE
                    binding.chipGroupStatus.visibility = View.GONE

                    val historyText = if (history.isEmpty()) {
                        "История пуста"
                    } else {
                        history.joinToString("\n\n") { entry ->
                            buildString {
                                append("📅 ${dateFormat.format(Date(entry.changedAt))}\n")
                                append("👤 ${entry.changedBy}\n")
                                append("📝 ${entry.action}")
                                if (entry.oldValue != null && entry.newValue != null) {
                                    append(": ${entry.oldValue} → ${entry.newValue}")
                                }
                            }
                        }
                    }

                    binding.etDescription.setText(historyText)
                    binding.etDescription.isEnabled = false
                    binding.cardDescription.visibility = View.VISIBLE
                    binding.tvDescriptionTitle.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing history", e)
            }
        }
    }

    // ============================================================
    // ПОЛНОЭКРАННЫЙ ПРОСМОТР ФОТО
    // ============================================================
    private fun openFullscreenPhoto() {
        val id = itemId ?: return
        val localFile = ImageUtils.getLocalImageFile(this, id)
        if (localFile == null || !localFile.exists()) {
            Toast.makeText(this, "Фото отсутствует", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, FullscreenImageActivity::class.java).apply {
            putExtra(FullscreenImageActivity.EXTRA_IMAGE_PATH, localFile.absolutePath)
            putExtra(FullscreenImageActivity.EXTRA_TITLE, binding.tvTitle.text.toString())
        }
        startActivity(intent)
    }

    // ============================================================
    // ФОТО
    // ============================================================
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
            Toast.makeText(this, "Ошибка открытия камеры", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) openCamera()
            else Toast.makeText(this, "Разрешение на камеру не получено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        binding.etExpiry.text.toString().let {
            if (it.isNotEmpty()) {
                try { dateFormat.parse(it)?.let { d -> calendar.time = d } } catch (e: Exception) {}
            }
        }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                binding.etExpiry.setText("${day.toString().padStart(2, '0')}.${(month + 1).toString().padStart(2, '0')}.$year")
            },
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ============================================================
    // ПОИСК ТОВАРА ПО ШТРИХ-КОДУ
    // ============================================================
    private fun showLookupDialog(barcode: String) {
        AlertDialog.Builder(this)
            .setTitle("🔍 Найти товар?")
            .setMessage("Найден штрих-код: $barcode\n\nИскать информацию о товаре в базах?")
            .setPositiveButton("Искать") { _, _ -> lookupProduct(barcode) }
            .setNegativeButton("Только код", null)
            .show()
    }

    private fun lookupProduct(barcode: String) {
        Toast.makeText(this, "Поиск товара...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val result = productLookupService.lookupProduct(barcode)

                if (result.success && result.product != null) {
                    val product = result.product
                    val displayName = when {
                        !product.name.isNullOrEmpty() -> product.name
                        !product.brand.isNullOrEmpty() -> product.brand
                        !product.category.isNullOrEmpty() -> product.category
                        else -> null
                    }

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

                    AlertDialog.Builder(this@ItemDetailActivity)
                        .setTitle("✅ Найдено: ${displayName ?: barcode}")
                        .setMessage("Что заполнить?\n\nНазвание: ${displayName ?: "—"}\n\nОписание: ${descriptionText.ifEmpty { "—" }}")
                        .setPositiveButton("Заполнить всё") { _, _ ->
                            displayName?.let { binding.etName.setText(it) }
                            if (descriptionText.isNotEmpty()) {
                                binding.etDescription.setText(descriptionText)
                            }
                        }
                        .setNeutralButton("Только название") { _, _ ->
                            displayName?.let { binding.etName.setText(it) }
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                } else {
                    Toast.makeText(this@ItemDetailActivity, "Товар не найден", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error looking up product", e)
            }
        }
    }

    // ============================================================
    // ДЕЙСТВИЯ
    // ============================================================
    private fun showLendDialog() {
        val id = itemId ?: return
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        val etPerson = android.widget.EditText(this).apply { hint = "Кому выдать (имя)" }
        val etNote = android.widget.EditText(this).apply { hint = "Заметка (необязательно)" }
        container.addView(etPerson)
        container.addView(etNote)

        AlertDialog.Builder(this)
            .setTitle("🤝 Выдать предмет")
            .setView(container)
            .setPositiveButton("Выдать") { _, _ ->
                val person = etPerson.text.toString().trim()
                val note = etNote.text.toString().trim().ifEmpty { null }
                if (person.isNotEmpty()) {
                    viewModel.lendItem(id, person, note)
                    Toast.makeText(this, "Выдан: $person", Toast.LENGTH_SHORT).show()
                    finish()
                } else Toast.makeText(this, "Введите имя", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showReturnDialog() {
        val id = itemId ?: return
        AlertDialog.Builder(this)
            .setTitle("↩️ Вернуть предмет?")
            .setMessage("Предмет вернётся в базу, займ будет отменён.")
            .setPositiveButton("Вернуть") { _, _ ->
                viewModel.returnItem(id)
                Toast.makeText(this, "Предмет возвращён", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showMoveDialog() {
        val id = itemId ?: return
        lifecycleScope.launch {
            try {
                val folders = viewModel.getAllFolders()
                val names = folders.map { it.name }.toMutableList()
                names.add(0, "Корень")
                AlertDialog.Builder(this@ItemDetailActivity)
                    .setTitle("📁 Переместить предмет")
                    .setItems(names.toTypedArray()) { _, which ->
                        val target = if (which == 0) null else folders[which - 1]
                        viewModel.moveItem(id, target?.id)
                        Toast.makeText(this@ItemDetailActivity, "Перемещено", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            } catch (e: Exception) {
                Logger.log(TAG, "Error moving item", e)
            }
        }
    }

    private fun showCopyDialog() {
        val id = itemId ?: return
        AlertDialog.Builder(this)
            .setTitle("📋 Копировать предмет?")
            .setMessage("Будет создана копия предмета в текущей папке.")
            .setPositiveButton("Копировать") { _, _ ->
                viewModel.copyItem(id)
                Toast.makeText(this, "Предмет скопирован", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showArchiveDialog() {
        val id = itemId ?: return
        val reasons = arrayOf("🍽 Съедено", "🔧 Сломано", "🗑 Выброшено", "🎁 Подарено", "💰 Продано", "⏰ Истёк срок", "📦 Другое")
        val reasonKeys = arrayOf("eaten", "broken", "thrown", "gifted", "sold", "expired", "other")

        AlertDialog.Builder(this)
            .setTitle("📦 В архив: ${binding.etName.text}")
            .setItems(reasons) { _, which ->
                viewModel.archiveItem(id, reasonKeys[which], null)
                Toast.makeText(this, "Предмет в архиве", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeleteDialog() {
        val id = itemId ?: return
        AlertDialog.Builder(this)
            .setTitle("🗑 Удалить предмет?")
            .setMessage("Это действие нельзя отменить. Возможно, лучше переместить в архив?")
            .setPositiveButton("Удалить") { _, _ ->
                viewModel.deleteItem(id)
                Toast.makeText(this, "Предмет удалён", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNeutralButton("В архив") { _, _ -> showArchiveDialog() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // СОХРАНЕНИЕ
    // ============================================================
    private fun saveChanges() {
        val id = itemId ?: return
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(id) }
                if (item == null) {
                    finish()
                    return@launch
                }

                val name = binding.etName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this@ItemDetailActivity, "Введите название", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val quantity = binding.etQuantity.text.toString().toIntOrNull() ?: 1
                val description = binding.etDescription.text.toString()
                val expiryDate = parseDate(binding.etExpiry.text.toString())
                val price = binding.etPrice.text.toString().toDoubleOrNull()
                val barcode = binding.etBarcode.text.toString().trim().ifEmpty { null }

                val updated = item.copy(
                    name = name,
                    quantity = quantity,
                    barcode = barcode,
                    description = description,
                    expiryDate = expiryDate,
                    price = price,
                    updatedDate = System.currentTimeMillis(),
                    updatedBy = "user"
                )
                updated.computeExpiryFields()

                withContext(Dispatchers.IO) {
                    db.itemDao().updateItem(updated)
                    newImageBytes?.let { bytes ->
                        ImageUtils.saveImageLocally(applicationContext, id, bytes)
                    }
                    val history = HistoryEntry(
                        itemId = id,
                        action = "update",
                        oldValue = "Изменены поля",
                        newValue = "Название: $name, Количество: $quantity",
                        changedBy = "user"
                    )
                    db.historyDao().insertEntry(history)
                }

                Toast.makeText(this@ItemDetailActivity, "Сохранено", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Logger.log(TAG, "Error saving changes", e)
                Toast.makeText(this@ItemDetailActivity, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun parseDate(dateStr: String): Long? {
        return try {
            dateFormat.parse(dateStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

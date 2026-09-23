package com.family.base.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
import com.family.base.data.repository.CatalogRepository
import com.family.base.databinding.ActivityItemDetailBinding
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
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
    private val FOLDER_PATH = "/${Config.SHARED_FOLDER_NAME}"
    private val CAMERA_PERMISSION_REQUEST = 200

    private var newImageBytes: ByteArray? = null
    private var photoUri: Uri? = null
    private var itemId: String? = null

    // Флаг: сейчас режим редактирования?
    private var isEditMode = false

    // ===== ВЫБОР ИЗ ГАЛЕРЕИ (С УЧЁТОМ EXIF) =====
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bitmap = ImageUtils.loadBitmapWithExif(this, it) ?: return@let
                val processedBytes = ImageUtils.processImage(bitmap)
                newImageBytes = processedBytes
                binding.ivPhoto.setImageBitmap(bitmap)
                binding.ivPhoto.visibility = View.VISIBLE
                binding.btnAddPhoto.visibility = View.GONE
                Logger.log(TAG, "Image selected from gallery, size=${processedBytes.size}")

                if (isEditMode) {
                    binding.btnSave.visibility = View.VISIBLE
                    binding.btnEdit.visibility = View.GONE
                    binding.btnPlus.visibility = View.VISIBLE
                    binding.btnMinus.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error picking image", e)
                Toast.makeText(this, "Ошибка выбора фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== ФОТО С КАМЕРЫ (С УЧЁТОМ EXIF) =====
    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val uri = photoUri ?: return@registerForActivityResult
            try {
                val bitmap = ImageUtils.loadBitmapWithExif(this, uri) ?: return@registerForActivityResult
                val processedBytes = ImageUtils.processImage(bitmap)
                newImageBytes = processedBytes
                binding.ivPhoto.setImageBitmap(bitmap)
                binding.ivPhoto.visibility = View.VISIBLE
                binding.btnAddPhoto.visibility = View.GONE
                Logger.log(TAG, "Photo captured, size=${processedBytes.size}")

                if (isEditMode) {
                    binding.btnSave.visibility = View.VISIBLE
                    binding.btnEdit.visibility = View.GONE
                    binding.btnPlus.visibility = View.VISIBLE
                    binding.btnMinus.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error processing camera photo", e)
                Toast.makeText(this, "Ошибка обработки фото", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Фото не сделано", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== ItemDetailActivity onCreate START ===")

        try {
            binding = ActivityItemDetailBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Binding inflated successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        try {
            db = AppDatabase.getInstance(this)
            repository = CatalogRepository(db)
            viewModel = ViewModelProvider(this)[MainViewModel::class.java]
            Logger.log(TAG, "Database, repository and ViewModel initialized")
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

        if (showHistory) {
            isEditMode = false
            showHistory(itemId!!)
        } else if (editMode) {
            isEditMode = true
            showEditMode(itemId!!)
        } else {
            isEditMode = false
            showDetails(itemId!!)
        }

        setupListeners()

        Logger.log(TAG, "=== ItemDetailActivity onCreate FINISHED ===")
    }

    private fun setupListeners() {
        // Назад
        binding.btnBack.setOnClickListener { finish() }

        // Кнопки режима редактирования
        binding.btnSave.setOnClickListener { saveChanges() }
        binding.btnEdit.setOnClickListener {
            itemId?.let {
                isEditMode = true
                showEditMode(it)
            }
        }
        binding.btnPlus.setOnClickListener { changeQuantity(+1) }
        binding.btnMinus.setOnClickListener { changeQuantity(-1) }
        binding.btnAddPhoto.setOnClickListener { showImageSourceDialog() }

        // ===== НОВЫЕ КНОПКИ ДЕЙСТВИЙ =====
        binding.btnActionEdit.setOnClickListener {
            itemId?.let {
                isEditMode = true
                showEditMode(it)
            }
        }

        binding.btnActionLend.setOnClickListener {
            showLendDialog()
        }

        binding.btnActionMove.setOnClickListener {
            showMoveDialog()
        }

        binding.btnActionCopy.setOnClickListener {
            showCopyDialog()
        }

        binding.btnActionArchive.setOnClickListener {
            showArchiveDialog()
        }

        binding.btnActionDelete.setOnClickListener {
            showDeleteDialog()
        }
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

                    if (item.price != null && item.price != 0.0) {
                        binding.tvPrice.visibility = View.VISIBLE
                        binding.tvPrice.text = "Цена: ${item.price} ₽"
                        binding.etPrice.visibility = View.GONE
                        binding.tilPrice.visibility = View.GONE
                    } else {
                        binding.tvPrice.visibility = View.GONE
                        binding.etPrice.visibility = View.GONE
                        binding.tilPrice.visibility = View.GONE
                    }

                    binding.btnPlus.visibility = View.GONE
                    binding.btnMinus.visibility = View.GONE

                    val localFile = ImageUtils.getLocalImageFile(this@ItemDetailActivity, itemId)
                    if (localFile != null && localFile.exists()) {
                        binding.ivPhoto.visibility = View.VISIBLE
                        binding.ivPhoto.load(localFile) { crossfade(true) }
                    } else {
                        binding.ivPhoto.visibility = View.GONE
                    }
                    binding.btnAddPhoto.visibility = View.GONE

                    binding.btnSave.visibility = View.GONE
                    binding.btnEdit.visibility = View.GONE
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

                    val localFile = ImageUtils.getLocalImageFile(this@ItemDetailActivity, itemId)
                    if (localFile != null && localFile.exists()) {
                        binding.ivPhoto.visibility = View.VISIBLE
                        binding.ivPhoto.load(localFile) { crossfade(true) }
                        binding.ivPhoto.setOnClickListener { showImageSourceDialog() }
                        binding.btnAddPhoto.visibility = View.GONE
                    } else {
                        binding.ivPhoto.visibility = View.GONE
                        binding.btnAddPhoto.visibility = View.VISIBLE
                    }

                    binding.btnSave.visibility = View.VISIBLE
                    binding.btnEdit.visibility = View.GONE
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing edit mode", e)
            }
        }
    }

    // ============================================================
    // ИСТОРИЯ
    // ============================================================
    private fun showHistory(itemId: String) {
        isEditMode = false
        lifecycleScope.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    db.historyDao().getHistoryForItem(itemId)
                }

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
                    binding.btnEdit.visibility = View.GONE
                    binding.btnPlus.visibility = View.GONE
                    binding.btnMinus.visibility = View.GONE
                    binding.btnAddPhoto.visibility = View.GONE

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
                    binding.etDescription.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing history", e)
            }
        }
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
                    0 -> {
                        if (checkCameraPermission()) openCamera()
                        else requestCameraPermission()
                    }
                    1 -> pickImageLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }

    private fun openCamera() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val photoFile = File(cacheDir, "IMG_$timeStamp.jpg")
            photoUri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", photoFile
            )
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
                Toast.makeText(this, "Необходимо разрешение на камеру", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        val currentText = binding.etExpiry.text.toString()
        if (currentText.isNotEmpty()) {
            try {
                dateFormat.parse(currentText)?.let { calendar.time = it }
            } catch (e: Exception) { }
        }

        DatePickerDialog(
            this,
            { _, year, month, day ->
                binding.etExpiry.setText("${day.toString().padStart(2, '0')}.${(month + 1).toString().padStart(2, '0')}.$year")
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ============================================================
    // ДЕЙСТВИЯ С ПРЕДМЕТОМ
    // ============================================================

    // ===== ВЫДАТЬ =====
    private fun showLendDialog() {
        val id = itemId ?: return

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        val etPerson = android.widget.EditText(this).apply {
            hint = "Кому выдать (имя)"
        }
        val etNote = android.widget.EditText(this).apply {
            hint = "Заметка (необязательно)"
        }

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
                } else {
                    Toast.makeText(this, "Введите имя", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ===== ПЕРЕНЕСТИ =====
    private fun showMoveDialog() {
        val id = itemId ?: return
        lifecycleScope.launch {
            try {
                val allFolders = viewModel.getAllFolders()
                val names = allFolders.map { it.name }.toMutableList()
                names.add(0, "Корень")

                AlertDialog.Builder(this@ItemDetailActivity)
                    .setTitle("📁 Переместить предмет")
                    .setItems(names.toTypedArray()) { _, which ->
                        val target = if (which == 0) null else allFolders[which - 1]
                        viewModel.moveItem(id, target?.id)
                        Toast.makeText(this@ItemDetailActivity, "Перемещено: ${target?.name ?: "Корень"}", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing move dialog", e)
            }
        }
    }

    // ===== КОПИРОВАТЬ =====
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

    // ===== АРХИВИРОВАТЬ =====
    private fun showArchiveDialog() {
        val id = itemId ?: return
        val reasons = arrayOf(
            "🍽 Съедено", "🔧 Сломано", "🗑 Выброшено",
            "🎁 Подарено", "💰 Продано", "⏰ Истёк срок", "📦 Другое"
        )
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

    // ===== УДАЛИТЬ =====
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
                    Toast.makeText(this@ItemDetailActivity, "Предмет не найден", Toast.LENGTH_SHORT).show()
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

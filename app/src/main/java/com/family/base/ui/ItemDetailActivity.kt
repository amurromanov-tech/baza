package com.family.base.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.family.base.Config
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.repository.CatalogRepository
import com.family.base.databinding.ActivityItemDetailBinding
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ItemDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityItemDetailBinding
    private val TAG = "ItemDetailActivity"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private lateinit var db: AppDatabase
    private lateinit var repository: CatalogRepository
    private val FOLDER_PATH = "/${Config.SHARED_FOLDER_NAME}"

    private var newImageBytes: ByteArray? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, it)
                val processedBytes = ImageUtils.processImage(bitmap)
                newImageBytes = processedBytes
                binding.ivPhoto.setImageBitmap(bitmap)
                binding.ivPhoto.visibility = View.VISIBLE
                binding.btnAddPhoto.visibility = View.GONE
                Logger.log(TAG, "New image selected, size=${processedBytes.size}")
            } catch (e: Exception) {
                Logger.log(TAG, "Error picking image", e)
                Toast.makeText(this, "Ошибка выбора фото", Toast.LENGTH_SHORT).show()
            }
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
            Logger.log(TAG, "Database and repository initialized")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to initialize database", e)
            return
        }

        val itemId = intent.getStringExtra("item_id")
        val editMode = intent.getBooleanExtra("edit_mode", false)
        val showHistory = intent.getBooleanExtra("show_history", false)

        Logger.log(TAG, "itemId: $itemId, editMode: $editMode, showHistory: $showHistory")

        if (itemId == null) {
            Logger.log(TAG, "No item_id provided")
            Toast.makeText(this, "Ошибка: предмет не найден", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (showHistory) {
            showHistory(itemId)
        } else if (editMode) {
            showEditMode(itemId)
        } else {
            showDetails(itemId)
        }

        setupListeners()

        Logger.log(TAG, "=== ItemDetailActivity onCreate FINISHED ===")
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            Logger.log(TAG, "Back button clicked")
            finish()
        }

        binding.btnSave.setOnClickListener {
            Logger.log(TAG, "Save button clicked")
            saveChanges()
        }

        binding.btnPlus.setOnClickListener {
            changeQuantity(+1)
        }

        binding.btnMinus.setOnClickListener {
            changeQuantity(-1)
        }

        // ===== КНОПКА ДОБАВЛЕНИЯ ФОТО =====
        binding.btnAddPhoto.setOnClickListener {
            Logger.log(TAG, "Add photo button clicked")
            selectNewPhoto()
        }
    }

    private fun changeQuantity(delta: Int) {
        val currentText = binding.etQuantity.text.toString()
        val currentQty = currentText.toIntOrNull() ?: 1
        val newQty = (currentQty + delta).coerceAtLeast(0)
        binding.etQuantity.setText(newQty.toString())
        Logger.log(TAG, "Quantity changed: $currentQty -> $newQty (delta: $delta)")
    }

    private fun showDetails(itemId: String) {
        Logger.log(TAG, "Showing details for item: $itemId")
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) {
                    db.itemDao().getItemById(itemId)
                }

                if (item == null) {
                    Logger.log(TAG, "Item not found: $itemId")
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
                        val dateStr = dateFormat.format(Date(it))
                        binding.etExpiry.setText(dateStr)
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

                    // ===== ФОТО В ПРОСМОТРЕ =====
                    val localFile = ImageUtils.getLocalImageFile(this@ItemDetailActivity, itemId)
                    if (localFile != null && localFile.exists()) {
                        binding.ivPhoto.visibility = View.VISIBLE
                        binding.ivPhoto.load(localFile) {
                            crossfade(true)
                        }
                    } else {
                        binding.ivPhoto.visibility = View.GONE
                    }
                    binding.btnAddPhoto.visibility = View.GONE

                    binding.btnSave.visibility = View.GONE
                    binding.btnEdit.visibility = View.VISIBLE
                    binding.btnEdit.setOnClickListener {
                        Logger.log(TAG, "Edit button clicked")
                        showEditMode(itemId)
                    }
                }

                Logger.log(TAG, "Details shown for item: ${item.name}")
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing details", e)
            }
        }
    }

    private fun showEditMode(itemId: String) {
        Logger.log(TAG, "Showing edit mode for item: $itemId")
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) {
                    db.itemDao().getItemById(itemId)
                }

                if (item == null) {
                    Logger.log(TAG, "Item not found: $itemId")
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
                        val dateStr = dateFormat.format(Date(it))
                        binding.etExpiry.setText(dateStr)
                    }
                    binding.etExpiry.isEnabled = true
                    binding.etExpiry.setOnClickListener {
                        showDatePickerDialog()
                    }

                    binding.tvPrice.visibility = View.GONE
                    binding.etPrice.visibility = View.VISIBLE
                    binding.tilPrice.visibility = View.VISIBLE
                    binding.etPrice.setText(if (item.price != null && item.price != 0.0) item.price.toString() else "")

                    binding.btnPlus.visibility = View.VISIBLE
                    binding.btnMinus.visibility = View.VISIBLE

                    // ===== ЛОГИКА ДЛЯ ФОТО В РЕДАКТИРОВАНИИ =====
                    val localFile = ImageUtils.getLocalImageFile(this@ItemDetailActivity, itemId)
                    if (localFile != null && localFile.exists()) {
                        // Фото есть — показываем
                        binding.ivPhoto.visibility = View.VISIBLE
                        binding.ivPhoto.load(localFile) {
                            crossfade(true)
                        }
                        binding.ivPhoto.setOnClickListener {
                            Logger.log(TAG, "Photo clicked, opening picker")
                            selectNewPhoto()
                        }
                        binding.btnAddPhoto.visibility = View.GONE
                    } else {
                        // Фото нет — показываем кнопку "Добавить фото"
                        binding.ivPhoto.visibility = View.GONE
                        binding.btnAddPhoto.visibility = View.VISIBLE
                        Logger.log(TAG, "No photo, showing Add Photo button")
                    }

                    binding.btnSave.visibility = View.VISIBLE
                    binding.btnEdit.visibility = View.GONE
                }

                Logger.log(TAG, "Edit mode shown for item: ${item.name}")
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing edit mode", e)
            }
        }
    }

    private fun showHistory(itemId: String) {
        Logger.log(TAG, "Showing history for item: $itemId")
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

                Logger.log(TAG, "History shown, ${history.size} entries")
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing history", e)
            }
        }
    }

    private fun selectNewPhoto() {
        Logger.log(TAG, "selectNewPhoto called")
        pickImageLauncher.launch("image/*")
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        val currentText = binding.etExpiry.text.toString()
        if (currentText.isNotEmpty()) {
            try {
                val date = dateFormat.parse(currentText)
                date?.let { calendar.time = it }
            } catch (e: Exception) { /* ignore */ }
        }

        DatePickerDialog(
            this,
            { _, selectedYear, selectedMonth, selectedDay ->
                val dateStr = "${selectedDay.toString().padStart(2, '0')}.${(selectedMonth + 1).toString().padStart(2, '0')}.$selectedYear"
                binding.etExpiry.setText(dateStr)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun saveChanges() {
        Logger.log(TAG, "saveChanges called")
        val itemId = intent.getStringExtra("item_id") ?: return

        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) {
                    db.itemDao().getItemById(itemId)
                }

                if (item == null) {
                    Logger.log(TAG, "Item not found: $itemId")
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

                val updatedItem = item.copy(
                    name = name,
                    quantity = quantity,
                    barcode = barcode,
                    description = description,
                    expiryDate = expiryDate,
                    price = price,
                    updatedDate = System.currentTimeMillis(),
                    updatedBy = "user"
                )
                updatedItem.computeExpiryFields()

                withContext(Dispatchers.IO) {
                    db.itemDao().updateItem(updatedItem)

                    newImageBytes?.let { bytes ->
                        Logger.log(TAG, "Uploading new image, size=${bytes.size}")
                        repository.uploadItemImage(itemId, bytes)
                        ImageUtils.saveImageLocally(applicationContext, itemId, bytes)
                        newImageBytes = null
                    }

                    val history = HistoryEntry(
                        itemId = itemId,
                        action = "update",
                        oldValue = "Изменены поля",
                        newValue = "Название: $name, Количество: $quantity, Цена: $price",
                        changedBy = "user"
                    )
                    db.historyDao().insertEntry(history)
                }

                Logger.log(TAG, "Item updated successfully: $itemId")
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

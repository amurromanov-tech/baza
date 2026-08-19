package com.family.base.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.repository.CatalogRepository
import com.family.base.databinding.ActivityAddItemBinding
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class AddItemActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddItemBinding
    private lateinit var db: AppDatabase
    private lateinit var repository: CatalogRepository
    private val TAG = "AddItemActivity"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    private var parentFolderId: String? = null
    private var imageBytes: ByteArray? = null
    private var expiryDate: Long? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, it)
                val processedBytes = ImageUtils.processImage(bitmap)
                imageBytes = processedBytes
                binding.ivManualPhoto.setImageBitmap(bitmap)
                binding.ivManualPhoto.visibility = View.VISIBLE
                Logger.log(TAG, "Image selected, size=${processedBytes.size}")
            } catch (e: Exception) {
                Logger.log(TAG, "Error picking image", e)
                Toast.makeText(this, "Ошибка выбора фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== AddItemActivity onCreate START ===")

        try {
            binding = ActivityAddItemBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Binding inflated")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            finish()
            return
        }

        try {
            db = AppDatabase.getInstance(this)
            repository = CatalogRepository(db)
            Logger.log(TAG, "Database and repository initialized")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to initialize database", e)
            finish()
            return
        }

        parentFolderId = intent.getStringExtra("parent_id")
        Logger.log(TAG, "parentFolderId: $parentFolderId")

        setupListeners()
        Logger.log(TAG, "=== AddItemActivity onCreate FINISHED ===")
    }

    private fun setupListeners() {
        // Кнопки режимов
        binding.btnManualMode.setOnClickListener {
            binding.modeSelection.visibility = View.GONE
            binding.manualModeLayout.visibility = View.VISIBLE
            binding.autoModeLayout.visibility = View.GONE
            Logger.log(TAG, "Manual mode selected")
        }

        binding.btnAutoMode.setOnClickListener {
            binding.modeSelection.visibility = View.GONE
            binding.autoModeLayout.visibility = View.VISIBLE
            binding.manualModeLayout.visibility = View.GONE
            Logger.log(TAG, "Auto mode selected")
        }

        // Отмена
        binding.btnCancel.setOnClickListener {
            Logger.log(TAG, "Cancel clicked")
            finish()
        }

        // Сохранение (используем ручной режим)
        binding.btnSave.setOnClickListener {
            Logger.log(TAG, "Save clicked")
            saveItem()
        }

        // Дата для ручного режима
        binding.etManualExpiry.setOnClickListener {
            showDatePickerDialog(binding.etManualExpiry)
        }

        // Дата для авто режима
        binding.etAutoExpiry.setOnClickListener {
            showDatePickerDialog(binding.etAutoExpiry)
        }

        // Фото
        binding.btnTakePhoto.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Сканер штрих-кода
        binding.btnScanBarcode.setOnClickListener {
            Logger.log(TAG, "Scan barcode clicked")
            Toast.makeText(this, "Сканер штрих-кода будет доступен позже", Toast.LENGTH_SHORT).show()
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

    private fun saveItem() {
        Logger.log(TAG, "saveItem() called")

        // Используем поля из ручного режима
        val name = binding.etManualName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
            return
        }

        val quantity = binding.etManualQuantity.text.toString().toIntOrNull() ?: 1
        val description = binding.etManualDescription.text.toString().trim()
        val price = binding.etPrice.text.toString().toDoubleOrNull()

        // Тип
        val itemType = when (binding.rgType.checkedRadioButtonId) {
            R.id.rbFood -> "food"
            R.id.rbMedicine -> "medicine"
            else -> "other"
        }

        // Создаём предмет с правильными полями
        val item = ItemEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            parentId = parentFolderId,
            quantity = quantity,
            description = description,
            price = price,
            expiryDate = expiryDate,
            addedBy = "user",
            itemType = itemType,
            imageUrl = if (imageBytes != null) "${UUID.randomUUID()}.jpg" else null
        )
        item.computeExpiryFields()

        Logger.log(TAG, "Saving item: name=$name, quantity=$quantity, price=$price, type=$itemType")

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.itemDao().insertItem(item)
                }

                imageBytes?.let { bytes ->
                    Logger.log(TAG, "Uploading image for item ${item.id}, size=${bytes.size}")
                    try {
                        withContext(Dispatchers.IO) {
                            repository.uploadItemImage(item.id, bytes)
                            ImageUtils.saveImageLocally(applicationContext, item.id, bytes)
                        }
                        Logger.log(TAG, "Image uploaded successfully")
                    } catch (e: Exception) {
                        Logger.log(TAG, "Failed to upload image", e)
                        Toast.makeText(
                            this@AddItemActivity,
                            "Фото не загружено на диск, но предмет сохранён",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddItemActivity, "Предмет добавлен", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error saving item", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@AddItemActivity,
                        "Ошибка сохранения: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

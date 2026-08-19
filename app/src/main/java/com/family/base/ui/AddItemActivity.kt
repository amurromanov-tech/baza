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
                binding.ivPhoto.setImageBitmap(bitmap)
                binding.ivPhoto.visibility = View.VISIBLE
                binding.btnRemovePhoto.visibility = View.VISIBLE
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
        binding.btnCancel.setOnClickListener {
            Logger.log(TAG, "Cancel clicked")
            finish()
        }

        binding.btnSave.setOnClickListener {
            Logger.log(TAG, "Save clicked")
            saveItem()
        }

        binding.etExpiry.setOnClickListener {
            showDatePickerDialog()
        }

        binding.ivPhoto.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnRemovePhoto.setOnClickListener {
            imageBytes = null
            binding.ivPhoto.setImageDrawable(null)
            binding.ivPhoto.visibility = View.GONE
            binding.btnRemovePhoto.visibility = View.GONE
            Logger.log(TAG, "Photo removed")
        }

        binding.btnScanBarcode.setOnClickListener {
            Logger.log(TAG, "Scan barcode clicked")
            // Можно добавить сканер штрих-кода позже
            Toast.makeText(this, "Сканер штрих-кода будет доступен позже", Toast.LENGTH_SHORT).show()
        }
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
            { _, year, month, dayOfMonth ->
                val dateStr = String.format("%02d.%02d.%d", dayOfMonth, month + 1, year)
                binding.etExpiry.setText(dateStr)
                // Сохраняем дату в миллисекундах
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

        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
            return
        }

        val quantity = binding.etQuantity.text.toString().toIntOrNull() ?: 1
        val description = binding.etDescription.text.toString().trim()
        val price = binding.etPrice.text.toString().toDoubleOrNull()

        // Создаём предмет
        val item = ItemEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            quantity = quantity,
            description = description,
            price = price,
            expiryDate = expiryDate,
            parentFolderId = parentFolderId,
            createdDate = System.currentTimeMillis(),
            updatedDate = System.currentTimeMillis(),
            updatedBy = "user"
        )
        item.computeExpiryFields()

        Logger.log(TAG, "Saving item: name=$name, quantity=$quantity, price=$price")

        lifecycleScope.launch {
            try {
                // 1. Сохраняем в БД
                withContext(Dispatchers.IO) {
                    db.itemDao().insertItem(item)
                }

                // 2. Если есть фото — загружаем на Яндекс.Диск
                imageBytes?.let { bytes ->
                    Logger.log(TAG, "Uploading image for item ${item.id}, size=${bytes.size}")
                    try {
                        withContext(Dispatchers.IO) {
                            repository.uploadItemImage(item.id, bytes)
                            // Сохраняем локально для быстрого доступа
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

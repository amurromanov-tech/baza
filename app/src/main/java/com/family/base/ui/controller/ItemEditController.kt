package com.family.base.ui.controller

import android.app.DatePickerDialog
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.model.SubtypeCatalog
import com.family.base.databinding.ActivityItemDetailBinding
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Контроллер режима редактирования карточки предмета.
 *
 * Отвечает за:
 *  - saveChanges() — валидация полей и сохранение через MainViewModel;
 *  - дропдаун подтипа (setupSubtypeEditDropdown, updateSubtypeDropdown);
 *  - datepicker-диалоги для срока годности и даты покупки;
 *  - parseDate() — парсинг даты.
 *
 * НЕ управляет отображением view/edit — этим занимается ItemViewController.
 * НЕ трогает фото — этим занимается ItemPhotoController.
 */
class ItemEditController(
    private val activity: AppCompatActivity,
    private val binding: ActivityItemDetailBinding,
    private val db: AppDatabase,
    private val viewModel: MainViewModel,
    private val itemIdProvider: () -> String?,
    private val currentItemProvider: () -> ItemEntity?,
    private val currentEditTypeProvider: () -> String,
    private val selectedEditSubtypeProvider: () -> String?,
    private val onCurrentItemChanged: (ItemEntity?) -> Unit,
    private val onFinish: () -> Unit
) {

    private val TAG = "ItemEditController"

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    // ============================================================
    // ПОДТИП
    // ============================================================

    fun setupSubtypeEditDropdown(
        onSubtypeSelected: (String) -> Unit
    ) {
        val view = binding.autoCompleteSubtypeEdit
        view.setOnItemClickListener { _, _, position, _ ->
            val subtypes = SubtypeCatalog.getSubtypes(currentEditTypeProvider())
            if (position in subtypes.indices) {
                val key = subtypes[position].key
                Logger.log(TAG, "Subtype chosen: $key")
                onSubtypeSelected(key)
            }
        }
    }

    fun updateSubtypeDropdown(type: String) {
        val subtypes = SubtypeCatalog.getSubtypes(type)
        val adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_dropdown_item_1line,
            subtypes.map { it.displayName }
        )
        binding.autoCompleteSubtypeEdit.setAdapter(adapter)
        binding.autoCompleteSubtypeEdit.setText("", false)
    }

    // ============================================================
    // DATEPICKER-ДИАЛОГИ
    // ============================================================

    fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        binding.etExpiry.text.toString().let {
            if (it.isNotEmpty()) {
                try {
                    dateFormat.parse(it)?.let { d -> calendar.time = d }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        DatePickerDialog(
            activity,
            { _, year, month, day ->
                binding.etExpiry.setText(
                    "${day.toString().padStart(2, '0')}." +
                        "${(month + 1).toString().padStart(2, '0')}.$year"
                )
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    fun showPurchaseDatePickerDialog() {
        val calendar = Calendar.getInstance()
        val current = currentItemProvider()?.purchaseDate
        if (current != null) {
            calendar.timeInMillis = current
        } else {
            binding.etPurchaseDate.text.toString().let {
                if (it.isNotEmpty()) {
                    try {
                        dateFormat.parse(it)?.let { d -> calendar.time = d }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }

        DatePickerDialog(
            activity,
            { _, year, month, day ->
                val dateStr = "${day.toString().padStart(2, '0')}." +
                    "${(month + 1).toString().padStart(2, '0')}.$year"
                binding.etPurchaseDate.setText(dateStr)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ============================================================
    // СОХРАНЕНИЕ
    // ============================================================

    fun saveChanges() {
        val id = itemIdProvider() ?: return
        activity.lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(id) }
                if (item == null) {
                    onFinish()
                    return@launch
                }

                val name = binding.etName.text.toString().trim()
                if (name.isEmpty()) {
                    android.widget.Toast.makeText(
                        activity,
                        "Введите название",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val quantity = binding.etQuantity.text.toString().toIntOrNull() ?: 1
                val description = binding.etDescription.text.toString()
                val expiryDate = parseDate(binding.etExpiry.text.toString())
                val price = binding.etPrice.text.toString().toDoubleOrNull()
                val barcode = binding.etBarcode.text.toString().trim().ifEmpty { null }

                val purchaseDateText = binding.etPurchaseDate.text.toString().trim()
                val purchaseDate = if (purchaseDateText.isEmpty()) null else parseDate(purchaseDateText)

                val newItemType = when (binding.rgTypeEdit.checkedRadioButtonId) {
                    R.id.rbTypeFood -> "food"
                    R.id.rbTypeMedicine -> "medicine"
                    R.id.rbTypeThing -> "thing"
                    else -> "other"
                }

                val newItemSubtype = selectedEditSubtypeProvider()
                if (newItemSubtype.isNullOrEmpty()) {
                    android.widget.Toast.makeText(
                        activity,
                        "Выберите подтип",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    binding.tilSubtypeEdit.error = "Выберите подтип"
                    binding.tilSubtypeEdit.requestFocus()
                    return@launch
                }
                binding.tilSubtypeEdit.error = null

                Logger.log(
                    TAG,
                    "Save: type=$newItemType, subtype=$newItemSubtype, purchaseDate=$purchaseDate"
                )

                // newImageBytes — если было выбрано новое фото, imageUrl сбрасывается,
                // и ниже фото сохраняется локально. Иначе оставляем прежний imageUrl.
                // Само поле newImageBytes контроллеру неизвестно — фото сохраняет
                // ItemPhotoController.savePendingPhoto(id), который вызывается до этого.
                val newImageUrl = item.imageUrl

                val updated = item.copy(
                    name = name,
                    quantity = quantity,
                    barcode = barcode,
                    description = description,
                    expiryDate = expiryDate,
                    price = price,
                    purchaseDate = purchaseDate,
                    itemType = newItemType,
                    itemSubtype = newItemSubtype,
                    imageUrl = newImageUrl,
                    updatedDate = System.currentTimeMillis(),
                    updatedBy = "user"
                )
                updated.computeExpiryFields()

                viewModel.updateItemFull(updated)
                onCurrentItemChanged(updated)

                android.widget.Toast.makeText(
                    activity,
                    "Сохранено",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                onFinish()
            } catch (e: Exception) {
                Logger.log(TAG, "Error saving changes", e)
                android.widget.Toast.makeText(
                    activity,
                    "Ошибка сохранения",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
    // УТИЛИТЫ
    // ============================================================

    fun parseDate(dateStr: String): Long? = try {
        dateFormat.parse(dateStr)?.time
    } catch (e: Exception) {
        null
    }
}

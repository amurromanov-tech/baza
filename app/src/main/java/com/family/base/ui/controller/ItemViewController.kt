package com.family.base.ui.controller

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.model.SubtypeCatalog
import com.family.base.databinding.ActivityItemDetailBinding
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Контроллер отображения карточки предмета.
 *
 * Отвечает за:
 *  - переход между view/edit режимами (showViewMode);
 *  - заполнение полей view/edit (fillViewFields, fillEditFields);
 *  - показ истории (showHistory);
 *  - построение пути (buildItemPath);
 *  - чипы (applyChips) + статусные пульсации;
 *  - анимации (contentAppearance, quantity, price, totalPrice);
 *  - прогресс-бар срока годности (updateExpiryProgress);
 *  - индикатор синхронизации (updateSyncIndicator, showSyncStatusToast);
 *  - даты (revision, purchaseDate);
 *  - свёрнутые секции (expandAllSections).
 *
 * НЕ управляет saveChanges — этим занимается ItemEditController.
 * НЕ управляет фото — этим занимается ItemPhotoController.
 * НЕ управляет вложенными — этим занимается ItemNestedController.
 */
class ItemViewController(
    private val activity: AppCompatActivity,
    private val binding: ActivityItemDetailBinding,
    private val db: AppDatabase,
    private val itemIdProvider: () -> String?,
    private val currentItemProvider: () -> ItemEntity?,
    private val onCurrentItemChanged: (ItemEntity?) -> Unit,
    private val onEditModeChanged: (Boolean) -> Unit,
    private val onShowDetails: (String) -> Unit,
    private val onShowEditMode: (String) -> Unit
) {

    private val TAG = "ItemViewController"

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    private val REVISION_EXPIRED_DAYS = 365L

    private val APPEAR_DURATION = 250L
    private val APPEAR_STAGGER = 50L

    private var quantityAnimator: ValueAnimator? = null
    private var priceAnimator: ValueAnimator? = null
    private var totalPriceAnimator: ValueAnimator? = null

    private var expiredPulse: ObjectAnimator? = null
    private var soonPulse: ObjectAnimator? = null

    private val moneyFormat: DecimalFormat by lazy {
        val symbols = DecimalFormatSymbols(Locale.getDefault())
        symbols.groupingSeparator = ' '
        DecimalFormat("#,##0.##", symbols)
    }

    // ============================================================
    // ПЕРЕКЛЮЧЕНИЕ VIEW / EDIT
    // ============================================================

    fun showViewMode(isView: Boolean) {
        val viewVisibility = if (isView) View.VISIBLE else View.GONE
        val editVisibility = if (isView) View.GONE else View.VISIBLE

        binding.tvNameView.visibility = viewVisibility
        binding.tilName.visibility = editVisibility

        binding.quantityRow.visibility = viewVisibility
        binding.quantityEditBlock.visibility = editVisibility

        binding.typeEditBlock.visibility = editVisibility
        binding.subtypeEditBlock.visibility = editVisibility

        binding.chipBarcodeView.visibility = viewVisibility
        binding.barcodeEditBlock.visibility = editVisibility

        binding.chipExpiryView.visibility = viewVisibility
        binding.tilExpiry.visibility = editVisibility

        binding.priceRow.visibility = viewVisibility
        binding.tilPrice.visibility = editVisibility

        binding.tvDescriptionView.visibility = viewVisibility
        binding.tilDescription.visibility = editVisibility

        binding.tvDatePurchase.visibility = viewVisibility
        binding.tilPurchaseDate.visibility = editVisibility

        binding.editButtonsLayout.visibility = editVisibility

        binding.btnAddNested.visibility = editVisibility
    }

    fun expandAllSections() {
        applySectionState(binding.contentBasicExpandable, binding.arrowBasic, true)
        applySectionState(binding.contentDetailsExpandable, binding.arrowDetails, true)
        applySectionState(binding.contentDatesExpandable, binding.arrowDates, true)
        applySectionState(binding.contentDescriptionExpandable, binding.arrowDescription, true)
        applySectionState(binding.contentNestedExpandable, binding.arrowNested, true)
    }

    fun applySectionState(
        content: View,
        arrow: android.widget.TextView,
        expanded: Boolean
    ) {
        if (expanded) {
            content.visibility = View.VISIBLE
            arrow.text = "▼"
        } else {
            content.visibility = View.GONE
            arrow.text = "▶"
        }
    }

    // ============================================================
    // ЗАПОЛНЕНИЕ ПОЛЕЙ — VIEW
    // ============================================================

    fun fillViewFields(item: ItemEntity, path: String) {
        binding.tvNameView.text = "📝 ${item.name}"
        binding.tvNameView.visibility = View.VISIBLE

        binding.quantityRow.visibility = View.VISIBLE
        binding.chipQuantityView.text = "📦 Количество: ×${item.quantity}"

        val subtypeDisplay = SubtypeCatalog.getDisplayName(item.itemType, item.itemSubtype)
        if (subtypeDisplay != null) {
            binding.chipSubtypeView.text = subtypeDisplay
            binding.chipSubtypeView.visibility = View.VISIBLE
        } else {
            binding.chipSubtypeView.visibility = View.GONE
        }

        if (!item.barcode.isNullOrEmpty()) {
            binding.chipBarcodeView.text = "🔢 ${item.barcode}"
            binding.chipBarcodeView.visibility = View.VISIBLE
        } else {
            binding.chipBarcodeView.visibility = View.GONE
        }

        if (item.expiryDate != null) {
            binding.chipExpiryView.text = "⏰ до ${dateFormat.format(Date(item.expiryDate!!))}"
            binding.chipExpiryView.visibility = View.VISIBLE
        } else {
            binding.chipExpiryView.visibility = View.GONE
        }

        if (item.price != null && item.price != 0.0) {
            updatePriceRow(item.price, item.quantity)
        } else {
            binding.tvPrice.visibility = View.GONE
            binding.tvTotalPrice.visibility = View.GONE
        }

        if (!item.description.isNullOrEmpty()) {
            binding.tvDescriptionView.text = item.description
            binding.tvDescriptionTitle.visibility = View.VISIBLE
            binding.cardDescription.visibility = View.VISIBLE
        } else {
            binding.tvDescriptionTitle.visibility = View.GONE
            binding.cardDescription.visibility = View.GONE
        }

        binding.tvItemPath.text = path
        binding.btnGoToFolder.visibility = if (item.parentId != null) View.VISIBLE else View.GONE

        binding.tvDateAdded.text = dateTimeFormat.format(Date(item.addedDate))
        binding.tvDateModified.text = dateTimeFormat.format(Date(item.updatedDate))

        updatePurchaseDateRow(item.purchaseDate)

        if (item.expiryDate != null) {
            binding.tvDateExpiryLabel.visibility = View.VISIBLE
            binding.tvDateExpiry.visibility = View.VISIBLE
            binding.tvDateExpiry.text = dateFormat.format(Date(item.expiryDate!!))
        } else {
            binding.tvDateExpiryLabel.visibility = View.GONE
            binding.tvDateExpiry.visibility = View.GONE
        }

        binding.tvDateRevisionLabel.visibility = View.VISIBLE
        binding.tvDateRevision.visibility = View.VISIBLE
        updateRevisionRow(item.lastRevisionDate)
    }

    // ============================================================
    // ЗАПОЛНЕНИЕ ПОЛЕЙ — EDIT
    // ============================================================

    fun fillEditFields(
        item: ItemEntity,
        path: String,
        onTypeChecked: (String) -> Unit,
        onSubtypeDropdownUpdate: (String) -> Unit,
        onExpiryDateClick: () -> Unit
    ) {
        binding.etName.setText(item.name)
        binding.etName.isEnabled = true

        binding.etQuantity.setText(item.quantity.toString())
        binding.etQuantity.isEnabled = true

        val type = item.itemType ?: "thing"
        when (type) {
            "food" -> binding.rgTypeEdit.check(R.id.rbTypeFood)
            "medicine" -> binding.rgTypeEdit.check(R.id.rbTypeMedicine)
            "thing" -> binding.rgTypeEdit.check(R.id.rbTypeThing)
            else -> binding.rgTypeEdit.check(R.id.rbTypeOther)
        }
        onTypeChecked(type)

        onSubtypeDropdownUpdate(type)
        if (!item.itemSubtype.isNullOrEmpty()) {
            val subtypeDisplay = SubtypeCatalog.getDisplayName(type, item.itemSubtype)
            if (subtypeDisplay != null) {
                binding.autoCompleteSubtypeEdit.setText(subtypeDisplay, false)
            }
        }

        binding.etBarcode.setText(item.barcode ?: "")
        binding.etBarcode.isEnabled = true

        item.expiryDate?.let { binding.etExpiry.setText(dateFormat.format(Date(it))) }
        binding.etExpiry.isEnabled = true
        binding.etExpiry.setOnClickListener { onExpiryDateClick() }

        item.purchaseDate?.let {
            binding.etPurchaseDate.setText(dateFormat.format(Date(it)))
        } ?: binding.etPurchaseDate.setText("")

        binding.etPrice.setText(
            if (item.price != null && item.price != 0.0) item.price.toString() else ""
        )

        binding.etDescription.setText(item.description ?: "")
        binding.etDescription.isEnabled = true
        binding.tvDescriptionTitle.visibility = View.VISIBLE
        binding.cardDescription.visibility = View.VISIBLE

        binding.tvItemPath.text = path
        binding.btnGoToFolder.visibility = View.GONE

        binding.tvDateAdded.text = dateTimeFormat.format(Date(item.addedDate))
        binding.tvDateModified.text = dateTimeFormat.format(Date(item.updatedDate))

        updatePurchaseDateRow(item.purchaseDate)

        if (item.expiryDate != null) {
            binding.tvDateExpiryLabel.visibility = View.VISIBLE
            binding.tvDateExpiry.visibility = View.VISIBLE
            binding.tvDateExpiry.text = dateFormat.format(Date(item.expiryDate!!))
        } else {
            binding.tvDateExpiryLabel.visibility = View.GONE
            binding.tvDateExpiry.visibility = View.GONE
        }

        binding.tvDateRevisionLabel.visibility = View.VISIBLE
        binding.tvDateRevision.visibility = View.VISIBLE
        updateRevisionRow(item.lastRevisionDate)
    }

    // ============================================================
    // ЧИПЫ
    // ============================================================

    fun applyChips(item: ItemEntity) {
        when (item.itemType) {
            "food" -> {
                binding.chipType.text = "🍎 Еда"
                binding.chipType.setChipBackgroundColorResource(R.color.chipFoodBg)
                binding.chipType.setTextColor(ContextCompat.getColor(activity, R.color.chipFoodText))
            }
            "medicine" -> {
                binding.chipType.text = "💊 Лекарство"
                binding.chipType.setChipBackgroundColorResource(R.color.chipMedicineBg)
                binding.chipType.setTextColor(ContextCompat.getColor(activity, R.color.chipMedicineText))
            }
            "thing" -> {
                binding.chipType.text = "📦 Предмет"
                binding.chipType.setChipBackgroundColorResource(R.color.chipThingBg)
                binding.chipType.setTextColor(ContextCompat.getColor(activity, R.color.chipThingText))
            }
            else -> {
                binding.chipType.text = "🗂 Другое"
                binding.chipType.setChipBackgroundColorResource(R.color.chipOtherBg)
                binding.chipType.setTextColor(ContextCompat.getColor(activity, R.color.chipOtherText))
            }
        }
        binding.chipType.visibility = View.VISIBLE

        binding.chipExpired.visibility = if (item.isExpired) View.VISIBLE else View.GONE

        if (!item.isExpired && item.daysUntilExpiry in 0..3) {
            binding.chipSoon.visibility = View.VISIBLE
            binding.chipSoon.text = "⚠️ Осталось ${item.daysUntilExpiry} дн."
        } else {
            binding.chipSoon.visibility = View.GONE
        }

        binding.chipLent.visibility =
            if (item.isLent && !item.lentTo.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    // ============================================================
    // ПУТЬ
    // ============================================================

    suspend fun buildItemPath(item: ItemEntity): String {
        val segments = mutableListOf<String>()
        segments.add("📦 ${item.name}")

        var currentFolderId: String? = item.parentId
        var currentParentItemId: String? = item.parentItemId

        var depth = 0
        val maxDepth = 100

        while (depth < maxDepth) {
            depth++

            if (!currentParentItemId.isNullOrEmpty()) {
                val parentItem = db.itemDao().getItemById(currentParentItemId)
                if (parentItem == null) {
                    Logger.log(TAG, "buildItemPath: parent item not found: $currentParentItemId")
                    break
                }
                segments.add("📦 ${parentItem.name}")
                currentFolderId = parentItem.parentId
                currentParentItemId = parentItem.parentItemId
                continue
            }

            if (!currentFolderId.isNullOrEmpty()) {
                val folder = db.folderDao().getFolderById(currentFolderId)
                if (folder == null) {
                    Logger.log(TAG, "buildItemPath: folder not found: $currentFolderId")
                    break
                }
                segments.add("📁 ${folder.name}")
                currentParentItemId = folder.parentItemId
                currentFolderId = folder.parentId
                continue
            }

            break
        }

        if (segments.isEmpty()) {
            return "📂 Корень"
        }

        val pathBody = segments.reversed().joinToString(" / ")
        return "📂 Корень / $pathBody"
    }

    // ============================================================
    // ИСТОРИЯ
    // ============================================================

    fun showHistory(itemId: String) {
        stopStatusPulse()
        activity.lifecycleScope.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    db.historyDao().getHistoryForItem(itemId)
                }
                withContext(Dispatchers.Main) {
                    binding.tvTitle.text = "История изменений"

                    showViewMode(false)
                    binding.tilName.visibility = View.GONE
                    binding.quantityRow.visibility = View.GONE
                    binding.quantityEditBlock.visibility = View.GONE
                    binding.typeEditBlock.visibility = View.GONE
                    binding.subtypeEditBlock.visibility = View.GONE
                    binding.barcodeEditBlock.visibility = View.GONE
                    binding.tilExpiry.visibility = View.GONE
                    binding.tilPrice.visibility = View.GONE
                    binding.priceRow.visibility = View.GONE
                    binding.tilPurchaseDate.visibility = View.GONE
                    binding.tvDatePurchase.visibility = View.GONE
                    binding.btnSave.visibility = View.GONE
                    binding.editButtonsLayout.visibility = View.GONE
                    binding.btnAddPhoto.visibility = View.GONE
                    binding.cardLentInfo.visibility = View.GONE
                    binding.chipGroupStatus.visibility = View.GONE
                    binding.tvItemPath.visibility = View.GONE
                    binding.btnGoToFolder.visibility = View.GONE
                    binding.tvDateExpiryLabel.visibility = View.GONE
                    binding.tvDateExpiry.visibility = View.GONE
                    binding.expiryProgressBlock.visibility = View.GONE
                    binding.tvDateRevisionLabel.visibility = View.GONE
                    binding.tvDateRevision.visibility = View.GONE

                    binding.sectionNestedHeader.visibility = View.GONE
                    binding.cardNested.visibility = View.GONE

                    val historyText = if (history.isEmpty()) "История пуста"
                    else history.joinToString("\n\n") { entry ->
                        buildString {
                            append("📅 ${dateTimeFormat.format(Date(entry.changedAt))}\n")
                            append("👤 ${entry.changedBy}\n")
                            append("📝 ${entry.action}")
                            if (entry.oldValue != null && entry.newValue != null) {
                                append(": ${entry.oldValue} → ${entry.newValue}")
                            }
                        }
                    }

                    binding.tvDescriptionView.text = historyText
                    binding.tvDescriptionView.visibility = View.VISIBLE
                    binding.cardDescription.visibility = View.VISIBLE
                    binding.tvDescriptionTitle.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing history", e)
            }
        }
    }

    // ============================================================
    // РЕВИЗИЯ
    // ============================================================

    fun updateRevisionRow(timestamp: Long?) {
        binding.tvDateRevision.text = formatRevisionDate(timestamp)
        val color = if (isRevisionExpired(timestamp)) {
            Color.parseColor("#E57373")
        } else {
            ContextCompat.getColor(activity, R.color.dateValue)
        }
        binding.tvDateRevision.setTextColor(color)
    }

    private fun formatRevisionDate(timestamp: Long?): String {
        if (timestamp == null) return "не проводилась"

        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val daysAgo = TimeUnit.MILLISECONDS.toDays(diff).toInt()
        val dateStr = dateFormat.format(Date(timestamp))

        val daysText = when {
            daysAgo == 0 -> "сегодня"
            daysAgo == 1 -> "вчера"
            daysAgo < 30 -> "$daysAgo дн. назад"
            daysAgo < 365 -> "${daysAgo / 30} мес. назад"
            else -> "$daysAgo дн. назад"
        }

        val prefix = if (daysAgo >= REVISION_EXPIRED_DAYS) "⚠️ " else ""
        return "$prefix$dateStr ($daysText)"
    }

    private fun isRevisionExpired(timestamp: Long?): Boolean {
        if (timestamp == null) return false
        val daysAgo = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - timestamp)
        return daysAgo >= REVISION_EXPIRED_DAYS
    }

    // ============================================================
    // ДАТА ПОКУПКИ
    // ============================================================

    fun updatePurchaseDateRow(timestamp: Long?) {
        if (timestamp == null) {
            binding.tvDatePurchase.text = "— не указана"
            binding.tvDatePurchase.setTextColor(
                ContextCompat.getColor(activity, R.color.dateLabel)
            )
        } else {
            binding.tvDatePurchase.text = dateFormat.format(Date(timestamp))
            binding.tvDatePurchase.setTextColor(
                ContextCompat.getColor(activity, R.color.dateValue)
            )
        }
    }

    // ============================================================
    // ИНДИКАТОР СИНХРОНИЗАЦИИ
    // ============================================================

    fun updateSyncIndicator(item: ItemEntity) {
        activity.lifecycleScope.launch {
            try {
                val inQueue = withContext(Dispatchers.IO) {
                    db.outboxDao().getPending()
                        .any { it.entityType == "item" && it.entityId == item.id }
                }

                val hasLocalFile = withContext(Dispatchers.IO) {
                    val f = ImageUtils.getLocalImageFile(activity, item.id)
                    f != null && f.exists()
                }

                val (emoji, tooltip) = when {
                    inQueue -> Pair("⏳", "В очереди на отправку")
                    !item.imageUrl.isNullOrEmpty() ->
                        Pair("☁️", "Синхронизировано с Яндекс.Диском")
                    hasLocalFile ->
                        Pair("⚠️", "Фото есть локально, но не загружено на Диск")
                    else -> Pair("☁️", "Синхронизировано (нет фото)")
                }

                withContext(Dispatchers.Main) {
                    binding.tvSyncStatus.text = emoji
                    binding.tvSyncStatus.tag = tooltip
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error updating sync indicator: ${e.message}")
            }
        }
    }

    fun showSyncStatusToast() {
        val tooltip = binding.tvSyncStatus.tag as? String ?: "Статус неизвестен"
        android.widget.Toast.makeText(activity, tooltip, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // ПРОГРЕСС-БАР СРОКА ГОДНОСТИ
    // ============================================================

    fun updateExpiryProgress(item: ItemEntity) {
        val expiry = item.expiryDate
        if (expiry == null) {
            binding.expiryProgressBlock.visibility = View.GONE
            return
        }

        binding.expiryProgressBlock.visibility = View.VISIBLE

        val now = System.currentTimeMillis()
        val added = item.purchaseDate ?: item.addedDate

        val totalSpan = (expiry - added).coerceAtLeast(1L)
        val elapsed = (now - added).coerceAtLeast(0L)
        val progressPercent = ((elapsed.toFloat() / totalSpan.toFloat()) * 100f)
            .coerceIn(0f, 100f)
            .toInt()

        val daysLeft = TimeUnit.MILLISECONDS.toDays(expiry - now).toInt()

        val (color, label) = when {
            daysLeft < 0 -> {
                val overdue = -daysLeft
                Pair(Color.parseColor("#E57373"), "⏰ Просрочен на $overdue дн. (100%)")
            }
            daysLeft == 0 -> Pair(Color.parseColor("#E57373"), "⏰ Истекает сегодня!")
            daysLeft in 1..3 -> Pair(Color.parseColor("#C97B63"), "⚠️ Осталось $daysLeft дн. ($progressPercent%)")
            daysLeft in 4..14 -> Pair(Color.parseColor("#FFD54F"), "⏳ Осталось $daysLeft дн. ($progressPercent%)")
            else -> Pair(Color.parseColor("#81C784"), "✅ Осталось $daysLeft дн. ($progressPercent%)")
        }

        binding.expiryProgress.setIndicatorColor(color)
        binding.expiryProgress.setProgressCompat(progressPercent, true)
        binding.tvExpiryProgressLabel.text = label
        binding.tvExpiryProgressLabel.setTextColor(color)
    }

    // ============================================================
    // АНИМАЦИИ ПОЯВЛЕНИЯ
    // ============================================================

    fun animateContentAppearance() {
        val contentContainer = binding.contentContainer
        val count = contentContainer.childCount
        for (i in 0 until count) {
            val view = contentContainer.getChildAt(i)
            view.alpha = 0f
            view.translationY = 40f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((i * APPEAR_STAGGER))
                .setDuration(APPEAR_DURATION)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    // ============================================================
    // ПУЛЬСАЦИИ
    // ============================================================

    fun startStatusPulse() {
        stopStatusPulse()

        if (binding.chipExpired.visibility == View.VISIBLE) {
            expiredPulse = ObjectAnimator.ofFloat(binding.chipExpired, "alpha", 1.0f, 0.55f).apply {
                duration = 1200L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }

        if (binding.chipSoon.visibility == View.VISIBLE) {
            soonPulse = ObjectAnimator.ofFloat(binding.chipSoon, "alpha", 1.0f, 0.55f).apply {
                duration = 1200L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    fun stopStatusPulse() {
        expiredPulse?.cancel()
        expiredPulse = null
        binding.chipExpired.alpha = 1f

        soonPulse?.cancel()
        soonPulse = null
        binding.chipSoon.alpha = 1f
    }

    // ============================================================
    // АНИМАЦИИ ЧИСЕЛ
    // ============================================================

    fun animateQuantity(target: Int) {
        quantityAnimator?.cancel()
        binding.chipQuantityView.post {
            if (target <= 0) {
                binding.chipQuantityView.text = "📦 Количество: ×0"
                return@post
            }
            quantityAnimator = ValueAnimator.ofInt(0, target).apply {
                duration = 400L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val value = anim.animatedValue as Int
                    binding.chipQuantityView.text = "📦 Количество: ×$value"
                }
                start()
            }
        }
    }

    fun updatePriceRow(unitPrice: Double?, quantity: Int) {
        if (unitPrice == null || unitPrice == 0.0) {
            binding.tvPrice.visibility = View.GONE
            binding.tvTotalPrice.visibility = View.GONE
            return
        }

        binding.tvPrice.visibility = View.VISIBLE
        animatePrice(unitPrice)

        if (quantity > 1) {
            animateTotalPrice(unitPrice * quantity)
        } else {
            totalPriceAnimator?.cancel()
            binding.tvTotalPrice.visibility = View.GONE
        }
    }

    private fun animatePrice(target: Double) {
        priceAnimator?.cancel()
        binding.tvPrice.post {
            if (target <= 0.0) return@post
            val isWhole = target == target.toLong().toDouble()
            priceAnimator = ValueAnimator.ofFloat(0f, target.toFloat()).apply {
                duration = 600L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val value = anim.animatedValue as Float
                    val formatted = if (isWhole) {
                        moneyFormat.format(value.toLong())
                    } else {
                        moneyFormat.format(value.toDouble())
                    }
                    binding.tvPrice.text = "💰 $formatted ₽ / шт."
                }
                start()
            }
        }
    }

    private fun animateTotalPrice(target: Double) {
        totalPriceAnimator?.cancel()
        binding.tvTotalPrice.post {
            if (target <= 0.0) {
                binding.tvTotalPrice.visibility = View.GONE
                return@post
            }
            binding.tvTotalPrice.visibility = View.VISIBLE
            totalPriceAnimator = ValueAnimator.ofFloat(0f, target.toFloat()).apply {
                duration = 600L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val value = anim.animatedValue as Float
                    binding.tvTotalPrice.text = "Всего: ${moneyFormat.format(value.toDouble())} ₽"
                }
                start()
            }
        }
    }

    // ============================================================
    // ОЧИСТКА АНИМАТОРОВ
    // ============================================================

    fun cleanup() {
        quantityAnimator?.cancel()
        priceAnimator?.cancel()
        totalPriceAnimator?.cancel()
        stopStatusPulse()
    }
}

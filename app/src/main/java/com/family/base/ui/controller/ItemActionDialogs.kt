package com.family.base.ui.controller

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.remote.ProductLookupService
import com.family.base.databinding.ActivityItemDetailBinding
import com.family.base.ui.MoveDialogHelper
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Все диалоги действий над предметом в карточке.
 *
 * 🆕 v20.3.8: showWriteOffReasonDialog после списания:
 *  - полное списание (archived=true) → onFinish() — закрывает ItemDetailActivity;
 *  - частичное (archived=false) → onCurrentItemChanged(item.copy(quantity -= count)).
 */
class ItemActionDialogs(
    private val activity: AppCompatActivity,
    private val binding: ActivityItemDetailBinding,
    private val db: AppDatabase,
    private val viewModel: MainViewModel,
    private val itemIdProvider: () -> String?,
    private val currentItemProvider: () -> ItemEntity?,
    private val onCurrentItemChanged: (ItemEntity) -> Unit,
    private val onFinish: () -> Unit,
    private val onLoadNestedContent: () -> Unit,
    private val onOpenAddItemActivity: (parentItemId: String) -> Unit
) {

    private val TAG = "ItemActionDialogs"

    private val productLookupService = ProductLookupService()

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    // ============================================================
    // СПИСАНИЕ
    // ============================================================

    private fun getWriteOffNoun(item: ItemEntity): String {
        return when (item.itemType) {
            "food" -> "продукт"
            "medicine" -> "лекарство"
            "thing" -> "вещь"
            else -> "предмет"
        }
    }

    private fun getWriteOffTitle(item: ItemEntity): String {
        val shortName = if (item.name.length > 30) item.name.take(30) + "…" else item.name
        val noun = getWriteOffNoun(item)
        return "Списать $noun: $shortName"
    }

    private fun getMoveTitle(item: ItemEntity): String {
        val shortName = if (item.name.length > 30) item.name.take(30) + "…" else item.name
        val noun = getWriteOffNoun(item)
        return "Переместить $noun: $shortName"
    }

    fun showWriteOffDialog() {
        val id = itemIdProvider() ?: return
        val item = currentItemProvider() ?: return

        if (item.quantity <= 0) {
            Toast.makeText(activity, "Нет предметов для списания", Toast.LENGTH_SHORT).show()
            return
        }

        val stepper = buildStepperLayout(item.quantity)

        AlertDialog.Builder(activity)
            .setTitle(getWriteOffTitle(item))
            .setView(stepper.container)
            .setPositiveButton("Далее") { _, _ ->
                val count = stepper.getValue()
                if (count <= 0) {
                    Toast.makeText(activity, "Введите число больше 0", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (count > item.quantity) {
                    Toast.makeText(activity, "Недостаточно штук (всего ${item.quantity})", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showWriteOffReasonDialog(id, count)
            }
            .setNegativeButton("Отмена") { _, _ -> stopRepeat() }
            .setOnDismissListener { stopRepeat() }
            .show()
    }

    /**
     * 🆕 v20.3.8: после списания:
     *  - archived = true → onFinish() (закрыть карточку, предмет ушёл в архив);
     *  - archived = false → onCurrentItemChanged(...) (обновить счётчик).
     */
    private fun showWriteOffReasonDialog(id: String, count: Int) {
        val item = currentItemProvider()
        val noun = if (item != null) getWriteOffNoun(item) else "предмет"
        val totalQty = item?.quantity ?: count

        val title = if (count == totalQty) {
            "Списываешь последнюю $noun.\nПредмет уйдёт в архив."
        } else {
            "Списать $count шт. — в архив.\nВыберите причину:"
        }

        val reasons = arrayOf(
            "🧴 Израсходовано",
            "🍽 Съедено",
            "🔧 Сломано",
            "🗑 Выброшено",
            "🎁 Подарено",
            "💰 Продано",
            "⏰ Истёк срок",
            "📦 Другое"
        )
        val reasonKeys = arrayOf(
            "used_up",
            "eaten",
            "broken",
            "thrown",
            "gifted",
            "sold",
            "expired",
            "other"
        )

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(reasons) { _, which ->
                val itemForBlock = item
                val itemForUpdate = item
                viewModel.writeOffItem(
                    itemId = id,
                    count = count,
                    reason = reasonKeys[which],
                    note = null,
                    onBlocked = { msg ->
                        if (itemForBlock != null) {
                            showArchiveBlockedDialog(itemForBlock, msg)
                        }
                    },
                    onDone = { archived ->
                        if (archived) {
                            // Полное списание → предмет в архиве, карточку закрываем.
                            Logger.log(TAG, "writeOff onDone: archived=true, finishing item card")
                            onFinish()
                        } else {
                            // Частичное списание → обновляем счётчик в карточке.
                            if (itemForUpdate != null) {
                                val newQty = (itemForUpdate.quantity - count).coerceAtLeast(0)
                                Logger.log(TAG, "writeOff onDone: archived=false, newQty=$newQty")
                                onCurrentItemChanged(itemForUpdate.copy(quantity = newQty))
                            }
                        }
                    }
                )
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // СТЕППЕР
    // ============================================================

    private class StepperResult(
        val container: LinearLayout,
        val editText: EditText,
        val checkAll: CheckBox,
        val minusBtn: View,
        val plusBtn: View,
        val maxQty: Int
    ) {
        fun getValue(): Int = editText.text.toString().toIntOrNull() ?: 1
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildStepperLayout(maxQty: Int): StepperResult {
        return buildStepperLayoutInternal(maxQty, withCheckAll = false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildStepperLayoutWithAll(maxQty: Int): StepperResult {
        return buildStepperLayoutInternal(maxQty, withCheckAll = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildStepperLayoutInternal(maxQty: Int, withCheckAll: Boolean): StepperResult {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }

        val minusBtn = Button(activity).apply {
            text = "−"
            textSize = 22f
            minWidth = 0
            minimumWidth = 0
            width = 120
            height = 120
            setPadding(0, 0, 0, 0)
        }

        val editText = EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
            gravity = android.view.Gravity.CENTER
            textSize = 20f
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = 16
                marginEnd = 16
            }
        }

        val plusBtn = Button(activity).apply {
            text = "+"
            textSize = 22f
            minWidth = 0
            minimumWidth = 0
            width = 120
            height = 120
            setPadding(0, 0, 0, 0)
        }

        row.addView(minusBtn)
        row.addView(editText)
        row.addView(plusBtn)
        container.addView(row)

        val checkAll = CheckBox(activity).apply {
            text = "Переместить всё ($maxQty шт.)"
            textSize = 15f
            visibility = if (withCheckAll) View.VISIBLE else View.GONE
            setPadding(0, 16, 0, 0)
        }
        if (withCheckAll) container.addView(checkAll)

        val result = StepperResult(container, editText, checkAll, minusBtn, plusBtn, maxQty)

        val changeBy = { delta: Int ->
            if (!(withCheckAll && checkAll.isChecked)) {
                val cur = result.getValue()
                val next = (cur + delta).coerceIn(1, maxQty)
                if (next != cur) {
                    editText.setText(next.toString())
                    editText.setSelection(editText.text.length)
                }
            }
        }

        minusBtn.setOnClickListener { changeBy(-1) }
        plusBtn.setOnClickListener { changeBy(+1) }

        setupRepeatButton(minusBtn) { changeBy(-1) }
        setupRepeatButton(plusBtn) { changeBy(+1) }

        if (withCheckAll) {
            checkAll.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    editText.setText(maxQty.toString())
                    editText.isEnabled = false
                    minusBtn.isEnabled = false
                    plusBtn.isEnabled = false
                } else {
                    editText.setText("1")
                    editText.isEnabled = true
                    minusBtn.isEnabled = true
                    plusBtn.isEnabled = true
                }
            }
        }

        return result
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRepeatButton(view: View, action: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    repeatRunnable = object : Runnable {
                        override fun run() {
                            action()
                            repeatHandler.postDelayed(this, 100)
                        }
                    }
                    repeatHandler.postDelayed(repeatRunnable!!, 400)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRepeat()
                    v.isPressed = false
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    fun stopRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    // ============================================================
    // БЫСТРОЕ ИЗМЕНЕНИЕ КОЛИЧЕСТВА
    // ============================================================

    fun showQuickQuantityDialog() {
        val id = itemIdProvider() ?: return
        val item = currentItemProvider() ?: return

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        val etQty = EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(item.quantity.toString())
            hint = "Количество"
            setSelectAllOnFocus(true)
        }
        container.addView(etQty)

        AlertDialog.Builder(activity)
            .setTitle("📦 Быстрое изменение количества")
            .setMessage("Текущее: ${item.quantity}")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                val newQty = etQty.text.toString().toIntOrNull()
                if (newQty == null || newQty < 0) {
                    Toast.makeText(activity, "Введите корректное число", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newQty == item.quantity) {
                    Toast.makeText(activity, "Количество не изменилось", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.updateItemQuantity(id, newQty)
                val updated = item.copy(quantity = newQty)
                onCurrentItemChanged(updated)
                Toast.makeText(activity, "Количество: $newQty", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // ЗАЙМ
    // ============================================================

    fun showLendDialog() {
        val id = itemIdProvider() ?: return
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        val etPerson = EditText(activity).apply { hint = "Кому выдать (имя)" }
        val etNote = EditText(activity).apply { hint = "Заметка (необязательно)" }
        container.addView(etPerson)
        container.addView(etNote)

        AlertDialog.Builder(activity)
            .setTitle("🤝 Выдать предмет")
            .setView(container)
            .setPositiveButton("Выдать") { _, _ ->
                val person = etPerson.text.toString().trim()
                val note = etNote.text.toString().trim().ifEmpty { null }
                if (person.isNotEmpty()) {
                    viewModel.lendItem(id, person, note)
                    Toast.makeText(activity, "Выдан: $person", Toast.LENGTH_SHORT).show()
                    onFinish()
                } else {
                    Toast.makeText(activity, "Введите имя", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    fun showReturnDialog() {
        val id = itemIdProvider() ?: return
        AlertDialog.Builder(activity)
            .setTitle("↩️ Вернуть предмет?")
            .setMessage("Предмет вернётся в базу.")
            .setPositiveButton("Вернуть") { _, _ ->
                viewModel.returnItem(id)
                Toast.makeText(activity, "Предмет возвращён", Toast.LENGTH_SHORT).show()
                onFinish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // РЕВИЗИЯ
    // ============================================================

    fun showRevisionDialog(onRevisionMarked: (Long) -> Unit) {
        val id = itemIdProvider() ?: return
        val item = currentItemProvider() ?: return

        AlertDialog.Builder(activity)
            .setTitle("Провести ревизию: ${item.name}?")
            .setMessage("Отметить, что предмет проверен сегодня?")
            .setPositiveButton("Да") { _, _ ->
                viewModel.markRevision(id)
                val now = System.currentTimeMillis()
                onRevisionMarked(now)
                Toast.makeText(activity, "Ревизия отмечена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    // ============================================================
    // ПЕРЕМЕЩЕНИЕ
    // ============================================================

    fun showMoveDialog() {
        val id = itemIdProvider() ?: return
        val item = currentItemProvider()

        if (item == null) {
            Toast.makeText(activity, "Предмет не найден", Toast.LENGTH_SHORT).show()
            return
        }

        if (item.quantity <= 1) {
            openMoveFolderPicker(id, 1)
            return
        }

        val stepper = buildStepperLayoutWithAll(item.quantity)

        AlertDialog.Builder(activity)
            .setTitle(getMoveTitle(item))
            .setView(stepper.container)
            .setPositiveButton("Далее") { _, _ ->
                val count = stepper.getValue()
                if (count <= 0) {
                    Toast.makeText(activity, "Введите число больше 0", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (count > item.quantity) {
                    Toast.makeText(activity, "Недостаточно штук (всего ${item.quantity})", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                openMoveFolderPicker(id, count)
            }
            .setNegativeButton("Отмена") { _, _ -> stopRepeat() }
            .setOnDismissListener { stopRepeat() }
            .show()
    }

    private fun openMoveFolderPicker(id: String, count: Int) {
        activity.lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(id) }
                if (item == null) {
                    Toast.makeText(activity, "Предмет не найден", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                Logger.log(
                    TAG,
                    "openMoveFolderPicker: item=${item.name}, count=$count, " +
                        "current parentId=${item.parentId}, current parentItemId=${item.parentItemId}"
                )

                MoveDialogHelper.show(
                    context = activity,
                    scope = activity.lifecycleScope,
                    db = db,
                    title = "Переместить «${item.name}»",
                    startFromId = item.parentId,
                    startFromItemId = item.parentItemId,
                    excludedIds = emptySet(),
                    onConfirm = { newParentId, newParentItemId ->
                        Logger.log(
                            TAG,
                            "Move confirmed: newParentId=$newParentId, newParentItemId=$newParentItemId, count=$count"
                        )
                        if (newParentId == item.parentId && newParentItemId == item.parentItemId) {
                            Toast.makeText(activity, "Предмет уже здесь", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.splitAndMoveItem(id, count, newParentId, newParentItemId)
                            Toast.makeText(activity, "Перемещено $count шт.", Toast.LENGTH_SHORT).show()
                            onFinish()
                        }
                    }
                )
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing move dialog", e)
            }
        }
    }

    // ============================================================
    // АРХИВАЦИЯ
    // ============================================================

    fun showArchiveDialog() {
        val id = itemIdProvider() ?: return

        val reasons = arrayOf(
            "🧴 Израсходовано", "🍽 Съедено", "🔧 Сломано", "🗑 Выброшено",
            "🎁 Подарено", "💰 Продано", "⏰ Истёк срок", "📦 Другое"
        )
        val reasonKeys = arrayOf(
            "used_up", "eaten", "broken", "thrown",
            "gifted", "sold", "expired", "other"
        )

        AlertDialog.Builder(activity)
            .setTitle("📦 В архив: ${binding.tvTitle.text}")
            .setItems(reasons) { _, which ->
                val itemForBlock = currentItemProvider()
                viewModel.archiveItem(
                    id,
                    reasonKeys[which],
                    null,
                    onBlocked = { msg ->
                        if (itemForBlock != null) {
                            showArchiveBlockedDialog(itemForBlock, msg)
                        }
                    }
                )
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    fun showArchiveBlockedDialog(item: ItemEntity, message: String) {
        AlertDialog.Builder(activity)
            .setTitle("⚠️ Нельзя архивировать")
            .setMessage(message)
            .setPositiveButton("Отвязать всех детей") { _, _ ->
                viewModel.detachAllChildren(item.id) { count ->
                    Toast.makeText(
                        activity,
                        "Отвязано: $count. Теперь можно архивировать.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // УДАЛЕНИЕ
    // ============================================================

    fun showDeleteDialog(onShowArchiveDialog: () -> Unit) {
        val id = itemIdProvider() ?: return

        viewModel.getChildrenCount(id) { (foldersCount, itemsCount) ->
            val totalChildren = foldersCount + itemsCount

            if (totalChildren > 0) {
                val childInfo = buildString {
                    append("У предмета есть вложенные:\n")
                    if (foldersCount > 0) append("📁 Папок: $foldersCount\n")
                    if (itemsCount > 0) append("📦 Предметов: $itemsCount\n")
                    append("\nНельзя удалить, пока есть вложенные.\n")
                    append("Сначала отвяжите их — они поднимутся в ту же папку, где лежит этот предмет.")
                }

                AlertDialog.Builder(activity)
                    .setTitle("⚠️ Нельзя удалить")
                    .setMessage(childInfo)
                    .setPositiveButton("Отвязать всех детей") { _, _ ->
                        viewModel.detachAllChildren(id) { count ->
                            Toast.makeText(
                                activity,
                                "Отвязано: $count. Теперь можно удалить.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("🗑 Удалить предмет?")
                    .setMessage("Это действие нельзя отменить. Возможно, лучше в архив?")
                    .setPositiveButton("Удалить") { _, _ ->
                        viewModel.deleteItem(id)
                        Toast.makeText(activity, "Предмет удалён", Toast.LENGTH_SHORT).show()
                        onFinish()
                    }
                    .setNeutralButton("В архив") { _, _ -> onShowArchiveDialog() }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
    }

    // ============================================================
    // ВЛОЖЕННЫЕ
    // ============================================================

    fun showAddNestedDialog() {
        val id = itemIdProvider() ?: return
        val options = arrayOf("📁 Создать папку", "📦 Создать предмет")

        AlertDialog.Builder(activity)
            .setTitle("Добавить вложенный")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateNestedFolderDialog(id)
                    1 -> onOpenAddItemActivity(id)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showCreateNestedFolderDialog(parentItemId: String) {
        val editText = EditText(activity).apply {
            hint = "Название папки"
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        container.addView(editText)

        AlertDialog.Builder(activity)
            .setTitle("📁 Новая вложенная папка")
            .setView(container)
            .setPositiveButton("Создать") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(activity, "Введите название", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.createFolderInItem(name, parentItemId) { createdFolderId ->
                    if (createdFolderId != null) {
                        Toast.makeText(activity, "Папка «$name» создана", Toast.LENGTH_SHORT).show()
                        onLoadNestedContent()
                    } else {
                        Toast.makeText(activity, "Ошибка создания папки", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // ШТРИХ-КОД
    // ============================================================

    fun showLookupDialog(barcode: String) {
        AlertDialog.Builder(activity)
            .setTitle("🔍 Найти товар?")
            .setMessage("Найден штрих-код: $barcode\n\nИскать информацию?")
            .setPositiveButton("Искать") { _, _ -> lookupProduct(barcode) }
            .setNegativeButton("Только код", null)
            .show()
    }

    private fun lookupProduct(barcode: String) {
        Toast.makeText(activity, "Поиск товара...", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
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
                    val desc = buildString {
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

                    AlertDialog.Builder(activity)
                        .setTitle("✅ Найдено: ${displayName ?: barcode}")
                        .setMessage(
                            "Что заполнить?\n\nНазвание: ${displayName ?: "—"}\n\n" +
                                "Описание: ${desc.ifEmpty { "—" }}"
                        )
                        .setPositiveButton("Заполнить всё") { _, _ ->
                            displayName?.let { binding.etName.setText(it) }
                            if (desc.isNotEmpty()) binding.etDescription.setText(desc)
                        }
                        .setNeutralButton("Только название") { _, _ ->
                            displayName?.let { binding.etName.setText(it) }
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                } else {
                    Toast.makeText(activity, "Товар не найден", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error looking up product", e)
            }
        }
    }
}

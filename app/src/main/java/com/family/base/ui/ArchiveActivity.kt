package com.family.base.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.family.base.BaseApplication
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.databinding.ActivityArchiveBinding
import com.family.base.ui.adapter.ArchiveAdapter
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ArchiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArchiveBinding
    private lateinit var db: AppDatabase
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: ArchiveAdapter
    private val TAG = "ArchiveActivity"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    private enum class ArchiveFolder { THINGS, MEDICINE, FOOD }

    private enum class Sort(val label: String) {
        ARCHIVED_DESC("📅 По дате архивации: новые сначала"),
        ARCHIVED_ASC("📅 По дате архивации: старые сначала"),
        NAME_ASC("🔤 По имени: А→Я"),
        NAME_DESC("🔤 По имени: Я→А"),
        PRICE_DESC("💰 По цене: дорогие сначала"),
        PURCHASE_DESC("🛒 По дате покупки: новые сначала"),
        PURCHASE_ASC("🛒 По дате покупки: старые сначала")
    }

    private var currentFolder: ArchiveFolder = ArchiveFolder.THINGS
    private var currentReasonKey: String? = null
    private var currentSort: Sort = Sort.ARCHIVED_DESC
    private var currentSearchQuery: String? = null

    private var allArchivedItems: List<ItemEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== ArchiveActivity onCreate START ===")

        binding = ActivityArchiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        viewModel = BaseApplication.mainViewModel

        adapter = ArchiveAdapter(
            onItemClick = { item -> showItemDetails(item) },
            onRestoreClick = { item -> restoreItem(item) }
        )

        binding.rvArchive.layoutManager = LinearLayoutManager(this)
        binding.rvArchive.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSearch.setOnClickListener { showSearchDialog() }
        binding.btnSort.setOnClickListener { showSortDialog() }

        setupFolderChips()
        setupReasonChips()

        // 🆕 Extra: стартовая папка архива
        applyStartFolderFromIntent()

        updateSearchIcon()
        loadAllArchive()

        Logger.log(TAG, "=== ArchiveActivity onCreate FINISHED ===")
    }

    // ==================== СТАРТОВАЯ ПАПКА ИЗ INTENT ====================

    private fun applyStartFolderFromIntent() {
        val startFolder = intent.getStringExtra("start_folder") ?: return
        Logger.log(TAG, "Requested start_folder=$startFolder")

        when (startFolder) {
            "food" -> {
                currentFolder = ArchiveFolder.FOOD
                binding.chipGroupFolder.check(R.id.chipFolderFood)
            }
            "medicine" -> {
                currentFolder = ArchiveFolder.MEDICINE
                binding.chipGroupFolder.check(R.id.chipFolderMedicine)
            }
            "things" -> {
                currentFolder = ArchiveFolder.THINGS
                binding.chipGroupFolder.check(R.id.chipFolderThings)
            }
            else -> Logger.log(TAG, "Unknown start_folder: $startFolder")
        }
    }

    // ==================== ПАПКИ ====================

    private fun setupFolderChips() {
        binding.chipGroupFolder.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipFolderMedicine -> currentFolder = ArchiveFolder.MEDICINE
                R.id.chipFolderFood -> currentFolder = ArchiveFolder.FOOD
                else -> currentFolder = ArchiveFolder.THINGS
            }
            binding.chipGroupReason.check(R.id.chipReasonAll)
            currentReasonKey = null
            applyFilters()
        }
    }

    private fun setupReasonChips() {
        binding.chipGroupReason.setOnCheckedStateChangeListener { _, checkedIds ->
            currentReasonKey = when (checkedIds.firstOrNull()) {
                R.id.chipReasonUsedUp -> "used_up"
                R.id.chipReasonEaten -> "eaten"
                R.id.chipReasonBroken -> "broken"
                R.id.chipReasonThrown -> "thrown"
                R.id.chipReasonGifted -> "gifted"
                R.id.chipReasonSold -> "sold"
                R.id.chipReasonExpired -> "expired"
                R.id.chipReasonOther -> "other"
                else -> null
            }
            applyFilters()
        }
    }

    // ==================== ЗАГРУЗКА ====================

    private fun loadAllArchive() {
        lifecycleScope.launch {
            try {
                allArchivedItems = withContext(Dispatchers.IO) {
                    db.itemDao().getArchivedItems()
                }
                applyFilters()
                Logger.log(TAG, "Loaded ${allArchivedItems.size} archived items total")
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading archive", e)
                Toast.makeText(this@ArchiveActivity, "Ошибка загрузки архива", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyFilters() {
        val byFolder = allArchivedItems.filter { matchesFolder(it, currentFolder) }
        val byReason = if (currentReasonKey == null) {
            byFolder
        } else {
            byFolder.filter { it.archivedReason == currentReasonKey }
        }
        val bySearch = if (currentSearchQuery.isNullOrEmpty()) {
            byReason
        } else {
            val q = currentSearchQuery!!
            byReason.filter { it.name.contains(q, ignoreCase = true) }
        }

        val result = applySort(bySearch)

        adapter.submitList(result)

        val totalSum = result.sumOf { (it.price ?: 0.0) * it.quantity }
        binding.tvArchiveTotal.text = "Итого в архиве: ${formatMoney(totalSum)}"
        binding.tvArchiveCount.text = "Предметов: ${result.size}"
    }

    // ==================== СОРТИРОВКА ====================

    private fun applySort(items: List<ItemEntity>): List<ItemEntity> {
        return when (currentSort) {
            Sort.ARCHIVED_DESC -> items.sortedByDescending { it.archivedDate ?: 0L }

            Sort.ARCHIVED_ASC -> items.sortedBy { it.archivedDate ?: 0L }

            Sort.NAME_ASC -> items.sortedBy { it.name.lowercase() }

            Sort.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }

            Sort.PRICE_DESC -> items.sortedByDescending { (it.price ?: 0.0) * it.quantity }

            Sort.PURCHASE_DESC -> items.sortedByDescending { it.purchaseDate ?: it.addedDate }

            Sort.PURCHASE_ASC -> items.sortedBy { it.purchaseDate ?: it.addedDate }
        }
    }

    private fun showSortDialog() {
        val options = Sort.values().map { it.label }.toTypedArray()
        val checkedItem = Sort.values().indexOf(currentSort)

        AlertDialog.Builder(this)
            .setTitle("Сортировка")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                currentSort = Sort.values()[which]
                dialog.dismiss()
                applyFilters()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ==================== ПОИСК ====================

    private fun showSearchDialog() {
        val editText = EditText(this).apply {
            hint = "Поиск по названию..."
            setText(currentSearchQuery ?: "")
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Поиск в архиве")
            .setView(editText)
            .setPositiveButton("Искать") { _, _ ->
                val q = editText.text.toString().trim()
                currentSearchQuery = if (q.isEmpty()) null else q
                updateSearchIcon()
                applyFilters()
            }
            .setNegativeButton("Сбросить") { _, _ ->
                currentSearchQuery = null
                updateSearchIcon()
                applyFilters()
            }
            .setNeutralButton("Отмена", null)
            .show()
    }

    private fun updateSearchIcon() {
        if (currentSearchQuery.isNullOrEmpty()) {
            binding.btnSearch.setImageResource(R.drawable.ic_search)
            binding.btnSearch.contentDescription = "Поиск"
            binding.btnSearch.setOnClickListener { showSearchDialog() }
        } else {
            binding.btnSearch.setImageResource(R.drawable.ic_close)
            binding.btnSearch.contentDescription = "Сбросить поиск"
            binding.btnSearch.setOnClickListener {
                currentSearchQuery = null
                updateSearchIcon()
                applyFilters()
            }
        }
    }

    private fun matchesFolder(item: ItemEntity, folder: ArchiveFolder): Boolean {
        return when (folder) {
            ArchiveFolder.FOOD -> item.itemType == "food"
            ArchiveFolder.MEDICINE -> item.itemType == "medicine"
            ArchiveFolder.THINGS -> {
                val t = item.itemType
                t == null || t.isEmpty() || t == "thing" || t == "other"
            }
        }
    }

    // ==================== ДЕТАЛИ / ВОССТАНОВЛЕНИЕ ====================

    /**
     * 🆕 v20.2.2: карточка предмета в архиве теперь с фото.
     *
     * Раньше был AlertDialog с простым текстом. Теперь — кастомный view
     * (dialog_archive_detail.xml): фото сверху, под ним причина, дата,
     * цена, количество, описание.
     *
     * Фото грузим в три шага:
     *  1. локальный файл (ImageUtils.getLocalImageFile) — если есть, сразу показываем;
     *  2. если нет — тихая догрузка с Яндекс.Диска через
     *     viewModel.downloadItemImageBytes(item.id), сохраняем локально
     *     (ImageUtils.saveImageLocally) и показываем;
     *  3. если и на Диске нет — остаётся плейсхолдер.
     *
     * Во время догрузки показываем ProgressBar.
     */
    private fun showItemDetails(item: ItemEntity) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_archive_detail, null)

        val ivPhoto = dialogView.findViewById<ImageView>(R.id.ivItemPhoto)
        val ivPlaceholder = dialogView.findViewById<ImageView>(R.id.ivItemPhotoPlaceholder)
        val photoLoadingBar = dialogView.findViewById<ProgressBar>(R.id.photoLoadingBar)

        val tvReason = dialogView.findViewById<TextView>(R.id.tvReason)
        val tvArchivedDate = dialogView.findViewById<TextView>(R.id.tvArchivedDate)
        val tvNote = dialogView.findViewById<TextView>(R.id.tvNote)
        val tvPrice = dialogView.findViewById<TextView>(R.id.tvPrice)
        val tvQuantity = dialogView.findViewById<TextView>(R.id.tvQuantity)
        val tvDescription = dialogView.findViewById<TextView>(R.id.tvDescription)

        // Заполняем текстовые поля
        val reasonText = getReasonText(item.archivedReason)
        tvReason.text = "Причина: $reasonText"

        val dateText = item.archivedDate?.let { dateFormat.format(Date(it)) } ?: "—"
        tvArchivedDate.text = "Дата: $dateText"

        if (!item.archivedNote.isNullOrEmpty()) {
            tvNote.visibility = View.VISIBLE
            tvNote.text = "Заметка: ${item.archivedNote}"
        } else {
            tvNote.visibility = View.GONE
        }

        tvPrice.text = "💰 Цена: ${formatMoney((item.price ?: 0.0) * item.quantity)}"
        tvQuantity.text = "📦 Количество: ${item.quantity}"

        if (!item.description.isNullOrEmpty()) {
            tvDescription.visibility = View.VISIBLE
            tvDescription.text = "Описание: ${item.description}"
        } else {
            tvDescription.visibility = View.GONE
        }

        // Загрузка фото
        loadArchivePhoto(item, ivPhoto, ivPlaceholder, photoLoadingBar)

        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setView(dialogView)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * 🆕 v20.2.2: трёхступенчатая загрузка фото для карточки архива.
     */
    private fun loadArchivePhoto(
        item: ItemEntity,
        ivPhoto: ImageView,
        ivPlaceholder: ImageView,
        loadingBar: ProgressBar
    ) {
        // Шаг 1: локальный файл
        val localFile = ImageUtils.getLocalImageFile(this, item.id)
        if (localFile != null && localFile.exists()) {
            ivPlaceholder.visibility = View.GONE
            ivPhoto.visibility = View.VISIBLE
            ivPhoto.load(localFile) {
                crossfade(true)
                error(R.drawable.ic_item_default)
            }
            loadingBar.visibility = View.GONE
            return
        }

        // Шаг 2: тихая догрузка с Диска
        ivPlaceholder.visibility = View.VISIBLE
        ivPhoto.visibility = View.GONE
        loadingBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    viewModel.downloadItemImageBytes(item.id)
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        ImageUtils.saveImageLocally(applicationContext, item.id, bytes)
                    }
                    val file = ImageUtils.getLocalImageFile(this@ArchiveActivity, item.id)
                    if (file != null && file.exists()) {
                        ivPlaceholder.visibility = View.GONE
                        ivPhoto.visibility = View.VISIBLE
                        ivPhoto.load(file) {
                            crossfade(true)
                            error(R.drawable.ic_item_default)
                        }
                    } else {
                        ivPlaceholder.visibility = View.VISIBLE
                        ivPhoto.visibility = View.GONE
                    }
                } else {
                    // Фото нет ни локально, ни на Диске — оставляем плейсхолдер
                    Logger.log(TAG, "No photo for archived item '${item.name}' (id=${item.id})")
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading archive photo for '${item.name}': ${e.message}")
            } finally {
                loadingBar.visibility = View.GONE
            }
        }
    }

    private fun restoreItem(item: ItemEntity) {
        AlertDialog.Builder(this)
            .setTitle("Вернуть из архива?")
            .setMessage("Предмет «${item.name}» вернётся в базу.")
            .setPositiveButton("Вернуть") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            db.itemDao().unarchiveItem(item.id, System.currentTimeMillis())
                        }
                        Toast.makeText(this@ArchiveActivity, "Предмет возвращён в базу", Toast.LENGTH_SHORT).show()
                        loadAllArchive()
                    } catch (e: Exception) {
                        Logger.log(TAG, "Error restoring item", e)
                        Toast.makeText(this@ArchiveActivity, "Ошибка возврата", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ==================== ХЕЛПЕРЫ ====================

    private fun getReasonText(reason: String?): String {
        return when (reason) {
            "used_up" -> "🧴 Израсходовано"
            "eaten" -> "🍽 Съедено"
            "broken" -> "🔧 Сломано"
            "thrown" -> "🗑 Выброшено"
            "gifted" -> "🎁 Подарено"
            "sold" -> "💰 Продано"
            "expired" -> "⏰ Истёк срок"
            "other" -> "📦 Другое"
            else -> "—"
        }
    }

    private fun formatMoney(amount: Double): String {
        return if (amount % 1.0 == 0.0) "${amount.toInt()} ₽" else String.format("%.2f ₽", amount)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

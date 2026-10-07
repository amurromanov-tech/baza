package com.family.base.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.family.base.BaseApplication
import com.family.base.Config
import com.family.base.R
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.model.SubtypeCatalog
import com.family.base.data.remote.ProductLookupService
import com.family.base.data.repository.CatalogRepository
import com.family.base.databinding.ActivityItemDetailBinding
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class ItemDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityItemDetailBinding
    private val TAG = "ItemDetailActivity"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private lateinit var db: AppDatabase
    private lateinit var repository: CatalogRepository
    private lateinit var viewModel: MainViewModel
    private lateinit var tokenStorage: TokenStorage
    private val CAMERA_PERMISSION_REQUEST = 200

    private val productLookupService = ProductLookupService()

    private var newImageBytes: ByteArray? = null
    private var photoUri: Uri? = null
    private var itemId: String? = null

    private var isEditMode = false
    private var currentParentId: String? = null

    private var quantityAnimator: ValueAnimator? = null
    private var priceAnimator: ValueAnimator? = null
    private var totalPriceAnimator: ValueAnimator? = null

    private var expiredPulse: ObjectAnimator? = null
    private var soonPulse: ObjectAnimator? = null

    private var currentEditType: String = "thing"
    private var selectedEditSubtype: String? = null

    private val APPEAR_DURATION = 250L
    private val APPEAR_STAGGER = 50L

    private var currentItem: ItemEntity? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    private val PREFS_NAME = "baza_item_detail_collapse"
    private val KEY_BASIC = "section_basic"
    private val KEY_DETAILS = "section_details"
    private val KEY_DATES = "section_dates"
    private val KEY_DESCRIPTION = "section_description"
    private val KEY_NESTED = "section_nested"

    private val REVISION_EXPIRED_DAYS = 365L

    private val moneyFormat: DecimalFormat by lazy {
        val symbols = DecimalFormatSymbols(Locale.getDefault())
        symbols.groupingSeparator = ' '
        DecimalFormat("#,##0.##", symbols)
    }

    private val nestedItemDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val folderId = result.data?.getStringExtra("navigate_to_folder_id")
            if (!folderId.isNullOrEmpty()) {
                setResult(RESULT_OK, Intent().apply {
                    putExtra("navigate_to_folder_id", folderId)
                })
                finish()
            }
        } else {
            loadNestedContent()
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bitmap = ImageUtils.loadBitmapWithExif(this, it) ?: return@let
                val processedBytes = ImageUtils.processImage(bitmap)
                newImageBytes = processedBytes
                binding.ivPhoto.setImageBitmap(bitmap)
                binding.ivPhoto.visibility = View.VISIBLE
                binding.ivPhotoPlaceholder.visibility = View.GONE
                binding.photoOverlay.visibility = View.VISIBLE
                binding.btnAddPhoto.visibility = View.GONE
                Toast.makeText(this, "Фото выбрано. Не забудь сохранить.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.log(TAG, "Error picking image", e)
                Toast.makeText(this, "Ошибка выбора фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                binding.photoOverlay.visibility = View.VISIBLE
                binding.btnAddPhoto.visibility = View.GONE
                Toast.makeText(this, "Фото сделано. Не забудь сохранить.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.log(TAG, "Error processing camera photo", e)
                Toast.makeText(this, "Ошибка обработки фото", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Фото не сделано", Toast.LENGTH_SHORT).show()
        }
    }

    private val barcodeScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scannedValue = result.data?.getStringExtra("barcode")
            if (!scannedValue.isNullOrEmpty()) {
                binding.etBarcode.setText(scannedValue)
                if (scannedValue.all { it.isDigit() }) showLookupDialog(scannedValue)
                else Toast.makeText(this, "Код: $scannedValue", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            viewModel = BaseApplication.mainViewModel
            tokenStorage = TokenStorage(this)
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
        setupSubtypeEditDropdown()
        setupCollapsibleSections()
        applyCollapsibleState()

        when {
            showHistory -> { isEditMode = false; showHistory(itemId!!) }
            editMode -> { isEditMode = true; showEditMode(itemId!!) }
            else -> { isEditMode = false; showDetails(itemId!!) }
        }
    }

    // ============================================================
    // ГОСТЕВОЙ РЕЖИМ
    // ============================================================
    private fun isGuestMode(): Boolean = tokenStorage.isCurrentUserGuest()

    private fun applyGuestModeIfNeeded() {
        if (!isGuestMode()) return

        binding.btnWriteOff.visibility = View.GONE

        binding.tvActionsTitle.visibility = View.GONE
        binding.btnActionEdit.visibility = View.GONE
        binding.btnActionLend.visibility = View.GONE
        binding.btnActionMove.visibility = View.GONE
        binding.btnActionRevision.visibility = View.GONE
        binding.btnActionArchive.visibility = View.GONE
        binding.btnActionDelete.visibility = View.GONE

        binding.btnSave.visibility = View.GONE
        binding.btnCancelEdit.visibility = View.GONE
        binding.btnAddPhoto.visibility = View.GONE

        binding.btnReturnItem.visibility = View.GONE

        binding.btnAddNested.visibility = View.GONE

        Logger.log(TAG, "Guest mode applied: edit buttons hidden")
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.ivPhoto.setOnClickListener { openFullscreenPhoto() }

        binding.ivPhoto.setOnLongClickListener {
            if (isGuestMode()) return@setOnLongClickListener true
            showPhotoActionsDialog(hasPhoto = true)
            true
        }

        binding.ivPhotoPlaceholder.setOnClickListener {
            if (isGuestMode()) return@setOnClickListener
            showImageSourceDialog()
        }

        binding.ivPhotoPlaceholder.setOnLongClickListener {
            if (isGuestMode()) return@setOnLongClickListener true
            showPhotoActionsDialog(hasPhoto = false)
            true
        }

        binding.btnSave.setOnClickListener { saveChanges() }
        binding.btnCancelEdit.setOnClickListener { finish() }
        binding.btnPlus.setOnClickListener { changeQuantity(+1) }
        binding.btnMinus.setOnClickListener { changeQuantity(-1) }
        binding.btnAddPhoto.setOnClickListener { showImageSourceDialog() }
        binding.btnScanBarcode.setOnClickListener {
            barcodeScannerLauncher.launch(Intent(this, BarcodeScannerActivity::class.java))
        }
        binding.btnActionEdit.setOnClickListener {
            if (!isGuestMode()) itemId?.let { isEditMode = true; showEditMode(it) }
        }
        binding.btnActionLend.setOnClickListener { if (!isGuestMode()) showLendDialog() }
        binding.btnActionMove.setOnClickListener { if (!isGuestMode()) showMoveDialog() }
        binding.btnActionRevision.setOnClickListener { if (!isGuestMode()) showRevisionDialog() }
        binding.btnActionArchive.setOnClickListener { if (!isGuestMode()) showArchiveDialog() }
        binding.btnActionDelete.setOnClickListener { if (!isGuestMode()) showDeleteDialog() }
        binding.btnReturnItem.setOnClickListener { if (!isGuestMode()) showReturnDialog() }

        binding.btnWriteOff.setOnClickListener { if (!isGuestMode()) showWriteOffDialog() }

        binding.chipQuantityView.setOnLongClickListener {
            if (isGuestMode()) return@setOnLongClickListener true
            showQuickQuantityDialog()
            true
        }

        binding.tvSyncStatus.setOnClickListener {
            showSyncStatusToast()
        }

        binding.btnGoToFolder.setOnClickListener {
            val parentId = currentParentId
            if (parentId != null) {
                val resultIntent = Intent().apply {
                    putExtra("navigate_to_folder_id", parentId)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }

        binding.btnAddNested.setOnClickListener {
            if (!isGuestMode()) showAddNestedDialog()
        }

        binding.rgTypeEdit.setOnCheckedChangeListener { _, checkedId ->
            currentEditType = when (checkedId) {
                R.id.rbTypeFood -> "food"
                R.id.rbTypeMedicine -> "medicine"
                R.id.rbTypeThing -> "thing"
                else -> "other"
            }
            selectedEditSubtype = null
            updateSubtypeDropdown(currentEditType)
        }

        // 🆕 Дата покупки — клик по значению открывает datepicker
        binding.tvDatePurchase.setOnClickListener {
            if (!isGuestMode()) {
                itemId?.let { showEditMode(it) }
                binding.etPurchaseDate.post { showPurchaseDatePickerDialog() }
            }
        }

        binding.etPurchaseDate.setOnClickListener { showPurchaseDatePickerDialog() }
    }

    // ============================================================
    // ДЕЙСТВИЯ С ФОТО
    // ============================================================
    private fun showPhotoActionsDialog(hasPhoto: Boolean) {
        val actions = if (hasPhoto) {
            arrayOf(
                "📸 Сделать фото",
                "🖼️ Выбрать из галереи",
                "👁 Открыть на весь экран",
                "❌ Удалить фото"
            )
        } else {
            arrayOf(
                "📸 Сделать фото",
                "🖼️ Выбрать из галереи"
            )
        }

        AlertDialog.Builder(this)
            .setTitle(if (hasPhoto) "Действия с фото" else "Добавить фото")
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    "📸 Сделать фото" ->
                        if (checkCameraPermission()) openCamera() else requestCameraPermission()

                    "🖼️ Выбрать из галереи" ->
                        pickImageLauncher.launch("image/*")

                    "👁 Открыть на весь экран" ->
                        openFullscreenPhoto()

                    "❌ Удалить фото" ->
                        confirmDeletePhoto()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDeletePhoto() {
        AlertDialog.Builder(this)
            .setTitle("Удалить фото?")
            .setMessage("Фото будет удалено с этого предмета. Данные предмета не пострадают.")
            .setPositiveButton("Удалить") { _, _ -> deletePhoto() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deletePhoto() {
        val id = itemId ?: return
        val item = currentItem ?: return

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val f = ImageUtils.getLocalImageFile(this@ItemDetailActivity, id)
                    if (f != null && f.exists()) {
                        val deleted = f.delete()
                        Logger.log(TAG, "Local photo delete: id=$id, file=${f.absolutePath}, deleted=$deleted")
                    } else {
                        Logger.log(TAG, "Local photo delete: id=$id — file not found")
                    }
                }

                val updated = item.copy(
                    imageUrl = null,
                    updatedDate = System.currentTimeMillis(),
                    updatedBy = "user"
                )
                viewModel.updateItemFull(updated)

                newImageBytes = null
                binding.ivPhoto.setImageDrawable(null)
                binding.ivPhoto.visibility = View.GONE
                binding.ivPhotoPlaceholder.visibility = View.VISIBLE
                binding.photoOverlay.visibility = View.VISIBLE

                if (isEditMode) {
                    binding.btnAddPhoto.visibility = View.VISIBLE
                }

                currentItem = updated

                Toast.makeText(this@ItemDetailActivity, "Фото удалено", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleting photo", e)
                Toast.makeText(this@ItemDetailActivity, "Ошибка удаления фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // СВОРАЧИВАНИЕ СЕКЦИЙ
    // ============================================================
    private fun setupCollapsibleSections() {
        binding.sectionBasicHeader.setOnClickListener {
            toggleSection(binding.contentBasicExpandable, binding.arrowBasic, KEY_BASIC)
        }
        binding.sectionDetailsHeader.setOnClickListener {
            toggleSection(binding.contentDetailsExpandable, binding.arrowDetails, KEY_DETAILS)
        }
        binding.sectionDatesHeader.setOnClickListener {
            toggleSection(binding.contentDatesExpandable, binding.arrowDates, KEY_DATES)
        }
        binding.sectionDescriptionHeader.setOnClickListener {
            toggleSection(binding.contentDescriptionExpandable, binding.arrowDescription, KEY_DESCRIPTION)
        }
        binding.sectionNestedHeader.setOnClickListener {
            toggleSection(binding.contentNestedExpandable, binding.arrowNested, KEY_NESTED)
        }
    }

    private fun toggleSection(content: View, arrow: android.widget.TextView, prefKey: String) {
        val isExpanded = content.visibility == View.VISIBLE
        if (isExpanded) {
            content.visibility = View.GONE
            arrow.text = "▶"
            saveCollapsibleState(prefKey, false)
        } else {
            content.visibility = View.VISIBLE
            arrow.text = "▼"
            saveCollapsibleState(prefKey, true)
        }
    }

    private fun applyCollapsibleState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val basicExpanded = prefs.getBoolean(KEY_BASIC, true)
        val detailsExpanded = prefs.getBoolean(KEY_DETAILS, false)
        val datesExpanded = prefs.getBoolean(KEY_DATES, false)
        val descriptionExpanded = prefs.getBoolean(KEY_DESCRIPTION, false)
        val nestedExpanded = prefs.getBoolean(KEY_NESTED, false)

        applyState(binding.contentBasicExpandable, binding.arrowBasic, basicExpanded)
        applyState(binding.contentDetailsExpandable, binding.arrowDetails, detailsExpanded)
        applyState(binding.contentDatesExpandable, binding.arrowDates, datesExpanded)
        applyState(binding.contentDescriptionExpandable, binding.arrowDescription, descriptionExpanded)
        applyState(binding.contentNestedExpandable, binding.arrowNested, nestedExpanded)
    }

    private fun applyState(content: View, arrow: android.widget.TextView, expanded: Boolean) {
        if (expanded) {
            content.visibility = View.VISIBLE
            arrow.text = "▼"
        } else {
            content.visibility = View.GONE
            arrow.text = "▶"
        }
    }

    private fun saveCollapsibleState(key: String, expanded: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, expanded)
            .apply()
    }

    // ============================================================
    // СЕКЦИЯ «📦 ВЛОЖЕННЫЕ»
    // ============================================================
    private fun loadNestedContent() {
        val id = itemId ?: return

        viewModel.getNestedContent(id) { folders, items ->
            binding.nestedContainer.removeAllViews()

            val totalChildren = folders.size + items.size

            if (totalChildren == 0) {
                binding.tvNestedEmpty.visibility = View.VISIBLE
            } else {
                binding.tvNestedEmpty.visibility = View.GONE

                folders.forEach { folder ->
                    val view = bindNestedFolder(folder)
                    binding.nestedContainer.addView(view)
                }
                items.forEach { item ->
                    val view = bindNestedItem(item)
                    binding.nestedContainer.addView(view)
                }
            }

            Logger.log(TAG, "loadNestedContent: folders=${folders.size}, items=${items.size}")
        }
    }

    private fun bindNestedFolder(folder: FolderEntity): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_catalog_entry, binding.nestedContainer, false)

        val icon = view.findViewById<ImageView>(R.id.icon)
        val name = view.findViewById<TextView>(R.id.name)
        val info = view.findViewById<TextView>(R.id.info)
        val expiryInfo = view.findViewById<TextView>(R.id.expiryInfo)
        val lentInfo = view.findViewById<TextView>(R.id.lentInfo)
        val colorBar = view.findViewById<View>(R.id.colorBar)

        val iconFile = ImageUtils.getLocalImageFile(this, "folder_${folder.id}")
        if (iconFile != null && iconFile.exists()) {
            icon.load(iconFile) {
                crossfade(true)
                placeholder(R.drawable.ic_folder_default)
                error(R.drawable.ic_folder_default)
            }
        } else {
            icon.load(R.drawable.ic_folder_default)
        }
        icon.setOnClickListener(null)

        name.text = "📁 ${folder.name}"
        info.text = ""
        expiryInfo.visibility = View.GONE
        lentInfo.visibility = View.GONE

        colorBar.setBackgroundColor(
            ContextCompat.getColor(this, R.color.colorNormal)
        )

        view.setOnClickListener { openNestedFolder(folder) }

        return view
    }

    private fun bindNestedItem(item: ItemEntity): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_catalog_entry, binding.nestedContainer, false)

        val icon = view.findViewById<ImageView>(R.id.icon)
        val name = view.findViewById<TextView>(R.id.name)
        val info = view.findViewById<TextView>(R.id.info)
        val expiryInfo = view.findViewById<TextView>(R.id.expiryInfo)
        val lentInfo = view.findViewById<TextView>(R.id.lentInfo)
        val colorBar = view.findViewById<View>(R.id.colorBar)

        val localFile = ImageUtils.getLocalImageFile(this, item.id)
        if (localFile != null && localFile.exists()) {
            icon.load(localFile) {
                crossfade(true)
                placeholder(R.drawable.ic_item_default)
                error(R.drawable.ic_item_default)
            }
            icon.setOnClickListener {
                val intent = Intent(this, FullscreenImageActivity::class.java).apply {
                    putExtra(FullscreenImageActivity.EXTRA_IMAGE_PATH, localFile.absolutePath)
                    putExtra(FullscreenImageActivity.EXTRA_TITLE, item.name)
                }
                startActivity(intent)
            }
        } else {
            icon.load(R.drawable.ic_item_default)
            icon.setOnClickListener(null)
        }

        val typeEmoji = when (item.itemType) {
            "food" -> "🍎"
            "medicine" -> "💊"
            "thing" -> "📦"
            else -> "🗂"
        }
        name.text = "$typeEmoji ${item.name}"

        val infoParts = mutableListOf<String>()
        if (item.quantity > 1) {
            infoParts.add("×${item.quantity}")
        }
        if (item.price != null && item.price != 0.0) {
            val priceStr = if (item.price % 1.0 == 0.0) {
                item.price.toInt().toString()
            } else {
                String.format("%.2f", item.price)
            }
            infoParts.add("${priceStr} ₽")
        }
        info.text = infoParts.joinToString("  •  ")

        if (item.isExpired) {
            expiryInfo.visibility = View.VISIBLE
            expiryInfo.text = "⚠️ Просрочен"
            expiryInfo.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else if (item.daysUntilExpiry != Int.MAX_VALUE && item.daysUntilExpiry <= 7) {
            expiryInfo.visibility = View.VISIBLE
            expiryInfo.text = "⏰ Осталось ${item.daysUntilExpiry} дн."
            expiryInfo.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        } else {
            expiryInfo.visibility = View.GONE
        }

        if (item.isLent && !item.lentTo.isNullOrEmpty()) {
            lentInfo.visibility = View.VISIBLE
            var text = "🤝 У ${item.lentTo}"
            if (!item.lentNote.isNullOrEmpty()) {
                text += " (${item.lentNote})"
            }
            lentInfo.text = text
        } else {
            lentInfo.visibility = View.GONE
        }

        val colorRes = when {
            item.isLent -> android.R.color.holo_orange_light
            item.isExpired -> R.color.colorExpired
            item.daysUntilExpiry in 0..3 -> R.color.colorWarning
            else -> R.color.colorNormal
        }
        colorBar.setBackgroundColor(ContextCompat.getColor(this, colorRes))

        view.setOnClickListener { openNestedItem(item) }

        return view
    }

    private fun openNestedFolder(folder: FolderEntity) {
        Logger.log(TAG, "openNestedFolder: ${folder.name} (id=${folder.id})")
        val resultIntent = Intent().apply {
            putExtra("navigate_to_folder_id", folder.id)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun openNestedItem(item: ItemEntity) {
        Logger.log(TAG, "openNestedItem: ${item.name} (id=${item.id})")
        val intent = Intent(this, ItemDetailActivity::class.java).apply {
            putExtra("item_id", item.id)
        }
        nestedItemDetailLauncher.launch(intent)
    }

    private fun showAddNestedDialog() {
        val id = itemId ?: return
        val options = arrayOf("📁 Создать папку", "📦 Создать предмет")

        AlertDialog.Builder(this)
            .setTitle("Добавить вложенный")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateNestedFolderDialog(id)
                    1 -> openAddNestedItemActivity(id)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showCreateNestedFolderDialog(parentItemId: String) {
        val editText = EditText(this).apply {
            hint = "Название папки"
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        container.addView(editText)

        AlertDialog.Builder(this)
            .setTitle("📁 Новая вложенная папка")
            .setView(container)
            .setPositiveButton("Создать") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.createFolderInItem(name, parentItemId) { createdFolderId ->
                    if (createdFolderId != null) {
                        Toast.makeText(this, "Папка «$name» создана", Toast.LENGTH_SHORT).show()
                        loadNestedContent()
                    } else {
                        Toast.makeText(this, "Ошибка создания папки", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openAddNestedItemActivity(parentItemId: String) {
        val intent = Intent(this, AddItemActivity::class.java).apply {
            putExtra("parent_item_id", parentItemId)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if (itemId != null) {
            loadNestedContent()
        }
    }

    // ============================================================
    // РЕВИЗИЯ
    // ============================================================
    private fun showRevisionDialog() {
        val id = itemId ?: return
        val item = currentItem ?: return

        AlertDialog.Builder(this)
            .setTitle("Провести ревизию: ${item.name}?")
            .setMessage("Отметить, что предмет проверен сегодня?")
            .setPositiveButton("Да") { _, _ ->
                viewModel.markRevision(id)
                val now = System.currentTimeMillis()
                currentItem = item.copy(lastRevisionDate = now)
                updateRevisionRow(now)
                Toast.makeText(this, "Ревизия отмечена: ${dateFormat.format(Date(now))}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Нет", null)
            .show()
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

    private fun updateRevisionRow(timestamp: Long?) {
        binding.tvDateRevision.text = formatRevisionDate(timestamp)
        val color = if (isRevisionExpired(timestamp)) {
            Color.parseColor("#E57373")
        } else {
            ContextCompat.getColor(this, R.color.dateValue)
        }
        binding.tvDateRevision.setTextColor(color)
    }

    // ============================================================
    // 🆕 ДАТА ПОКУПКИ
    // ============================================================
    private fun updatePurchaseDateRow(timestamp: Long?) {
        if (timestamp == null) {
            binding.tvDatePurchase.text = "— не указана"
            binding.tvDatePurchase.setTextColor(ContextCompat.getColor(this, R.color.dateLabel))
        } else {
            binding.tvDatePurchase.text = dateFormat.format(Date(timestamp))
            binding.tvDatePurchase.setTextColor(ContextCompat.getColor(this, R.color.dateValue))
        }
    }

    private fun showPurchaseDatePickerDialog() {
        val calendar = Calendar.getInstance()
        val current = currentItem?.purchaseDate
        if (current != null) {
            calendar.timeInMillis = current
        } else {
            binding.etPurchaseDate.text.toString().let {
                if (it.isNotEmpty()) {
                    try { dateFormat.parse(it)?.let { d -> calendar.time = d } } catch (e: Exception) {}
                }
            }
        }

        DatePickerDialog(
            this,
            { _, year, month, day ->
                val dateStr = "${day.toString().padStart(2, '0')}.${(month + 1).toString().padStart(2, '0')}.$year"
                binding.etPurchaseDate.setText(dateStr)
            },
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

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

    private fun showWriteOffDialog() {
        val id = itemId ?: return
        val item = currentItem ?: return

        if (item.quantity <= 0) {
            Toast.makeText(this, "Нет предметов для списания", Toast.LENGTH_SHORT).show()
            return
        }

        val stepper = buildStepperLayout(item.quantity)

        AlertDialog.Builder(this)
            .setTitle(getWriteOffTitle(item))
            .setView(stepper.container)
            .setPositiveButton("Далее") { _, _ ->
                val count = stepper.getValue()
                if (count <= 0) {
                    Toast.makeText(this, "Введите число больше 0", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (count > item.quantity) {
                    Toast.makeText(this, "Недостаточно штук (всего ${item.quantity})", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showWriteOffReasonDialog(id, count)
            }
            .setNegativeButton("Отмена") { _, _ -> stopRepeat() }
            .setOnDismissListener { stopRepeat() }
            .show()
    }

    private fun showWriteOffReasonDialog(id: String, count: Int) {
        val item = currentItem
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

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(reasons) { _, which ->
                val itemForBlock = item
                viewModel.writeOffItem(
                    id,
                    count,
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
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }

        val minusBtn = Button(this).apply {
            text = "−"
            textSize = 22f
            minWidth = 0
            minimumWidth = 0
            width = 120
            height = 120
            setPadding(0, 0, 0, 0)
        }

        val editText = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
            gravity = android.view.Gravity.CENTER
            textSize = 20f
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 16
                marginEnd = 16
            }
        }

        val plusBtn = Button(this).apply {
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

        val checkAll = CheckBox(this).apply {
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

    private fun stopRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    // ============================================================
    // БЫСТРОЕ РЕДАКТИРОВАНИЕ КОЛИЧЕСТВА
    // ============================================================
    private fun showQuickQuantityDialog() {
        val id = itemId ?: return
        val item = currentItem ?: return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        val etQty = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(item.quantity.toString())
            hint = "Количество"
            setSelectAllOnFocus(true)
        }
        container.addView(etQty)

        AlertDialog.Builder(this)
            .setTitle("📦 Быстрое изменение количества")
            .setMessage("Текущее: ${item.quantity}")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                val newQty = etQty.text.toString().toIntOrNull()
                if (newQty == null || newQty < 0) {
                    Toast.makeText(this, "Введите корректное число", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newQty == item.quantity) {
                    Toast.makeText(this, "Количество не изменилось", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.updateItemQuantity(id, newQty)
                currentItem = item.copy(quantity = newQty)
                animateQuantity(newQty)
                updatePriceRow(item.price, newQty)
                Toast.makeText(this, "Количество: $newQty", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // ИНДИКАТОР СИНХРОНИЗАЦИИ
    // ============================================================
    private fun updateSyncIndicator(item: ItemEntity) {
        lifecycleScope.launch {
            try {
                val inQueue = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getAllPending().any { it.entityId == item.id }
                }

                val hasLocalFile = withContext(Dispatchers.IO) {
                    val f = ImageUtils.getLocalImageFile(this@ItemDetailActivity, item.id)
                    f != null && f.exists()
                }

                val (emoji, tooltip) = when {
                    inQueue -> Pair("⏳", "В очереди на отправку")
                    !item.imageUrl.isNullOrEmpty() -> Pair("☁️", "Синхронизировано с Яндекс.Диском")
                    hasLocalFile -> Pair("⚠️", "Фото есть локально, но не загружено на Диск")
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

    private fun showSyncStatusToast() {
        val tooltip = binding.tvSyncStatus.tag as? String ?: "Статус неизвестен"
        Toast.makeText(this, tooltip, Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // АНИМАЦИЯ ПОЯВЛЕНИЯ КОНТЕНТА
    // ============================================================
    private fun animateContentAppearance() {
        val views = listOf(
            binding.chipGroupStatus,
            binding.contentContainer.getChildAt(1),
            binding.contentContainer.getChildAt(2),
            binding.contentContainer.getChildAt(3),
            binding.contentContainer.getChildAt(4),
            binding.contentContainer.getChildAt(5),
            binding.contentContainer.getChildAt(6),
            binding.contentContainer.getChildAt(7),
            binding.contentContainer.getChildAt(8),
            binding.contentContainer.getChildAt(9),
            binding.contentContainer.getChildAt(10),
            binding.contentContainer.getChildAt(11),
            binding.contentContainer.getChildAt(12),
            binding.contentContainer.getChildAt(13),
            binding.contentContainer.getChildAt(14),
            binding.contentContainer.getChildAt(15),
            binding.contentContainer.getChildAt(16)
        )

        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 40f

            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * APPEAR_STAGGER)
                .setDuration(APPEAR_DURATION)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    // ============================================================
    // ПУЛЬСАЦИЯ СТАТУСОВ
    // ============================================================
    private fun startStatusPulse() {
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

    private fun stopStatusPulse() {
        expiredPulse?.cancel()
        expiredPulse = null
        binding.chipExpired.alpha = 1f

        soonPulse?.cancel()
        soonPulse = null
        binding.chipSoon.alpha = 1f
    }

    // ============================================================
    // АНИМАЦИИ СЧЁТЧИКОВ
    // ============================================================
    private fun animateQuantity(target: Int) {
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

    private fun updatePriceRow(unitPrice: Double?, quantity: Int) {
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

    // ============================================================
    // ПРОГРЕСС-БАР СРОКА ГОДНОСТИ
    // ============================================================
    private fun updateExpiryProgress(item: ItemEntity) {
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
    // ПОДТИП
    // ============================================================
    private fun setupSubtypeEditDropdown() {
        val view = binding.autoCompleteSubtypeEdit
        view.setOnItemClickListener { _, _, position, _ ->
            val subtypes = SubtypeCatalog.getSubtypes(currentEditType)
            if (position in subtypes.indices) {
                selectedEditSubtype = subtypes[position].key
                Logger.log(TAG, "Subtype chosen: ${selectedEditSubtype}")
            }
        }
    }

    private fun updateSubtypeDropdown(type: String) {
        val subtypes = SubtypeCatalog.getSubtypes(type)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            subtypes.map { it.displayName }
        )
        binding.autoCompleteSubtypeEdit.setAdapter(adapter)
        binding.autoCompleteSubtypeEdit.setText("", false)
    }

    private fun changeQuantity(delta: Int) {
        val currentQty = binding.etQuantity.text.toString().toIntOrNull() ?: 1
        binding.etQuantity.setText((currentQty + delta).coerceAtLeast(0).toString())
    }

    // ============================================================
    // ПРОСМОТР ДЕТАЛЕЙ
    // ============================================================
    private fun showDetails(itemId: String) {
        isEditMode = false
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(itemId) }
                if (item == null) { finish(); return@launch }

                currentItem = item
                currentParentId = item.parentId
                val path = withContext(Dispatchers.IO) { buildItemPath(item) }

                withContext(Dispatchers.Main) {
                    binding.tvTitle.text = item.name
                    showViewMode(true)
                    fillViewFields(item, path)
                    applyChips(item)
                    updateExpiryProgress(item)
                    updateSyncIndicator(item)
                    applyGuestModeIfNeeded()

                    val localFile = ImageUtils.getLocalImageFile(this@ItemDetailActivity, itemId)
                    if (localFile != null && localFile.exists()) {
                        binding.ivPhoto.visibility = View.VISIBLE
                        binding.ivPhotoPlaceholder.visibility = View.GONE
                        binding.photoOverlay.visibility = View.VISIBLE
                        binding.ivPhoto.load(localFile) { crossfade(true) }
                    } else {
                        binding.ivPhoto.visibility = View.GONE
                        binding.ivPhotoPlaceholder.visibility = View.VISIBLE
                        binding.photoOverlay.visibility = View.VISIBLE
                    }

                    if (item.isLent && !item.lentTo.isNullOrEmpty()) {
                        binding.cardLentInfo.visibility = View.VISIBLE
                        binding.tvLentPerson.text = "Кому: ${item.lentTo}"
                        item.lentDate?.let { binding.tvLentDate.text = "Дата: ${dateFormat.format(Date(it))}" }
                        if (!item.lentNote.isNullOrEmpty()) {
                            binding.tvLentNote.visibility = View.VISIBLE
                            binding.tvLentNote.text = "Заметка: ${item.lentNote}"
                        } else binding.tvLentNote.visibility = View.GONE
                    } else binding.cardLentInfo.visibility = View.GONE

                    loadNestedContent()

                    animateContentAppearance()

                    lifecycleScope.launch {
                        delay(500)
                        startStatusPulse()
                    }
                }
            } catch (e: Exception) { Logger.log(TAG, "Error showing details", e) }
        }
    }

    // ============================================================
    // РЕЖИМ РЕДАКТИРОВАНИЯ
    // ============================================================
    private fun showEditMode(itemId: String) {
        isEditMode = true
        stopStatusPulse()
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(itemId) }
                if (item == null) { finish(); return@launch }

                currentItem = item
                currentParentId = item.parentId
                val path = withContext(Dispatchers.IO) { buildItemPath(item) }

                expandAllSections()

                withContext(Dispatchers.Main) {
                    binding.tvTitle.text = "Редактирование: ${item.name}"
                    showViewMode(false)
                    fillEditFields(item, path)
                    applyChips(item)
                    updateExpiryProgress(item)
                    updateSyncIndicator(item)
                    applyGuestModeIfNeeded()

                    val localFile = ImageUtils.getLocalImageFile(this@ItemDetailActivity, itemId)
                    if (localFile != null && localFile.exists()) {
                        binding.ivPhoto.visibility = View.VISIBLE
                        binding.ivPhotoPlaceholder.visibility = View.GONE
                        binding.photoOverlay.visibility = View.VISIBLE
                        binding.ivPhoto.load(localFile) { crossfade(true) }
                        binding.btnAddPhoto.visibility = View.GONE
                    } else {
                        binding.ivPhoto.visibility = View.GONE
                        binding.ivPhotoPlaceholder.visibility = View.VISIBLE
                        binding.photoOverlay.visibility = View.VISIBLE
                        binding.btnAddPhoto.visibility = View.VISIBLE
                    }

                    binding.cardLentInfo.visibility = View.GONE

                    loadNestedContent()

                    startStatusPulse()
                }
            } catch (e: Exception) { Logger.log(TAG, "Error showing edit mode", e) }
        }
    }

    private fun expandAllSections() {
        applyState(binding.contentBasicExpandable, binding.arrowBasic, true)
        applyState(binding.contentDetailsExpandable, binding.arrowDetails, true)
        applyState(binding.contentDatesExpandable, binding.arrowDates, true)
        applyState(binding.contentDescriptionExpandable, binding.arrowDescription, true)
        applyState(binding.contentNestedExpandable, binding.arrowNested, true)
    }

    // ============================================================
    // ПЕРЕКЛЮЧЕНИЕ VIEW / EDIT
    // ============================================================
    private fun showViewMode(isView: Boolean) {
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

        // 🆕 Дата покупки: view — текстом, edit — полем ввода
        binding.tvDatePurchase.visibility = viewVisibility
        binding.tilPurchaseDate.visibility = editVisibility

        binding.editButtonsLayout.visibility = editVisibility

        binding.btnAddNested.visibility = editVisibility
    }

    private fun fillViewFields(item: ItemEntity, path: String) {
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

    private fun fillEditFields(item: ItemEntity, path: String) {
        binding.etName.setText(item.name)
        binding.etName.isEnabled = true

        binding.etQuantity.setText(item.quantity.toString())
        binding.etQuantity.isEnabled = true

        currentEditType = item.itemType ?: "thing"
        when (currentEditType) {
            "food" -> binding.rgTypeEdit.check(R.id.rbTypeFood)
            "medicine" -> binding.rgTypeEdit.check(R.id.rbTypeMedicine)
            "thing" -> binding.rgTypeEdit.check(R.id.rbTypeThing)
            else -> binding.rgTypeEdit.check(R.id.rbTypeOther)
        }

        selectedEditSubtype = item.itemSubtype
        updateSubtypeDropdown(currentEditType)
        if (!item.itemSubtype.isNullOrEmpty()) {
            val subtypeDisplay = SubtypeCatalog.getDisplayName(currentEditType, item.itemSubtype)
            if (subtypeDisplay != null) {
                binding.autoCompleteSubtypeEdit.setText(subtypeDisplay, false)
            }
        }

        binding.etBarcode.setText(item.barcode ?: "")
        binding.etBarcode.isEnabled = true

        item.expiryDate?.let { binding.etExpiry.setText(dateFormat.format(Date(it))) }
        binding.etExpiry.isEnabled = true
        binding.etExpiry.setOnClickListener { showDatePickerDialog() }

        // 🆕 Дата покупки: заполняем поле
        item.purchaseDate?.let {
            binding.etPurchaseDate.setText(dateFormat.format(Date(it)))
        } ?: binding.etPurchaseDate.setText("")

        binding.etPrice.setText(if (item.price != null && item.price != 0.0) item.price.toString() else "")

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
    // ПОСТРОЕНИЕ ПУТИ С УЧЁТОМ ВЛОЖЕННЫХ ПРЕДМЕТОВ
    // ============================================================
    private suspend fun buildItemPath(item: ItemEntity): String {
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

                if (!folder.parentItemId.isNullOrEmpty()) {
                    segments.add("📁 ${folder.name}")
                    currentParentItemId = folder.parentItemId
                    currentFolderId = folder.parentId
                    continue
                }

                segments.add("📁 ${folder.name}")
                currentFolderId = folder.parentId
                currentParentItemId = folder.parentItemId
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
    // ЧИПЫ
    // ============================================================
    private fun applyChips(item: ItemEntity) {
        when (item.itemType) {
            "food" -> {
                binding.chipType.text = "🍎 Еда"
                binding.chipType.setChipBackgroundColorResource(R.color.chipFoodBg)
                binding.chipType.setTextColor(ContextCompat.getColor(this, R.color.chipFoodText))
            }
            "medicine" -> {
                binding.chipType.text = "💊 Лекарство"
                binding.chipType.setChipBackgroundColorResource(R.color.chipMedicineBg)
                binding.chipType.setTextColor(ContextCompat.getColor(this, R.color.chipMedicineText))
            }
            "thing" -> {
                binding.chipType.text = "📦 Предмет"
                binding.chipType.setChipBackgroundColorResource(R.color.chipThingBg)
                binding.chipType.setTextColor(ContextCompat.getColor(this, R.color.chipThingText))
            }
            else -> {
                binding.chipType.text = "🗂 Другое"
                binding.chipType.setChipBackgroundColorResource(R.color.chipOtherBg)
                binding.chipType.setTextColor(ContextCompat.getColor(this, R.color.chipOtherText))
            }
        }
        binding.chipType.visibility = View.VISIBLE

        binding.chipExpired.visibility = if (item.isExpired) View.VISIBLE else View.GONE

        if (!item.isExpired && item.daysUntilExpiry in 0..3) {
            binding.chipSoon.visibility = View.VISIBLE
            binding.chipSoon.text = "⚠️ Осталось ${item.daysUntilExpiry} дн."
        } else binding.chipSoon.visibility = View.GONE

        binding.chipLent.visibility = if (item.isLent && !item.lentTo.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    // ============================================================
    // ИСТОРИЯ
    // ============================================================
    private fun showHistory(itemId: String) {
        isEditMode = false
        stopStatusPulse()
        lifecycleScope.launch {
            try {
                val history = withContext(Dispatchers.IO) { db.historyDao().getHistoryForItem(itemId) }
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
                            if (entry.oldValue != null && entry.newValue != null) append(": ${entry.oldValue} → ${entry.newValue}")
                        }
                    }

                    binding.tvDescriptionView.text = historyText
                    binding.tvDescriptionView.visibility = View.VISIBLE
                    binding.cardDescription.visibility = View.VISIBLE
                    binding.tvDescriptionTitle.visibility = View.VISIBLE
                }
            } catch (e: Exception) { Logger.log(TAG, "Error showing history", e) }
        }
    }

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

    private fun showImageSourceDialog() {
        if (isGuestMode()) return
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
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val photoFile = File(cacheDir, "IMG_$ts.jpg")
            photoUri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
            takePhotoLauncher.launch(photoUri)
        } catch (e: Exception) {
            Logger.log(TAG, "Error opening camera", e)
            Toast.makeText(this, "Ошибка открытия камеры", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
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

    private fun showLookupDialog(barcode: String) {
        AlertDialog.Builder(this)
            .setTitle("🔍 Найти товар?")
            .setMessage("Найден штрих-код: $barcode\n\nИскать информацию?")
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
                    val desc = buildString {
                        if (!product.brand.isNullOrEmpty() && product.brand != displayName) append("Бренд: ${product.brand}\n")
                        if (!product.category.isNullOrEmpty()) append("Категория: ${product.category}\n")
                        if (!product.description.isNullOrEmpty()) append("\n${product.description}")
                        if (product.source != null) append("\n\nИсточник: ${product.source}")
                    }.trim()

                    AlertDialog.Builder(this@ItemDetailActivity)
                        .setTitle("✅ Найдено: ${displayName ?: barcode}")
                        .setMessage("Что заполнить?\n\nНазвание: ${displayName ?: "—"}\n\nОписание: ${desc.ifEmpty { "—" }}")
                        .setPositiveButton("Заполнить всё") { _, _ ->
                            displayName?.let { binding.etName.setText(it) }
                            if (desc.isNotEmpty()) binding.etDescription.setText(desc)
                        }
                        .setNeutralButton("Только название") { _, _ -> displayName?.let { binding.etName.setText(it) } }
                        .setNegativeButton("Отмена", null)
                        .show()
                } else Toast.makeText(this@ItemDetailActivity, "Товар не найден", Toast.LENGTH_LONG).show()
            } catch (e: Exception) { Logger.log(TAG, "Error looking up product", e) }
        }
    }

    private fun showLendDialog() {
        val id = itemId ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        val etPerson = EditText(this).apply { hint = "Кому выдать (имя)" }
        val etNote = EditText(this).apply { hint = "Заметка (необязательно)" }
        container.addView(etPerson); container.addView(etNote)

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
            .setMessage("Предмет вернётся в базу.")
            .setPositiveButton("Вернуть") { _, _ ->
                viewModel.returnItem(id)
                Toast.makeText(this, "Предмет возвращён", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // B-6: ПЕРЕМЕЩЕНИЕ С ВЫБОРОМ КОЛИЧЕСТВА (полное дерево, пара id)
    // ============================================================
    private fun showMoveDialog() {
        val id = itemId ?: return
        val item = currentItem

        if (item == null) {
            Toast.makeText(this, "Предмет не найден", Toast.LENGTH_SHORT).show()
            return
        }

        if (item.quantity <= 1) {
            openMoveFolderPicker(id, 1)
            return
        }

        val stepper = buildStepperLayoutWithAll(item.quantity)

        AlertDialog.Builder(this)
            .setTitle(getMoveTitle(item))
            .setView(stepper.container)
            .setPositiveButton("Далее") { _, _ ->
                val count = stepper.getValue()
                if (count <= 0) {
                    Toast.makeText(this, "Введите число больше 0", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (count > item.quantity) {
                    Toast.makeText(this, "Недостаточно штук (всего ${item.quantity})", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                openMoveFolderPicker(id, count)
            }
            .setNegativeButton("Отмена") { _, _ -> stopRepeat() }
            .setOnDismissListener { stopRepeat() }
            .show()
    }

    private fun openMoveFolderPicker(id: String, count: Int) {
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(id) }
                if (item == null) {
                    Toast.makeText(this@ItemDetailActivity, "Предмет не найден", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                Logger.log(
                    TAG,
                    "openMoveFolderPicker: item=${item.name}, count=$count, " +
                        "current parentId=${item.parentId}, current parentItemId=${item.parentItemId}"
                )

                MoveDialogHelper.show(
                    context = this@ItemDetailActivity,
                    scope = lifecycleScope,
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
                            Toast.makeText(this@ItemDetailActivity, "Предмет уже здесь", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.splitAndMoveItem(id, count, newParentId, newParentItemId)
                            Toast.makeText(this@ItemDetailActivity, "Перемещено $count шт.", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                )
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing move dialog", e)
            }
        }
    }

    // ============================================================
    // ДИАЛОГ «НЕЛЬЗЯ АРХИВИРОВАТЬ»
    // ============================================================
    private fun showArchiveBlockedDialog(item: ItemEntity, message: String) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Нельзя архивировать")
            .setMessage(message)
            .setPositiveButton("Отвязать всех детей") { _, _ ->
                viewModel.detachAllChildren(item.id) { count ->
                    Toast.makeText(
                        this,
                        "Отвязано: $count. Теперь можно архивировать.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // АРХИВАЦИЯ С ПРОВЕРКОЙ ДЕТЕЙ
    // ============================================================
    private fun showArchiveDialog() {
        val id = itemId ?: return

        val reasons = arrayOf(
            "🧴 Израсходовано", "🍽 Съедено", "🔧 Сломано", "🗑 Выброшено",
            "🎁 Подарено", "💰 Продано", "⏰ Истёк срок", "📦 Другое"
        )
        val reasonKeys = arrayOf(
            "used_up", "eaten", "broken", "thrown",
            "gifted", "sold", "expired", "other"
        )

        AlertDialog.Builder(this)
            .setTitle("📦 В архив: ${binding.tvTitle.text}")
            .setItems(reasons) { _, which ->
                val itemForBlock = currentItem
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

    // ============================================================
    // УДАЛЕНИЕ С ПРОВЕРКОЙ ДЕТЕЙ
    // ============================================================
    private fun showDeleteDialog() {
        val id = itemId ?: return

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

                AlertDialog.Builder(this)
                    .setTitle("⚠️ Нельзя удалить")
                    .setMessage(childInfo)
                    .setPositiveButton("Отвязать всех детей") { _, _ ->
                        viewModel.detachAllChildren(id) { count ->
                            Toast.makeText(
                                this,
                                "Отвязано: $count. Теперь можно удалить.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("🗑 Удалить предмет?")
                    .setMessage("Это действие нельзя отменить. Возможно, лучше в архив?")
                    .setPositiveButton("Удалить") { _, _ ->
                        viewModel.deleteItem(id)
                        Toast.makeText(this, "Предмет удалён", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .setNeutralButton("В архив") { _, _ -> showArchiveDialog() }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
    }

    private fun saveChanges() {
        val id = itemId ?: return
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(id) }
                if (item == null) { finish(); return@launch }

                val name = binding.etName.text.toString().trim()
                if (name.isEmpty()) { Toast.makeText(this@ItemDetailActivity, "Введите название", Toast.LENGTH_SHORT).show(); return@launch }

                val quantity = binding.etQuantity.text.toString().toIntOrNull() ?: 1
                val description = binding.etDescription.text.toString()
                val expiryDate = parseDate(binding.etExpiry.text.toString())
                val price = binding.etPrice.text.toString().toDoubleOrNull()
                val barcode = binding.etBarcode.text.toString().trim().ifEmpty { null }

                // 🆕 Дата покупки: парсим из поля (пустое → null)
                val purchaseDateText = binding.etPurchaseDate.text.toString().trim()
                val purchaseDate = if (purchaseDateText.isEmpty()) null else parseDate(purchaseDateText)

                val newItemType = when (binding.rgTypeEdit.checkedRadioButtonId) {
                    R.id.rbTypeFood -> "food"
                    R.id.rbTypeMedicine -> "medicine"
                    R.id.rbTypeThing -> "thing"
                    else -> "other"
                }

                val newItemSubtype = selectedEditSubtype
                if (newItemSubtype.isNullOrEmpty()) {
                    Toast.makeText(this@ItemDetailActivity, "Выберите подтип", Toast.LENGTH_SHORT).show()
                    binding.tilSubtypeEdit.error = "Выберите подтип"
                    binding.tilSubtypeEdit.requestFocus()
                    return@launch
                }
                binding.tilSubtypeEdit.error = null

                Logger.log(
                    TAG,
                    "Save: type=$newItemType, subtype=$newItemSubtype, hasNewImage=${newImageBytes != null}, " +
                        "purchaseDate=$purchaseDate"
                )

                val newImageUrl = if (newImageBytes != null) null else item.imageUrl

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

                newImageBytes?.let { bytes ->
                    withContext(Dispatchers.IO) {
                        ImageUtils.saveImageLocally(applicationContext, id, bytes)
                    }
                }

                viewModel.updateItemFull(updated)

                withContext(Dispatchers.IO) {
                    db.historyDao().insertEntry(
                        HistoryEntry(
                            itemId = id, action = "update",
                            oldValue = "Тип: ${item.itemType}, подтип: ${item.itemSubtype}",
                            newValue = "Тип: $newItemType, подтип: $newItemSubtype",
                            changedBy = "user"
                        )
                    )
                }

                Toast.makeText(this@ItemDetailActivity, "Сохранено", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Logger.log(TAG, "Error saving changes", e)
                Toast.makeText(this@ItemDetailActivity, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun parseDate(dateStr: String): Long? = try { dateFormat.parse(dateStr)?.time } catch (e: Exception) { null }

    override fun onDestroy() {
        super.onDestroy()
        quantityAnimator?.cancel()
        priceAnimator?.cancel()
        totalPriceAnimator?.cancel()
        stopStatusPulse()
        stopRepeat()
        Logger.log(TAG, "onDestroy called")
    }
}

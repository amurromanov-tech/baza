package com.family.base.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.family.base.BaseApplication
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.repository.CatalogRepository
import com.family.base.databinding.ActivityItemDetailBinding
import com.family.base.ui.controller.ItemActionDialogs
import com.family.base.ui.controller.ItemEditController
import com.family.base.ui.controller.ItemNestedController
import com.family.base.ui.controller.ItemPhotoController
import com.family.base.ui.controller.ItemViewController
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ItemDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityItemDetailBinding
    private val TAG = "ItemDetailActivity"

    private lateinit var db: AppDatabase
    private lateinit var repository: CatalogRepository
    private lateinit var viewModel: MainViewModel

    private var itemId: String? = null
    private var isEditMode = false
    private var currentItem: ItemEntity? = null
    private var currentParentId: String? = null

    private var currentEditType: String = "thing"
    private var selectedEditSubtype: String? = null

    // ============================================================
    // КОНТРОЛЛЕРЫ
    // ============================================================

    private lateinit var viewController: ItemViewController
    private lateinit var editController: ItemEditController
    private lateinit var photoController: ItemPhotoController
    private lateinit var actionDialogs: ItemActionDialogs
    private lateinit var nestedController: ItemNestedController

    // ============================================================
    // COLLAPSE PREFS
    // ============================================================

    private val PREFS_NAME = "baza_item_detail_collapse"
    private val KEY_BASIC = "section_basic"
    private val KEY_DETAILS = "section_details"
    private val KEY_DATES = "section_dates"
    private val KEY_DESCRIPTION = "section_description"
    private val KEY_NESTED = "section_nested"

    // ============================================================
    // LAUNCHERS
    // ============================================================

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
            nestedController.loadNestedContent()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        photoController.onImagePicked(uri)
    }

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        photoController.onPhotoTaken(success)
    }

    private val barcodeScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scannedValue = result.data?.getStringExtra("barcode")
            if (!scannedValue.isNullOrEmpty()) {
                binding.etBarcode.setText(scannedValue)
                if (scannedValue.all { it.isDigit() }) {
                    actionDialogs.showLookupDialog(scannedValue)
                } else {
                    Toast.makeText(this, "Код: $scannedValue", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================

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

        initControllers()
        setupListeners()
        setupCollapsibleSections()
        applyCollapsibleState()

        when {
            showHistory -> {
                isEditMode = false
                viewController.showHistory(itemId!!)
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
    }

    private fun initControllers() {
        viewController = ItemViewController(
            activity = this,
            binding = binding,
            db = db,
            itemIdProvider = { itemId },
            currentItemProvider = { currentItem },
            onCurrentItemChanged = { item -> currentItem = item },
            onEditModeChanged = { mode -> isEditMode = mode },
            onShowDetails = { id -> showDetails(id) },
            onShowEditMode = { id -> showEditMode(id) }
        )

        editController = ItemEditController(
            activity = this,
            binding = binding,
            db = db,
            viewModel = viewModel,
            itemIdProvider = { itemId },
            currentItemProvider = { currentItem },
            currentEditTypeProvider = { currentEditType },
            selectedEditSubtypeProvider = { selectedEditSubtype },
            onCurrentItemChanged = { item -> currentItem = item },
            onFinish = { finish() }
        )

        photoController = ItemPhotoController(
            activity = this,
            binding = binding,
            db = db,
            viewModel = viewModel,
            itemIdProvider = { itemId },
            currentItemProvider = { currentItem },
            isEditModeProvider = { isEditMode },
            onCurrentItemChanged = { item -> currentItem = item },
            onPhotoChanged = { /* no-op */ }
        )
        photoController.registerLaunchers(pickImageLauncher, takePhotoLauncher)

        actionDialogs = ItemActionDialogs(
            activity = this,
            binding = binding,
            db = db,
            viewModel = viewModel,
            itemIdProvider = { itemId },
            currentItemProvider = { currentItem },
            onCurrentItemChanged = { item -> currentItem = item },
            onFinish = { finish() },
            onLoadNestedContent = { nestedController.loadNestedContent() },
            onOpenAddItemActivity = { parentItemId ->
                val intent = Intent(this, AddItemActivity::class.java).apply {
                    putExtra("parent_item_id", parentItemId)
                }
                startActivity(intent)
            }
        )

        nestedController = ItemNestedController(
            activity = this,
            binding = binding,
            viewModel = viewModel,
            itemIdProvider = { itemId },
            onNavigateToFolder = { folderId ->
                setResult(RESULT_OK, Intent().apply {
                    putExtra("navigate_to_folder_id", folderId)
                })
                finish()
            },
            onOpenNestedItem = { nestedId ->
                val intent = Intent(this, ItemDetailActivity::class.java).apply {
                    putExtra("item_id", nestedId)
                }
                nestedItemDetailLauncher.launch(intent)
            }
        )
    }

    // ============================================================
    // LISTENERS
    // ============================================================

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.ivPhoto.setOnClickListener { photoController.openFullscreenPhoto() }

        binding.ivPhoto.setOnLongClickListener {
            photoController.showPhotoActionsDialog(hasPhoto = true)
            true
        }

        binding.ivPhotoPlaceholder.setOnClickListener {
            photoController.showImageSourceDialog()
        }

        binding.ivPhotoPlaceholder.setOnLongClickListener {
            photoController.showPhotoActionsDialog(hasPhoto = false)
            true
        }

        binding.btnSave.setOnClickListener { saveChanges() }
        binding.btnCancelEdit.setOnClickListener { finish() }
        binding.btnPlus.setOnClickListener { changeQuantity(+1) }
        binding.btnMinus.setOnClickListener { changeQuantity(-1) }
        binding.btnAddPhoto.setOnClickListener { photoController.showImageSourceDialog() }
        binding.btnScanBarcode.setOnClickListener {
            barcodeScannerLauncher.launch(Intent(this, BarcodeScannerActivity::class.java))
        }
        binding.btnActionEdit.setOnClickListener {
            itemId?.let { isEditMode = true; showEditMode(it) }
        }
        binding.btnActionLend.setOnClickListener { actionDialogs.showLendDialog() }
        binding.btnActionMove.setOnClickListener { actionDialogs.showMoveDialog() }
        binding.btnActionRevision.setOnClickListener {
            actionDialogs.showRevisionDialog { timestamp ->
                currentItem = currentItem?.copy(lastRevisionDate = timestamp)
                viewController.updateRevisionRow(timestamp)
            }
        }
        binding.btnActionArchive.setOnClickListener {
            actionDialogs.showArchiveDialog()
        }
        binding.btnActionDelete.setOnClickListener {
            actionDialogs.showDeleteDialog { actionDialogs.showArchiveDialog() }
        }
        binding.btnReturnItem.setOnClickListener {
            actionDialogs.showReturnDialog()
        }

        binding.btnWriteOff.setOnClickListener {
            actionDialogs.showWriteOffDialog()
        }

        binding.chipQuantityView.setOnLongClickListener {
            actionDialogs.showQuickQuantityDialog()
            true
        }

        binding.tvSyncStatus.setOnClickListener {
            viewController.showSyncStatusToast()
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
            actionDialogs.showAddNestedDialog()
        }

        binding.rgTypeEdit.setOnCheckedChangeListener { _, checkedId ->
            currentEditType = when (checkedId) {
                R.id.rbTypeFood -> "food"
                R.id.rbTypeMedicine -> "medicine"
                R.id.rbTypeThing -> "thing"
                else -> "other"
            }
            // 🆕 НЕ сбрасываем selectedEditSubtype — валидация в saveChanges()
            // отсечёт старый подтип, если он не подходит к новому типу.
            editController.updateSubtypeDropdown(currentEditType)
        }

        binding.tvDatePurchase.setOnClickListener {
            itemId?.let { showEditMode(it) }
            binding.etPurchaseDate.post { editController.showPurchaseDatePickerDialog() }
        }

        binding.etPurchaseDate.setOnClickListener { editController.showPurchaseDatePickerDialog() }

        editController.setupSubtypeEditDropdown { key -> selectedEditSubtype = key }
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

        viewController.applySectionState(binding.contentBasicExpandable, binding.arrowBasic, basicExpanded)
        viewController.applySectionState(binding.contentDetailsExpandable, binding.arrowDetails, detailsExpanded)
        viewController.applySectionState(binding.contentDatesExpandable, binding.arrowDates, datesExpanded)
        viewController.applySectionState(binding.contentDescriptionExpandable, binding.arrowDescription, descriptionExpanded)
        viewController.applySectionState(binding.contentNestedExpandable, binding.arrowNested, nestedExpanded)
    }

    private fun saveCollapsibleState(key: String, expanded: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, expanded)
            .apply()
    }

    // ============================================================
    // ONRESUME / ONDESTROY
    // ============================================================

    override fun onResume() {
        super.onResume()
        if (itemId != null) {
            nestedController.loadNestedContent()

            if (!isEditMode) {
                val localFile = ImageUtils.getLocalImageFile(this, itemId!!)
                if (localFile == null || !localFile.exists()) {
                    photoController.loadPhotoInBackground(itemId!!)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewController.cleanup()
        actionDialogs.stopRepeat()
        Logger.log(TAG, "onDestroy called")
    }

    // ============================================================
    // ПОКАЗ VIEW / EDIT
    // ============================================================

    private fun showDetails(id: String) {
        isEditMode = false
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(id) }
                if (item == null) {
                    finish()
                    return@launch
                }

                currentItem = item
                currentParentId = item.parentId
                val path = withContext(Dispatchers.IO) { viewController.buildItemPath(item) }

                withContext(Dispatchers.Main) {
                    binding.tvTitle.text = item.name
                    viewController.showViewMode(true)
                    viewController.fillViewFields(item, path)
                    viewController.applyChips(item)
                    viewController.updateExpiryProgress(item)
                    viewController.updateSyncIndicator(item)

                    photoController.showLocalPhotoOrPlaceholder(id)
                    val localFile = ImageUtils.getLocalImageFile(this@ItemDetailActivity, id)
                    if (localFile == null || !localFile.exists()) {
                        photoController.loadPhotoInBackground(id)
                    }

                    if (item.isLent && !item.lentTo.isNullOrEmpty()) {
                        binding.cardLentInfo.visibility = View.VISIBLE
                        binding.tvLentPerson.text = "Кому: ${item.lentTo}"
                        item.lentDate?.let {
                            binding.tvLentDate.text = "Дата: ${
                                SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(it))
                            }"
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

                    nestedController.loadNestedContent()
                    viewController.animateContentAppearance()

                    lifecycleScope.launch {
                        delay(500)
                        viewController.startStatusPulse()
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing details", e)
            }
        }
    }

    private fun showEditMode(id: String) {
        isEditMode = true
        viewController.stopStatusPulse()
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(id) }
                if (item == null) {
                    finish()
                    return@launch
                }

                currentItem = item
                currentParentId = item.parentId
                val path = withContext(Dispatchers.IO) { viewController.buildItemPath(item) }

                viewController.expandAllSections()

                withContext(Dispatchers.Main) {
                    binding.tvTitle.text = "Редактирование: ${item.name}"
                    viewController.showViewMode(false)
                    currentEditType = item.itemType ?: "thing"
                    selectedEditSubtype = item.itemSubtype
                    viewController.fillEditFields(
                        item = item,
                        path = path,
                        onTypeChecked = { type -> currentEditType = type },
                        onSubtypeDropdownUpdate = { type -> editController.updateSubtypeDropdown(type) },
                        onExpiryDateClick = { editController.showDatePickerDialog() }
                    )
                    viewController.applyChips(item)
                    viewController.updateExpiryProgress(item)
                    viewController.updateSyncIndicator(item)

                    photoController.showLocalPhotoOrPlaceholder(id)
                    val localFile = ImageUtils.getLocalImageFile(this@ItemDetailActivity, id)
                    if (localFile == null || !localFile.exists()) {
                        photoController.loadPhotoInBackground(id)
                        binding.btnAddPhoto.visibility = View.VISIBLE
                    } else {
                        binding.btnAddPhoto.visibility = View.GONE
                    }

                    binding.cardLentInfo.visibility = View.GONE
                    nestedController.loadNestedContent()
                    viewController.startStatusPulse()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing edit mode", e)
            }
        }
    }

    // ============================================================
    // СОХРАНЕНИЕ
    // ============================================================

    private fun saveChanges() {
        photoController.savePendingPhoto()
        editController.saveChanges()
    }

    private fun changeQuantity(delta: Int) {
        val currentQty = binding.etQuantity.text.toString().toIntOrNull() ?: 1
        binding.etQuantity.setText((currentQty + delta).coerceAtLeast(0).toString())
    }

    // ============================================================
    // PERMISSIONS
    // ============================================================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        photoController.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}

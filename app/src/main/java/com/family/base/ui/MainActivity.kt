package com.family.base.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.BaseApplication
import com.family.base.R
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.databinding.ActivityMainBinding
import com.family.base.ui.adapter.CatalogAdapter
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.ui.viewmodel.SyncPhase
import com.family.base.ui.viewmodel.SyncProgress
import com.family.base.util.AppLifecycleObserver
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: CatalogAdapter
    private lateinit var tokenStorage: TokenStorage
    private var syncRotationAnim: RotateAnimation? = null
    private var pathTextView: TextView? = null

    private val TAG = "MainActivity"
    private val db by lazy { AppDatabase.getInstance(this) }

    private var newFolderImageBytes: ByteArray? = null
    private var currentFolderForImage: FolderEntity? = null

    private var hideProgressJob: Job? = null

    // ===== ДЛЯ LONG-PRESS СТЕППЕРА =====
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    private val pickFolderImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bitmap = ImageUtils.loadBitmapWithExif(this, it) ?: return@let
                val processedBytes = ImageUtils.processImage(bitmap)
                newFolderImageBytes = processedBytes
                Toast.makeText(this, "Изображение выбрано, оно будет загружено после создания папки", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.log(TAG, "Error picking folder image", e)
                Toast.makeText(this, "Ошибка выбора фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickExistingFolderImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bitmap = ImageUtils.loadBitmapWithExif(this, it) ?: return@let
                val processedBytes = ImageUtils.processImage(bitmap)
                val folder = currentFolderForImage
                if (folder != null) {
                    lifecycleScope.launch {
                        try {
                            ImageUtils.saveImageLocally(applicationContext, "folder_${folder.id}", processedBytes)
                            val updated = folder.copy(iconUrl = "folder_${folder.id}.jpg")
                            withContext(Dispatchers.IO) { db.folderDao().updateFolder(updated) }
                            viewModel.uploadFolderImage(folder.id, processedBytes)
                            viewModel.loadContents()
                            Toast.makeText(this@MainActivity, "Иконка обновлена", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Logger.log(TAG, "Error updating folder image", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error picking folder image", e)
            }
        }
    }

    private val barcodeSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanned = result.data?.getStringExtra("barcode")
            if (!scanned.isNullOrEmpty()) {
                searchByBarcode(scanned)
            }
        }
    }

    private val itemDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val folderId = result.data?.getStringExtra("navigate_to_folder_id")
            if (!folderId.isNullOrEmpty()) {
                Logger.log(TAG, "Navigate to folder from ItemDetail: $folderId")
                viewModel.navigateToFolder(folderId)
            }
        }
    }

    private val checkPreviewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val folderId = result.data?.getStringExtra("navigate_to_folder_id")
            if (!folderId.isNullOrEmpty()) {
                Logger.log(TAG, "Navigate to purchase folder: $folderId")
                Toast.makeText(this, "Открываю папку с покупкой…", Toast.LENGTH_SHORT).show()
                viewModel.navigateToFolder(folderId)
            }
        } else {
            Logger.log(TAG, "Check preview cancelled or failed")
        }
    }

    private val connectFamilyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.loadContents()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== MainActivity onCreate START ===")

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        Logger.init(applicationContext)

        tokenStorage = TokenStorage(this)

        viewModel = BaseApplication.mainViewModel

        try {
            val appLifecycleObserver = AppLifecycleObserver(viewModel)
            ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        } catch (e: Exception) {
            Logger.log(TAG, "Error registering AppLifecycleObserver", e)
        }

        val publicKey = tokenStorage.getPublicKey()
        if (publicKey == null) {
            connectFamilyLauncher.launch(Intent(this, ConnectFamilyActivity::class.java))
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        pathTextView = TextView(this).apply {
            text = "BAZA"
            textSize = 18f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
        }
        supportActionBar?.setCustomView(pathTextView)
        supportActionBar?.displayOptions = ActionBar.DISPLAY_SHOW_CUSTOM

        adapter = CatalogAdapter(
            onFolderClick = { folder -> navigateToFolder(folder) },
            onItemClick = { item -> openItemDetail(item) },
            onFolderLongClick = { folder -> showFolderContextMenu(folder) },
            onItemLongClick = { item -> showItemContextMenu(item) }
        )
        binding.rvCatalog.layoutManager = LinearLayoutManager(this)
        binding.rvCatalog.adapter = adapter

        viewModel.currentEntries.observe(this) { entries ->
            adapter.submitList(entries)
            updatePathTitle()
        }
        viewModel.syncStatus.observe(this) { updateSyncStatusIcon(it) }
        viewModel.searchQueryLiveData.observe(this) { updateSearchIcon(it) }

        viewModel.syncProgress.observe(this) { progress ->
            if (progress != null) {
                updateSyncProgressCard(progress)
            } else {
                hideSyncProgressCard()
            }
        }

        binding.btnAddFolder.setOnClickListener { showCreateFolderDialog() }
        binding.btnAddItem.setOnClickListener {
            val intent = Intent(this, AddItemActivity::class.java)
            intent.putExtra("parent_id", viewModel.getCurrentFolderId())
            startActivity(intent)
        }
        binding.btnHome.setOnClickListener { viewModel.navigateToRoot() }
        binding.btnUp.setOnClickListener { viewModel.navigateUp() }
        binding.btnSearch.setOnClickListener { showSearchDialog() }
        binding.btnScanSearch.setOnClickListener {
            barcodeSearchLauncher.launch(Intent(this, BarcodeScannerActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnScanCheck.setOnClickListener {
            Logger.log(TAG, "Scan check clicked")
            checkPreviewLauncher.launch(Intent(this, CheckScannerActivity::class.java))
        }

        viewModel.navigateToFolder(null)
        updateSearchIcon(viewModel.searchQueryLiveData.value)

        // ===== ЗАЩИТА ОТ СЛУЧАЙНОГО ВЫХОДА =====
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Выйти из приложения?")
                    .setMessage("Вы уверены, что хотите закрыть БАЗУ?")
                    .setPositiveButton("Выйти") { _, _ ->
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadContents()
    }

    override fun onPause() {
        super.onPause()
        stopSyncAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSyncAnimation()
        hideProgressJob?.cancel()
        stopRepeat()
    }

    // ============================================================
    // ПЛАШКА ПРОГРЕССА СИНХРОНИЗАЦИИ
    // ============================================================
    private fun updateSyncProgressCard(progress: SyncProgress) {
        hideProgressJob?.cancel()

        val icon = when (progress.phase) {
            SyncPhase.SENDING -> "📤"
            SyncPhase.DOWNLOADING -> "📥"
            SyncPhase.UPLOADING_PHOTOS -> "📷"
            SyncPhase.DOWNLOADING_PHOTOS -> "🖼️"
            SyncPhase.DONE -> "✅"
        }
        binding.tvSyncPhaseIcon.text = icon

        binding.tvSyncPhaseText.text = progress.message

        if (progress.total > 0) {
            binding.tvSyncCounter.text = "${progress.current} / ${progress.total}"
            binding.tvSyncCounter.visibility = View.VISIBLE
        } else {
            binding.tvSyncCounter.visibility = View.GONE
        }

        if (progress.total > 0) {
            binding.syncProgressBar.max = progress.total
            binding.syncProgressBar.setProgressCompat(progress.current, true)
        } else {
            binding.syncProgressBar.max = 100
            binding.syncProgressBar.setProgressCompat(0, false)
        }

        if (binding.syncProgressCard.visibility != View.VISIBLE) {
            binding.syncProgressCard.alpha = 0f
            binding.syncProgressCard.translationY = 80f
            binding.syncProgressCard.visibility = View.VISIBLE
            binding.syncProgressCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .start()
        }

        if (progress.phase == SyncPhase.DONE) {
            hideProgressJob = lifecycleScope.launch {
                delay(2000)
                hideSyncProgressCard()
            }
        }
    }

    private fun hideSyncProgressCard() {
        if (binding.syncProgressCard.visibility != View.VISIBLE) return
        binding.syncProgressCard.animate()
            .alpha(0f)
            .translationY(80f)
            .setDuration(250)
            .withEndAction {
                binding.syncProgressCard.visibility = View.GONE
                binding.syncProgressCard.alpha = 1f
                binding.syncProgressCard.translationY = 0f
            }
            .start()
    }

    // ============================================================
    // НАВИГАЦИЯ
    // ============================================================
    private fun updatePathTitle() {
        pathTextView?.text = viewModel.currentPath.value ?: "BAZA"
    }

    private fun navigateToFolder(folder: FolderEntity) {
        viewModel.navigateToFolder(folder.id)
    }

    private fun openItemDetail(item: ItemEntity) {
        val intent = Intent(this, ItemDetailActivity::class.java)
        intent.putExtra("item_id", item.id)
        itemDetailLauncher.launch(intent)
    }

    private fun showSearchDialog() {
        val editText = EditText(this)
        editText.hint = "Поиск по названию или штрих-коду..."
        viewModel.searchQueryLiveData.value?.let { if (it.isNotEmpty()) editText.setText(it) }

        AlertDialog.Builder(this)
            .setTitle("Поиск")
            .setView(editText)
            .setPositiveButton("Искать") { _, _ ->
                val q = editText.text.toString().trim()
                if (q.isNotEmpty()) viewModel.search(q) else viewModel.clearSearch()
            }
            .setNegativeButton("Сбросить") { _, _ -> viewModel.clearSearch() }
            .setNeutralButton("Отмена", null)
            .show()
    }

    private fun searchByBarcode(barcode: String) {
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { db.itemDao().getItemsByBarcode(barcode) }
                when {
                    items.isEmpty() -> {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Ничего не найдено")
                            .setMessage("Предмет с кодом «$barcode» не найден.\n\nСоздать новый?")
                            .setPositiveButton("Создать") { _, _ ->
                                val intent = Intent(this@MainActivity, AddItemActivity::class.java)
                                intent.putExtra("parent_id", viewModel.getCurrentFolderId())
                                intent.putExtra("barcode", barcode)
                                startActivity(intent)
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                    items.size == 1 -> openItemDetail(items.first())
                    else -> {
                        val names = items.map { "${it.name}  (×${it.quantity})" }.toTypedArray()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Найдено ${items.size} предметов")
                            .setItems(names) { _, which -> openItemDetail(items[which]) }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error searching by barcode", e)
            }
        }
    }

    private fun showCreateFolderDialog() {
        val editText = EditText(this)
        editText.hint = "Название папки"
        AlertDialog.Builder(this)
            .setTitle("Новая папка")
            .setView(editText)
            .setPositiveButton("Создать") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) createFolderWithImage(name) else Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Выбрать иконку") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    newFolderImageBytes = null
                    pickFolderImageLauncher.launch("image/*")
                } else Toast.makeText(this, "Сначала введите название", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена") { _, _ -> newFolderImageBytes = null }
            .show()
    }

    private fun createFolderWithImage(name: String) {
        lifecycleScope.launch {
            try {
                viewModel.createFolder(name)
                delay(100)
                newFolderImageBytes?.let { bytes ->
                    try {
                        val folders = withContext(Dispatchers.IO) { db.folderDao().getAllFolders() }
                        val lastFolder = folders.maxByOrNull { it.createdAt }
                        if (lastFolder != null) {
                            ImageUtils.saveImageLocally(applicationContext, "folder_${lastFolder.id}", bytes)
                            val updated = lastFolder.copy(iconUrl = "folder_${lastFolder.id}.jpg")
                            withContext(Dispatchers.IO) { db.folderDao().updateFolder(updated) }
                            viewModel.uploadFolderImage(lastFolder.id, bytes)
                        }
                    } catch (e: Exception) {
                        Logger.log(TAG, "Failed to upload folder image", e)
                    }
                    newFolderImageBytes = null
                }
                viewModel.loadContents()
                Toast.makeText(this@MainActivity, "Папка создана", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.log(TAG, "Error creating folder", e)
            }
        }
    }

    private fun showFolderContextMenu(folder: FolderEntity) {
        val items = arrayOf("Переименовать", "Сменить иконку", "Удалить (если пуста)", "Статистика", "Переместить")
        AlertDialog.Builder(this)
            .setTitle("Действия с папкой")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(folder)
                    1 -> changeFolderImage(folder)
                    2 -> confirmDeleteFolder(folder)
                    3 -> showFolderStats(folder)
                    4 -> showMoveFolderDialog(folder)
                }
            }
            .show()
    }

    private fun showItemContextMenu(item: ItemEntity) {
        val items = arrayOf("Редактировать", "Списать / В архив", "Удалить", "История", "Переместить")
        AlertDialog.Builder(this)
            .setTitle("Действия с предметом")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> editItem(item)
                    1 -> showWriteOffOrArchiveDialog(item)
                    2 -> confirmDeleteItem(item)
                    3 -> showItemHistory(item)
                    4 -> showMoveItemDialog(item)
                }
            }
            .show()
    }

    // ============================================================
    // СПИСАНИЕ / АРХИВ ИЗ КОНТЕКСТНОГО МЕНЮ
    // ============================================================

    private fun showWriteOffOrArchiveDialog(item: ItemEntity) {
        if (item.quantity <= 1) {
            showWriteOffReasonDialog(item, item.quantity.coerceAtLeast(1))
        } else {
            showWriteOffCountDialog(item)
        }
    }

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

    private fun showWriteOffCountDialog(item: ItemEntity) {
        val stepper = buildStepperLayout(item.quantity, withCheckAll = false)

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
                // ВСЕГДА спрашиваем причину
                showWriteOffReasonDialog(item, count)
            }
            .setNegativeButton("Отмена") { _, _ -> stopRepeat() }
            .setOnDismissListener { stopRepeat() }
            .show()
    }

    private fun showWriteOffReasonDialog(item: ItemEntity, count: Int) {
        val noun = getWriteOffNoun(item)
        val totalQty = item.quantity

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
                viewModel.writeOffItem(item.id, count, reasonKeys[which], null)
                if (count == totalQty) {
                    Toast.makeText(this, "Предмет в архиве", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Списано $count шт. в архив", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // ПЕРЕМЕЩЕНИЕ С ВЫБОРОМ КОЛИЧЕСТВА
    // ============================================================
    private fun showMoveItemDialog(item: ItemEntity) {
        if (item.quantity <= 1) {
            openMoveFolderPicker(item, 1)
            return
        }

        val stepper = buildStepperLayout(item.quantity, withCheckAll = true)

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
                openMoveFolderPicker(item, count)
            }
            .setNegativeButton("Отмена") { _, _ -> stopRepeat() }
            .setOnDismissListener { stopRepeat() }
            .show()
    }

    private fun openMoveFolderPicker(item: ItemEntity, count: Int) {
        Logger.log(TAG, "Show move item dialog: ${item.name}, current parentId=${item.parentId}, count=$count")

        MoveDialogHelper.show(
            context = this,
            scope = lifecycleScope,
            db = db,
            title = "Переместить «${item.name}»",
            startFromId = item.parentId,
            excludedIds = emptySet(),
            onConfirm = { newParentId ->
                if (newParentId == item.parentId) {
                    Toast.makeText(this, "Предмет уже в этой папке", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.splitAndMoveItem(item.id, count, newParentId)
                    Toast.makeText(this, "Перемещено $count шт.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ============================================================
    // СТЕППЕР [−] [N] [+] С LONG-PRESS (+ чекбокс «Всё»)
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
    private fun buildStepperLayout(maxQty: Int, withCheckAll: Boolean): StepperResult {
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
    // АРХИВ (старый диалог — оставлен для confirmDeleteItem)
    // ============================================================
    private fun showArchiveDialog(item: ItemEntity) {
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
            .setTitle("В архив: ${item.name}")
            .setItems(reasons) { _, which -> showArchiveNoteDialog(item, reasonKeys[which]) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showArchiveNoteDialog(item: ItemEntity, reason: String) {
        val editText = EditText(this)
        editText.hint = "Комментарий (необязательно)"
        AlertDialog.Builder(this)
            .setTitle("Комментарий")
            .setView(editText)
            .setPositiveButton("В архив") { _, _ ->
                val note = editText.text.toString().trim().ifEmpty { null }
                viewModel.archiveItem(item.id, reason, note)
                Toast.makeText(this, "В архиве", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showRenameFolderDialog(folder: FolderEntity) {
        val editText = EditText(this).apply { setText(folder.name) }
        AlertDialog.Builder(this)
            .setTitle("Переименовать папку")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                viewModel.renameFolder(folder.id, editText.text.toString().trim())
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDeleteFolder(folder: FolderEntity) {
        lifecycleScope.launch {
            viewModel.getFolderStats(folder.id) { stats ->
                val (itemCount, folderCount) = stats

                if (itemCount == 0 && folderCount == 0) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Удалить папку «${folder.name}»?")
                        .setPositiveButton("Да") { _, _ ->
                            viewModel.deleteFolder(folder.id)
                            Toast.makeText(this@MainActivity, "Папка удалена", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Нет", null)
                        .show()
                } else {
                    val message = buildString {
                        append("Папка «${folder.name}» не пуста:\n\n")
                        if (itemCount > 0) append("• Предметов: $itemCount\n")
                        if (folderCount > 0) append("• Подпапок: $folderCount\n")
                        append("\nВсё содержимое будет перемещено в корень, после чего папка удалится.")
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("⚠️ Удалить папку?")
                        .setMessage(message)
                        .setPositiveButton("Переместить и удалить") { _, _ ->
                            viewModel.deleteFolder(folder.id)
                            Toast.makeText(
                                this@MainActivity,
                                "Содержимое перемещено в корень, папка удалена",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
        }
    }

    private fun changeFolderImage(folder: FolderEntity) {
        currentFolderForImage = folder
        pickExistingFolderImageLauncher.launch("image/*")
    }

    private fun showFolderStats(folder: FolderEntity) {
        lifecycleScope.launch {
            viewModel.getFolderStats(folder.id) { stats ->
                val (itemCount, folderCount) = stats
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Статистика «${folder.name}»")
                    .setMessage("Предметов: $itemCount\nПодпапок: $folderCount")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showMoveFolderDialog(folder: FolderEntity) {
        Logger.log(TAG, "Show move folder dialog: ${folder.name}, current parentId=${folder.parentId}")

        lifecycleScope.launch {
            try {
                val excluded = withContext(Dispatchers.IO) {
                    collectDescendantIds(folder.id) + folder.id
                }

                MoveDialogHelper.show(
                    context = this@MainActivity,
                    scope = lifecycleScope,
                    db = db,
                    title = "Переместить «${folder.name}»",
                    startFromId = folder.parentId,
                    excludedIds = excluded,
                    onConfirm = { newParentId ->
                        if (newParentId == folder.parentId) {
                            Toast.makeText(this@MainActivity, "Папка уже здесь", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.moveFolder(folder.id, newParentId)
                            Toast.makeText(this@MainActivity, "Перемещено", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing move folder dialog", e)
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun collectDescendantIds(folderId: String): Set<String> {
        val result = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(folderId)

        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            val children = db.folderDao().getFoldersByParent(id)
            for (child in children) {
                if (result.add(child.id)) {
                    stack.addLast(child.id)
                }
            }
        }
        return result
    }

    private fun editItem(item: ItemEntity) {
        val intent = Intent(this, ItemDetailActivity::class.java)
        intent.putExtra("item_id", item.id)
        intent.putExtra("edit_mode", true)
        itemDetailLauncher.launch(intent)
    }

    private fun confirmDeleteItem(item: ItemEntity) {
        AlertDialog.Builder(this)
            .setTitle("Удалить предмет «${item.name}»?")
            .setMessage("Это действие нельзя отменить. Возможно, лучше в архив?")
            .setPositiveButton("Удалить") { _, _ -> viewModel.deleteItem(item.id) }
            .setNegativeButton("В архив") { _, _ -> showArchiveDialog(item) }
            .setNeutralButton("Отмена", null)
            .show()
    }

    private fun showItemHistory(item: ItemEntity) {
        val intent = Intent(this, ItemDetailActivity::class.java)
        intent.putExtra("item_id", item.id)
        intent.putExtra("show_history", true)
        itemDetailLauncher.launch(intent)
    }

    private fun updateSearchIcon(query: String?) {
        if (query.isNullOrEmpty()) {
            binding.btnSearch.setImageResource(R.drawable.ic_search)
            binding.btnSearch.setOnClickListener { showSearchDialog() }
        } else {
            binding.btnSearch.setImageResource(R.drawable.ic_close)
            binding.btnSearch.setOnClickListener { viewModel.clearSearch() }
        }
    }

    private fun updateSyncStatusIcon(status: SyncStatus) {
        when (status) {
            SyncStatus.SYNCING -> {
                binding.ivSyncStatus.setImageResource(R.drawable.ic_sync_syncing)
                startSyncAnimation()
            }
            SyncStatus.SYNCED -> {
                binding.ivSyncStatus.setImageResource(R.drawable.ic_sync_done)
                stopSyncAnimation()
            }
            SyncStatus.PENDING -> {
                binding.ivSyncStatus.setImageResource(R.drawable.ic_sync_pending)
                stopSyncAnimation()
            }
            SyncStatus.OFFLINE -> {
                binding.ivSyncStatus.setImageResource(R.drawable.ic_sync_offline)
                stopSyncAnimation()
            }
        }
    }

    private fun startSyncAnimation() {
        if (syncRotationAnim == null) {
            syncRotationAnim = RotateAnimation(
                0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 1000
                interpolator = LinearInterpolator()
                repeatCount = Animation.INFINITE
                repeatMode = Animation.RESTART
            }
        }
        binding.ivSyncStatus.startAnimation(syncRotationAnim)
    }

    private fun stopSyncAnimation() {
        binding.ivSyncStatus.clearAnimation()
    }
}

package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.R
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.databinding.ActivityMainBinding
import com.family.base.ui.adapter.CatalogAdapter
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.AppLifecycleObserver
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
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

    private val pickFolderImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bitmap = ImageUtils.loadBitmapWithExif(this, it) ?: return@let
                val processedBytes = ImageUtils.processImage(bitmap)
                newFolderImageBytes = processedBytes
                Logger.log(TAG, "Folder image selected, size=${processedBytes.size}")
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
                            withContext(Dispatchers.IO) {
                                db.folderDao().updateFolder(updated)
                            }
                            viewModel.uploadFolderImage(folder.id, processedBytes)
                            viewModel.syncWithDisk()
                            viewModel.loadContents()
                            Toast.makeText(this@MainActivity, "Иконка обновлена", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Logger.log(TAG, "Error updating folder image", e)
                            Toast.makeText(this@MainActivity, "Ошибка обновления иконки", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error picking folder image", e)
                Toast.makeText(this, "Ошибка обработки фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val barcodeSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanned = result.data?.getStringExtra("barcode")
            if (!scanned.isNullOrEmpty()) {
                Logger.log(TAG, "Scanned barcode for search: $scanned")
                searchByBarcode(scanned)
            }
        }
    }

    private val connectFamilyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Logger.log(TAG, "ConnectFamily result: resultCode=${result.resultCode}")
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
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

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

        viewModel.navigateToFolder(null)
        updateSearchIcon(viewModel.searchQueryLiveData.value)
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
    }

    private fun updatePathTitle() {
        pathTextView?.text = viewModel.currentPath.value ?: "BAZA"
    }

    private fun navigateToFolder(folder: FolderEntity) {
        viewModel.navigateToFolder(folder.id)
    }

    private fun openItemDetail(item: ItemEntity) {
        val intent = Intent(this, ItemDetailActivity::class.java)
        intent.putExtra("item_id", item.id)
        startActivity(intent)
    }

    private fun showSearchDialog() {
        val editText = android.widget.EditText(this)
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
        val editText = android.widget.EditText(this)
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
                viewModel.syncWithDisk()
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
        val items = arrayOf("Редактировать", "В архив", "Удалить", "История", "Переместить")
        AlertDialog.Builder(this)
            .setTitle("Действия с предметом")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> editItem(item)
                    1 -> showArchiveDialog(item)
                    2 -> confirmDeleteItem(item)
                    3 -> showItemHistory(item)
                    4 -> showMoveItemDialog(item)
                }
            }
            .show()
    }

    private fun showArchiveDialog(item: ItemEntity) {
        val reasons = arrayOf("🍽 Съедено", "🔧 Сломано", "🗑 Выброшено", "🎁 Подарено", "💰 Продано", "⏰ Истёк срок", "📦 Другое")
        val reasonKeys = arrayOf("eaten", "broken", "thrown", "gifted", "sold", "expired", "other")
        AlertDialog.Builder(this)
            .setTitle("В архив: ${item.name}")
            .setItems(reasons) { _, which -> showArchiveNoteDialog(item, reasonKeys[which]) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showArchiveNoteDialog(item: ItemEntity, reason: String) {
        val editText = android.widget.EditText(this)
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
        val editText = android.widget.EditText(this).apply { setText(folder.name) }
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
                if (itemCount > 0 || folderCount > 0) {
                    Toast.makeText(this@MainActivity, "Папка не пуста", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Удалить папку «${folder.name}»?")
                        .setPositiveButton("Да") { _, _ -> viewModel.deleteFolder(folder.id) }
                        .setNegativeButton("Нет", null)
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

    // ============================================================
    // ПЕРЕМЕЩЕНИЕ ПАПКИ (новый иерархический диалог)
    // ============================================================
    private fun showMoveFolderDialog(folder: FolderEntity) {
        Logger.log(TAG, "Show move folder dialog: ${folder.name}")

        lifecycleScope.launch {
            try {
                // Исключаем саму папку и всех её потомков
                val excluded = withContext(Dispatchers.IO) {
                    collectDescendantIds(folder.id) + folder.id
                }

                MoveDialogHelper.show(
                    context = this@MainActivity,
                    scope = lifecycleScope,
                    db = db,
                    title = "Переместить «${folder.name}»",
                    startFromId = null,
                    excludedIds = excluded,
                    onConfirm = { newParentId ->
                        Logger.log(TAG, "Move folder to: $newParentId")
                        viewModel.moveFolder(folder.id, newParentId)
                        Toast.makeText(this@MainActivity, "Перемещено", Toast.LENGTH_SHORT).show()
                    }
                )
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing move folder dialog", e)
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // ПЕРЕМЕЩЕНИЕ ПРЕДМЕТА (новый иерархический диалог)
    // ============================================================
    private fun showMoveItemDialog(item: ItemEntity) {
        Logger.log(TAG, "Show move item dialog: ${item.name}")

        MoveDialogHelper.show(
            context = this,
            scope = lifecycleScope,
            db = db,
            title = "Переместить «${item.name}»",
            startFromId = null,
            excludedIds = emptySet(),   // предмет можно переместить в любую папку
            onConfirm = { newParentId ->
                Logger.log(TAG, "Move item to: $newParentId")
                viewModel.moveItem(item.id, newParentId)
                Toast.makeText(this, "Перемещено", Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * Рекурсивно собирает id всех потомков папки.
     * Нужно для защиты: нельзя переместить папку в саму себя или в своего потомка.
     */
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
        startActivity(intent)
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
        startActivity(intent)
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

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
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
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

    // ===== ДЛЯ ИКОНКИ ПАПКИ =====
    private var currentFolderForImage: FolderEntity? = null
    private val pickFolderImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, it)
                val processedBytes = ImageUtils.processImage(bitmap)
                val folder = currentFolderForImage
                if (folder != null) {
                    lifecycleScope.launch {
                        // 1. Сохраняем локально
                        ImageUtils.saveImageLocally(applicationContext, "folder_${folder.id}", processedBytes)
                        // 2. Обновляем папку в БД
                        val updated = folder.copy(iconUrl = "folder_${folder.id}.jpg")
                        withContext(Dispatchers.IO) {
                            db.folderDao().updateFolder(updated)
                        }
                        // 3. Загружаем на диск через ViewModel
                        viewModel.uploadFolderImage(folder.id, processedBytes)
                        // 4. Синхронизация и обновление списка
                        viewModel.syncWithDisk()
                        viewModel.loadContents()
                        Toast.makeText(this@MainActivity, "Иконка обновлена", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error picking folder image", e)
                Toast.makeText(this, "Ошибка обработки фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val connectFamilyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Logger.log(TAG, "ConnectFamily result: resultCode=${result.resultCode}, data=${result.data}")
        if (result.resultCode == RESULT_OK) {
            Logger.log(TAG, "Public key saved, reloading contents")
            viewModel.loadContents()
        } else {
            Logger.log(TAG, "ConnectFamily cancelled or failed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== MainActivity onCreate START ===")
        Logger.log(TAG, "SavedInstanceState: ${savedInstanceState != null}")
        
        try {
            Logger.log(TAG, "Inflating layout...")
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Binding inflated successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        Logger.init(applicationContext)

        try {
            Logger.log(TAG, "Initializing TokenStorage...")
            tokenStorage = TokenStorage(this)
            Logger.log(TAG, "TokenStorage initialized")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to initialize TokenStorage", e)
            return
        }

        try {
            Logger.log(TAG, "Creating ViewModel...")
            viewModel = ViewModelProvider(this)[MainViewModel::class.java]
            Logger.log(TAG, "ViewModel created")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to create ViewModel", e)
            return
        }

        val publicKey = tokenStorage.getPublicKey()
        Logger.log(TAG, "Public key from storage: ${publicKey?.take(20) ?: "null"}")
        
        if (publicKey == null) {
            Logger.log(TAG, "No public key, launching ConnectFamilyActivity")
            try {
                val intent = Intent(this, ConnectFamilyActivity::class.java)
                connectFamilyLauncher.launch(intent)
                Logger.log(TAG, "ConnectFamilyActivity launched")
            } catch (e: Exception) {
                Logger.log(TAG, "CRITICAL: Failed to launch ConnectFamilyActivity", e)
            }
        } else {
            Logger.log(TAG, "Public key exists, proceeding with normal startup")
        }

        try {
            Logger.log(TAG, "Setting up toolbar...")
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(false)

            pathTextView = TextView(this).apply {
                text = "BAZA"
                textSize = 18f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
                setPadding(0, 0, 0, 0)
            }
            supportActionBar?.setCustomView(pathTextView)
            supportActionBar?.displayOptions = ActionBar.DISPLAY_SHOW_CUSTOM

            Logger.log(TAG, "Toolbar set up")
        } catch (e: Exception) {
            Logger.log(TAG, "Error setting up toolbar", e)
        }

        try {
            Logger.log(TAG, "Creating adapter...")
            adapter = CatalogAdapter(
                onFolderClick = { folder -> navigateToFolder(folder) },
                onItemClick = { item -> openItemDetail(item) },
                onFolderLongClick = { folder -> showFolderContextMenu(folder) },
                onItemLongClick = { item -> showItemContextMenu(item) }
            )
            binding.rvCatalog.layoutManager = LinearLayoutManager(this)
            binding.rvCatalog.adapter = adapter
            Logger.log(TAG, "Adapter set up")
        } catch (e: Exception) {
            Logger.log(TAG, "Error setting up adapter", e)
        }

        try {
            Logger.log(TAG, "Setting up observers...")
            viewModel.currentEntries.observe(this) { entries ->
                Logger.log(TAG, "Entries updated: ${entries.size} items")
                try {
                    adapter.submitList(entries)
                    updatePathTitle()
                } catch (e: Exception) {
                    Logger.log(TAG, "Error updating adapter", e)
                }
            }
            
            viewModel.syncStatus.observe(this) { status ->
                Logger.log(TAG, "Sync status changed: $status")
                updateSyncStatusIcon(status)
            }

            viewModel.searchQueryLiveData.observe(this) { query ->
                updateSearchIcon(query)
            }
            
            Logger.log(TAG, "Observers set up")
        } catch (e: Exception) {
            Logger.log(TAG, "Error setting up observers", e)
        }

        try {
            Logger.log(TAG, "Setting up button listeners...")
            binding.btnAddFolder.setOnClickListener { 
                Logger.log(TAG, "Add folder button clicked")
                showCreateFolderDialog() 
            }
            
            binding.btnAddItem.setOnClickListener { 
                Logger.log(TAG, "Add item button clicked")
                val intent = Intent(this, AddItemActivity::class.java)
                intent.putExtra("parent_id", viewModel.getCurrentFolderId())
                startActivity(intent)
            }
            
            binding.btnHome.setOnClickListener { 
                Logger.log(TAG, "Home button clicked")
                viewModel.navigateToRoot()
            }
            binding.btnUp.setOnClickListener { 
                Logger.log(TAG, "Up button clicked")
                viewModel.navigateUp()
            }
            binding.btnSearch.setOnClickListener {
                Logger.log(TAG, "Search button clicked")
                showSearchDialog()
            }
            binding.btnSettings.setOnClickListener {
                Logger.log(TAG, "Settings button clicked")
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            Logger.log(TAG, "Button listeners set up")
        } catch (e: Exception) {
            Logger.log(TAG, "Error setting up button listeners", e)
        }

        try {
            Logger.log(TAG, "Navigating to root folder...")
            viewModel.navigateToFolder(null)
            Logger.log(TAG, "Navigation started")
        } catch (e: Exception) {
            Logger.log(TAG, "Error navigating to root", e)
        }

        updateSearchIcon(viewModel.searchQueryLiveData.value)

        Logger.log(TAG, "=== MainActivity onCreate FINISHED ===")
    }

    override fun onResume() {
        super.onResume()
        Logger.log(TAG, "onResume called, publicKey: ${tokenStorage.getPublicKey()?.take(20)}")
        viewModel.syncWithDisk()
    }

    override fun onPause() {
        super.onPause()
        Logger.log(TAG, "onPause called")
        stopSyncAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
        stopSyncAnimation()
    }

    // ===== ИСПРАВЛЕНАЯ ФУНКЦИЯ =====
    private fun updatePathTitle() {
        val path = viewModel.currentPath.value ?: "BAZA"
        pathTextView?.text = path
    }

    private fun navigateToFolder(folder: FolderEntity) {
        Logger.log(TAG, "Navigate to folder: id=${folder.id}, name=${folder.name}")
        viewModel.navigateToFolder(folder.id)
    }

    private fun openItemDetail(item: ItemEntity) {
        Logger.log(TAG, "Open item: id=${item.id}, name=${item.name}")
        val intent = Intent(this, ItemDetailActivity::class.java)
        intent.putExtra("item_id", item.id)
        startActivity(intent)
    }

    private fun showSearchDialog() {
        Logger.log(TAG, "Showing search dialog")
        val editText = android.widget.EditText(this)
        editText.hint = "Поиск предметов..."

        val currentQuery = viewModel.searchQueryLiveData.value
        if (!currentQuery.isNullOrEmpty()) {
            editText.setText(currentQuery)
        }

        AlertDialog.Builder(this)
            .setTitle("Поиск")
            .setView(editText)
            .setPositiveButton("Искать") { _, _ ->
                val query = editText.text.toString().trim()
                Logger.log(TAG, "Search query: $query")
                if (query.isNotEmpty()) {
                    viewModel.search(query)
                } else {
                    viewModel.clearSearch()
                }
            }
            .setNegativeButton("Сбросить") { _, _ ->
                Logger.log(TAG, "Search cleared")
                viewModel.clearSearch()
            }
            .setNeutralButton("Отмена") { _, _ ->
                // ничего не делаем
            }
            .show()
    }

    private fun showFolderContextMenu(folder: FolderEntity) {
        Logger.log(TAG, "Folder context menu: ${folder.name}")
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
        Logger.log(TAG, "Item context menu: ${item.name}")
        val items = arrayOf("Увеличить количество", "Уменьшить количество", "Редактировать", "Удалить", "История", "Переместить")
        AlertDialog.Builder(this)
            .setTitle("Действия с предметом")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> changeItemQuantity(item, +1)
                    1 -> changeItemQuantity(item, -1)
                    2 -> editItem(item)
                    3 -> confirmDeleteItem(item)
                    4 -> showItemHistory(item)
                    5 -> showMoveItemDialog(item)
                }
            }
            .show()
    }

    private fun showCreateFolderDialog() {
        Logger.log(TAG, "Showing create folder dialog")
        val editText = android.widget.EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Новая папка")
            .setView(editText)
            .setPositiveButton("Создать") { _, _ ->
                val name = editText.text.toString().trim()
                Logger.log(TAG, "Create folder dialog confirmed, name: $name")
                if (name.isNotEmpty()) {
                    viewModel.createFolder(name)
                }
            }
            .setNegativeButton("Отмена") { _, _ ->
                Logger.log(TAG, "Create folder dialog cancelled")
            }
            .show()
    }

    private fun showRenameFolderDialog(folder: FolderEntity) {
        Logger.log(TAG, "Showing rename dialog for: ${folder.name}")
        val editText = android.widget.EditText(this).apply { setText(folder.name) }
        AlertDialog.Builder(this)
            .setTitle("Переименовать папку")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val newName = editText.text.toString().trim()
                Logger.log(TAG, "Rename confirmed: ${folder.name} -> $newName")
                viewModel.renameFolder(folder.id, newName)
            }
            .setNegativeButton("Отмена") { _, _ ->
                Logger.log(TAG, "Rename cancelled")
            }
            .show()
    }

    private fun confirmDeleteFolder(folder: FolderEntity) {
        Logger.log(TAG, "Confirming delete folder: ${folder.name}")
        lifecycleScope.launch {
            try {
                viewModel.getFolderStats(folder.id) { stats ->
                    val (itemCount, folderCount) = stats
                    Logger.log(TAG, "Folder stats: items=$itemCount, subfolders=$folderCount")
                    if (itemCount > 0 || folderCount > 0) {
                        Toast.makeText(this@MainActivity, "Папка не пуста", Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Удалить папку «${folder.name}»?")
                            .setMessage("Вы уверены?")
                            .setPositiveButton("Да") { _, _ ->
                                viewModel.deleteFolder(folder.id)
                            }
                            .setNegativeButton("Нет", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error in confirmDeleteFolder", e)
                Toast.makeText(this@MainActivity, "Ошибка при проверке папки", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun changeFolderImage(folder: FolderEntity) {
        Logger.log(TAG, "Change image requested for folder: ${folder.name}")
        currentFolderForImage = folder
        pickFolderImageLauncher.launch("image/*")
    }

    private fun showFolderStats(folder: FolderEntity) {
        Logger.log(TAG, "Showing stats for folder: ${folder.name}")
        lifecycleScope.launch {
            try {
                viewModel.getFolderStats(folder.id) { stats ->
                    val (itemCount, folderCount) = stats
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Статистика папки «${folder.name}»")
                        .setMessage("Предметов: $itemCount\nПодпапок: $folderCount")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error in showFolderStats", e)
                Toast.makeText(this@MainActivity, "Ошибка при получении статистики", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ========== ДИАЛОГ ПЕРЕМЕЩЕНИЯ ПАПКИ ==========
    private fun showMoveFolderDialog(folder: FolderEntity) {
        Logger.log(TAG, "Show move folder dialog for: ${folder.name}")
        lifecycleScope.launch {
            try {
                val allFolders = viewModel.getAllFolders().filter { it.id != folder.id }
                val folderNames = allFolders.map { it.name }.toMutableList()
                folderNames.add(0, "Корень")

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Переместить папку «${folder.name}»")
                    .setItems(folderNames.toTypedArray()) { _, which ->
                        val targetFolder = if (which == 0) null else allFolders[which - 1]
                        val newParentId = targetFolder?.id
                        Logger.log(TAG, "Moving folder to: ${targetFolder?.name ?: "корень"} (id=$newParentId)")
                        viewModel.moveFolder(folder.id, newParentId)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing move folder dialog", e)
                Toast.makeText(this@MainActivity, "Ошибка загрузки списка папок", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ========== ДИАЛОГ ПЕРЕМЕЩЕНИЯ ПРЕДМЕТА ==========
    private fun showMoveItemDialog(item: ItemEntity) {
        Logger.log(TAG, "Show move item dialog for: ${item.name}")
        lifecycleScope.launch {
            try {
                val allFolders = viewModel.getAllFolders()
                val folderNames = allFolders.map { it.name }.toMutableList()
                folderNames.add(0, "Корень")

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Переместить предмет «${item.name}»")
                    .setItems(folderNames.toTypedArray()) { _, which ->
                        val targetFolder = if (which == 0) null else allFolders[which - 1]
                        val newParentId = targetFolder?.id
                        Logger.log(TAG, "Moving item to: ${targetFolder?.name ?: "корень"} (id=$newParentId)")
                        viewModel.moveItem(item.id, newParentId)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            } catch (e: Exception) {
                Logger.log(TAG, "Error showing move item dialog", e)
                Toast.makeText(this@MainActivity, "Ошибка загрузки списка папок", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun changeItemQuantity(item: ItemEntity, delta: Int) {
        val newQty = (item.quantity + delta).coerceAtLeast(0)
        Logger.log(TAG, "Changing quantity of ${item.name}: ${item.quantity} -> $newQty (delta: $delta)")
        viewModel.updateItemQuantity(item.id, newQty)
    }

    private fun editItem(item: ItemEntity) {
        Logger.log(TAG, "Edit item: ${item.name}")
        val intent = Intent(this, ItemDetailActivity::class.java)
        intent.putExtra("item_id", item.id)
        intent.putExtra("edit_mode", true)
        startActivity(intent)
    }

    private fun confirmDeleteItem(item: ItemEntity) {
        Logger.log(TAG, "Confirming delete item: ${item.name}")
        AlertDialog.Builder(this)
            .setTitle("Удалить предмет «${item.name}»?")
            .setPositiveButton("Да") { _, _ ->
                Logger.log(TAG, "Delete item confirmed")
                viewModel.deleteItem(item.id)
            }
            .setNegativeButton("Нет") { _, _ ->
                Logger.log(TAG, "Delete item cancelled")
            }
            .show()
    }

    private fun showItemHistory(item: ItemEntity) {
        Logger.log(TAG, "Show history for item: ${item.name}")
        val intent = Intent(this, ItemDetailActivity::class.java)
        intent.putExtra("item_id", item.id)
        intent.putExtra("show_history", true)
        startActivity(intent)
    }

    // ============================================================
    // ИКОНКА ПОИСКА С СОСТОЯНИЕМ
    // ============================================================

    private fun updateSearchIcon(query: String?) {
        if (query.isNullOrEmpty()) {
            binding.btnSearch.setImageResource(R.drawable.ic_search)
            binding.btnSearch.setOnClickListener {
                Logger.log(TAG, "Search button clicked")
                showSearchDialog()
            }
        } else {
            binding.btnSearch.setImageResource(R.drawable.ic_close)
            binding.btnSearch.setOnClickListener {
                Logger.log(TAG, "Clear search clicked")
                viewModel.clearSearch()
            }
        }
    }

    // ============================================================
    // СТАТУС СИНХРОНИЗАЦИИ
    // ============================================================

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

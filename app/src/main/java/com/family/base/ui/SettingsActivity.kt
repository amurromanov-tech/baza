package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.family.base.BuildConfig
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.remote.AppVersion
import com.family.base.data.remote.YandexDiskApi
import com.family.base.databinding.ActivitySettingsBinding
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.Logger
import com.family.base.util.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var tokenStorage: TokenStorage
    private lateinit var viewModel: MainViewModel
    private val TAG = "SettingsActivity"

   override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Logger.log(TAG, "=== SettingsActivity onCreate START ===")

    try {
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Logger.log(TAG, "Binding inflated successfully")
    } catch (e: Exception) {
        Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
        return
    }

    try {
        tokenStorage = TokenStorage(this)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        Logger.log(TAG, "TokenStorage and ViewModel initialized")
    } catch (e: Exception) {
        Logger.log(TAG, "CRITICAL: Failed to initialize", e)
        return
    }

    setupListeners()           // ← уже было
    loadSettings()             // ← уже было
    showCurrentVersion()       // ← уже было
    setupBackupListeners()     // ← НОВАЯ СТРОЧКА

    Logger.log(TAG, "=== SettingsActivity onCreate FINISHED ===")
}
    // ============================================================
    // ПОКАЗАТЬ ТЕКУЩУЮ ВЕРСИЮ
    // ============================================================
    private fun showCurrentVersion() {
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        binding.tvVersion.text = "Версия $versionName (код $versionCode)"
    }

    // ============================================================
    // НАСТРОЙКА СЛУШАТЕЛЕЙ
    // ============================================================
    private fun setupListeners() {
        Logger.log(TAG, "Setting up listeners")

        // Синхронизация
        binding.btnSyncNow.setOnClickListener {
            Logger.log(TAG, "Sync now clicked")
            Toast.makeText(this, "Синхронизация запущена...", Toast.LENGTH_SHORT).show()
            viewModel.forceSync()
        }

        // Очистка кэша
        binding.btnClearCache.setOnClickListener {
            Logger.log(TAG, "Clear cache clicked")
            showClearCacheDialog()
        }

        // Очистка логов
        binding.btnClearLogs.setOnClickListener {
            Logger.log(TAG, "Clear logs clicked")
            showClearLogsDialog()
        }

        // Отправка логов
        binding.btnSendLog.setOnClickListener {
            Logger.log(TAG, "Send log clicked")
            sendLogs()
        }

        // Выход
        binding.btnLogout.setOnClickListener {
            Logger.log(TAG, "Logout clicked")
            showLogoutDialog()
        }

        // Полная очистка данных
        binding.btnClearAllData.setOnClickListener {
            Logger.log(TAG, "Clear all data clicked")
            showClearAllDataDialog()
        }

        // Логирование (вкл/выкл)
        binding.switchLogging.setOnCheckedChangeListener { _, isChecked ->
            Logger.log(TAG, "Logging enabled: $isChecked")
            Logger.setEnabled(isChecked)
            saveSettings()
        }

        // ===== КНОПКА ПРОВЕРКИ ОБНОВЛЕНИЙ =====
        binding.btnCheckUpdate.setOnClickListener {
            Logger.log(TAG, "Check for updates clicked")
            checkForUpdates()
        }

        // ===== НОВАЯ КНОПКА: ПОДЕЛИТЬСЯ ССЫЛКОЙ =====
        binding.btnShareLink.setOnClickListener {
            Logger.log(TAG, "Share link clicked")
            shareFolderLink()
        }
    }

    // ============================================================
    // ЗАГРУЗКА/СОХРАНЕНИЕ НАСТРОЕК
    // ============================================================
    private fun loadSettings() {
        Logger.log(TAG, "Loading settings")
        try {
            val prefs = getSharedPreferences("baza_settings", MODE_PRIVATE)
            val loggingEnabled = prefs.getBoolean("logging_enabled", true)
            binding.switchLogging.isChecked = loggingEnabled
            Logger.setEnabled(loggingEnabled)
            Logger.log(TAG, "Settings loaded: logging=$loggingEnabled")
        } catch (e: Exception) {
            Logger.log(TAG, "Error loading settings", e)
        }
    }

    private fun saveSettings() {
        Logger.log(TAG, "Saving settings")
        try {
            val prefs = getSharedPreferences("baza_settings", MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("logging_enabled", binding.switchLogging.isChecked)
                apply()
            }
            Logger.log(TAG, "Settings saved")
        } catch (e: Exception) {
            Logger.log(TAG, "Error saving settings", e)
        }
    }

    // ============================================================
    // ОЧИСТКА КЭША
    // ============================================================
    private fun showClearCacheDialog() {
        AlertDialog.Builder(this)
            .setTitle("Очистить кэш")
            .setMessage("Удалить все загруженные изображения?")
            .setPositiveButton("Да") { _, _ ->
                Logger.log(TAG, "Cache cleared")
                clearCache()
                Toast.makeText(this, "Кэш очищен", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun clearCache() {
        try {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            Logger.log(TAG, "Cache cleared successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "Error clearing cache", e)
        }
    }

    // ============================================================
    // ОЧИСТКА ЛОГОВ
    // ============================================================
    private fun showClearLogsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Очистить логи")
            .setMessage("Удалить все файлы логов?")
            .setPositiveButton("Да") { _, _ ->
                Logger.log(TAG, "Logs cleared")
                Logger.clearLogs()
                Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    // ============================================================
    // ОТПРАВКА ЛОГОВ
    // ============================================================
    private fun sendLogs() {
        Logger.log(TAG, "Sending logs...")
        try {
            val logsDir = Logger.getLogsDirectory()
            if (logsDir == null || !logsDir.exists()) {
                Toast.makeText(this, "Логи не найдены", Toast.LENGTH_SHORT).show()
                return
            }

            val logFiles = logsDir.listFiles()
            if (logFiles.isNullOrEmpty()) {
                Toast.makeText(this, "Логи не найдены", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("support@familybase.com"))
                putExtra(Intent.EXTRA_SUBJECT, "Логи приложения БАЗА")
                
                val uris = logFiles.map { file ->
                    android.net.Uri.fromFile(file)
                }
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }

            startActivity(Intent.createChooser(intent, "Отправить логи"))
            Logger.log(TAG, "Logs sent successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "Error sending logs", e)
            Toast.makeText(this, "Ошибка отправки логов", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // ПОЛНАЯ ОЧИСТКА ДАННЫХ
    // ============================================================
    private fun showClearAllDataDialog() {
        val randomWord = generateRandomWord()
        val editText = android.widget.EditText(this)
        editText.hint = "Введите: $randomWord"

        AlertDialog.Builder(this)
            .setTitle("⚠️ ПОЛНАЯ ОЧИСТКА ДАННЫХ")
            .setMessage(
                """
                ВНИМАНИЕ! Это действие:
                
                • Удалит все локальные данные
                • Удалит все данные на Яндекс.Диске
                • Восстановление НЕВОЗМОЖНО
                
                Для подтверждения введите слово: $randomWord
                """.trimIndent()
            )
            .setView(editText)
            .setPositiveButton("Удалить всё") { _, _ ->
                val input = editText.text.toString().trim()
                if (input == randomWord) {
                    clearAllData()
                } else {
                    Toast.makeText(this, "Неверное слово", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun generateRandomWord(): String {
        val words = listOf(
            "DELETE", "CLEAR", "REMOVE", "ERASE", "RESET",
            "PURGE", "WIPE", "OBLITERATE", "ANNIHILATE"
        )
        return words.random()
    }

    private fun clearAllData() {
        Logger.log(TAG, "=== CLEAR ALL DATA START ===")
        
        Toast.makeText(this, "Очистка данных...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            try {
                // === 1. Удаляем файлы локальной БД физически ===
                val db = AppDatabase.getInstance(this@SettingsActivity)
                db.close()
                
                val dbFile = getDatabasePath("baza.db")
                if (dbFile.exists()) {
                    dbFile.delete()
                    Logger.log(TAG, "Database file deleted")
                }
                val walFile = getDatabasePath("baza.db-wal")
                if (walFile.exists()) {
                    walFile.delete()
                    Logger.log(TAG, "WAL file deleted")
                }
                val shmFile = getDatabasePath("baza.db-shm")
                if (shmFile.exists()) {
                    shmFile.delete()
                    Logger.log(TAG, "SHM file deleted")
                }
                Logger.log(TAG, "Local DB files removed")

                // === 2. Сбрасываем статический экземпляр ===
                AppDatabase.resetInstance()
                Logger.log(TAG, "AppDatabase instance reset")
                
                // === 3. Удаляем локальные изображения ===
                try {
                    val imagesDir = java.io.File(filesDir, "images")
                    if (imagesDir.exists()) {
                        imagesDir.deleteRecursively()
                        Logger.log(TAG, "Deleted local images folder")
                    }
                } catch (e: Exception) {
                    Logger.log(TAG, "Error deleting local images", e)
                }
                
                // === 4. Очищаем Яндекс.Диск ===
                val token = tokenStorage.getAccessToken()
                if (token != null) {
                    try {
                        val api = YandexDiskApi.getInstance()
                        val auth = "OAuth $token"
                        val folderName = tokenStorage.getFolderName() ?: "BAZA"
                        val rootPath = "/$folderName"

                        val deleteData = api.deleteFile(auth, "$rootPath/data", true)
                        Logger.log(TAG, "Delete $rootPath/data: ${deleteData.code()}")

                        val deleteImages = api.deleteFile(auth, "$rootPath/images", true)
                        Logger.log(TAG, "Delete $rootPath/images: ${deleteImages.code()}")

                        val deleteLastModified = api.deleteFile(auth, "$rootPath/.last_modified", true)
                        Logger.log(TAG, "Delete $rootPath/.last_modified: ${deleteLastModified.code()}")

                        Logger.log(TAG, "Disk contents cleared (root folder preserved)")
                    } catch (e: Exception) {
                        Logger.log(TAG, "Error clearing disk contents", e)
                    }
                } else {
                    Logger.log(TAG, "No token, skip Yandex.Disk deletion")
                }
                
                // === 5. Очищаем кэш и логи ===
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
                Logger.log(TAG, "Cache cleared")
                
                Logger.clearLogs()
                Logger.log(TAG, "Logs cleared")
                
                // === 6. Перезапускаем MainActivity ===
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "✅ Все данные очищены",
                        Toast.LENGTH_LONG
                    ).show()
                    
                    val intent = Intent(this@SettingsActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
                
                Logger.log(TAG, "=== CLEAR ALL DATA FINISHED ===")
                
            } catch (e: Exception) {
                Logger.log(TAG, "Error clearing all data", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "❌ Ошибка очистки: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ===== РЕЗЕРВНОЕ КОПИРОВАНИЕ =====
private fun setupBackupListeners() {
    binding.btnExportLocal.setOnClickListener {
        Logger.log(TAG, "Export local clicked")
        exportBackup(local = true)
    }

    binding.btnExportCloud.setOnClickListener {
        Logger.log(TAG, "Export cloud clicked")
        exportBackup(local = false)
    }

    binding.btnImportLocal.setOnClickListener {
        Logger.log(TAG, "Import local clicked")
        showImportDialog(local = true)
    }

    binding.btnImportCloud.setOnClickListener {
        Logger.log(TAG, "Import cloud clicked")
        showImportDialog(local = false)
    }
}

private fun exportBackup(local: Boolean) {
    lifecycleScope.launch {
        try {
            Toast.makeText(this@SettingsActivity, "Создание бэкапа...", Toast.LENGTH_SHORT).show()
            val db = AppDatabase.getInstance(this@SettingsActivity)
            val backupManager = BackupManager(this@SettingsActivity)
            val folderName = tokenStorage.getFolderName()

            val success = if (local) {
                val file = backupManager.exportToLocal(db, folderName)
                file != null
            } else {
                backupManager.exportToCloud(db, folderName, tokenStorage)
            }

            if (success) {
                Toast.makeText(this@SettingsActivity, "✅ Бэкап создан", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SettingsActivity, "❌ Ошибка создания бэкапа", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error exporting backup", e)
            Toast.makeText(this@SettingsActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun showImportDialog(local: Boolean) {
    AlertDialog.Builder(this)
        .setTitle("Восстановление данных")
        .setMessage("ВНИМАНИЕ! Все текущие данные будут заменены данными из бэкапа. Продолжить?")
        .setPositiveButton("Да") { _, _ ->
            importBackup(local)
        }
        .setNegativeButton("Отмена", null)
        .show()
}

private fun importBackup(local: Boolean) {
    lifecycleScope.launch {
        try {
            Toast.makeText(this@SettingsActivity, "Восстановление...", Toast.LENGTH_SHORT).show()
            val db = AppDatabase.getInstance(this@SettingsActivity)
            val backupManager = BackupManager(this@SettingsActivity)

            val success = if (local) {
                // Показываем диалог выбора файла
                val backups = backupManager.getLocalBackups()
                if (backups.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "Нет локальных бэкапов", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val file = pickBackupFile(backups) ?: return@launch
                backupManager.importFromLocal(file, db)
            } else {
                backupManager.importFromCloud(tokenStorage, db)
            }

            if (success) {
                Toast.makeText(this@SettingsActivity, "✅ Данные восстановлены", Toast.LENGTH_SHORT).show()
                viewModel.loadContents()
            } else {
                Toast.makeText(this@SettingsActivity, "❌ Ошибка восстановления", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error importing backup", e)
            Toast.makeText(this@SettingsActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun pickBackupFile(files: List<File>): File? {
    val names = files.map { it.name }.toTypedArray()
    val index = arrayOf(-1)
    AlertDialog.Builder(this)
        .setTitle("Выберите бэкап")
        .setItems(names) { _, which ->
            index[0] = which
        }
        .setPositiveButton("OK") { _, _ ->
            // handled
        }
        .setNegativeButton("Отмена", null)
        .show()

    return if (index[0] >= 0) files[index[0]] else null
    // ============================================================
    // ВЫХОД
    // ============================================================
    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Выход")
            .setMessage("Вы уверены, что хотите выйти? Все локальные данные будут удалены.")
            .setPositiveButton("Да") { _, _ ->
                Logger.log(TAG, "Logging out...")
                logout()
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun logout() {
        try {
            tokenStorage.clear()
            
            lifecycleScope.launch {
                try {
                    val db = AppDatabase.getInstance(this@SettingsActivity)
                    db.folderDao().getAllFolders().forEach { db.folderDao().deleteFolder(it) }
                    db.itemDao().getAllItems().forEach { db.itemDao().deleteItem(it) }
                    db.lockDao().deleteAllLocks()
                    db.syncQueueDao().clearAll()
                    Logger.log(TAG, "Database cleared")
                    
                    val imagesDir = java.io.File(filesDir, "images")
                    if (imagesDir.exists()) {
                        imagesDir.deleteRecursively()
                        Logger.log(TAG, "Local images deleted")
                    }
                } catch (e: Exception) {
                    Logger.log(TAG, "Error clearing database", e)
                }
            }

            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
            
            Logger.log(TAG, "Logout completed")
        } catch (e: Exception) {
            Logger.log(TAG, "Error during logout", e)
            Toast.makeText(this, "Ошибка выхода", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // ПРОВЕРКА ОБНОВЛЕНИЙ
    // ============================================================
    private fun checkForUpdates() {
        val currentVersionCode = BuildConfig.VERSION_CODE
        val currentVersionName = BuildConfig.VERSION_NAME

        Logger.log(TAG, "Checking for updates. Current version: $currentVersionName ($currentVersionCode)")
        Toast.makeText(this, "Проверка обновлений...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val updateManager = UpdateManager(this@SettingsActivity)
                val latestVersion = updateManager.checkForUpdate(currentVersionCode)

                if (latestVersion != null && latestVersion.versionCode > currentVersionCode) {
                    Logger.log(TAG, "Update available: ${latestVersion.versionName} (${latestVersion.versionCode})")
                    showUpdateDialog(latestVersion)
                } else {
                    Logger.log(TAG, "No updates available")
                    Toast.makeText(
                        this@SettingsActivity,
                        "У вас последняя версия ($currentVersionName)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error checking for updates", e)
                Toast.makeText(
                    this@SettingsActivity,
                    "Ошибка проверки обновлений: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showUpdateDialog(version: AppVersion) {
        AlertDialog.Builder(this)
            .setTitle("📱 Доступно обновление!")
            .setMessage(
                """
                Версия: ${version.versionName}
                
                Что нового:
                ${version.releaseNotes ?: "• Исправлены ошибки\n• Улучшена производительность"}
                """.trimIndent()
            )
            .setPositiveButton("Обновить") { _, _ ->
                Logger.log(TAG, "User clicked 'Update'")
                val updateManager = UpdateManager(this)
                updateManager.downloadAndInstall(version.downloadUrl, version.versionName)
                Toast.makeText(this, "Загрузка началась...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Позже") { _, _ ->
                Logger.log(TAG, "User clicked 'Later'")
            }
            .show()
    }

    // ============================================================
    // ПОДЕЛИТЬСЯ ССЫЛКОЙ (НОВЫЙ МЕТОД)
    // ============================================================
    private fun shareFolderLink() {
        val link = tokenStorage.getSharedFolderLink()
        if (link != null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Присоединяйтесь к общей папке БАЗА: $link")
            }
            startActivity(Intent.createChooser(intent, "Поделиться ссылкой"))
        } else {
            Toast.makeText(this, "Ссылка не найдена. Сначала настройте общую папку.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

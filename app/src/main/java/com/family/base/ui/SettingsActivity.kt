package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.family.base.util.BackupManagerV2
import com.family.base.util.Logger
import com.family.base.util.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

        setupListeners()
        loadSettings()
        showCurrentVersion()
        setupBackupListeners()

        Logger.log(TAG, "=== SettingsActivity onCreate FINISHED ===")
    }

    private fun showCurrentVersion() {
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        binding.tvVersion.text = "Версия $versionName (код $versionCode)"
    }

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

        // Полная очистка
        binding.btnClearAllData.setOnClickListener {
            Logger.log(TAG, "Clear all data clicked")
            showClearAllDataDialog()
        }

        // Логирование
        binding.switchLogging.setOnCheckedChangeListener { _, isChecked ->
            Logger.log(TAG, "Logging enabled: $isChecked")
            Logger.setEnabled(isChecked)
            saveSettings()
        }

        // Проверка обновлений
        binding.btnCheckUpdate.setOnClickListener {
            Logger.log(TAG, "Check for updates clicked")
            checkForUpdates()
        }

        // Поделиться ссылкой
        binding.btnShareLink.setOnClickListener {
            Logger.log(TAG, "Share link clicked")
            shareFolderLink()
        }

        // Статистика
        binding.btnStatistics.setOnClickListener {
            Logger.log(TAG, "Statistics clicked")
            startActivity(Intent(this, StatisticsActivity::class.java))
        }

        // Архив
        binding.btnArchive.setOnClickListener {
            Logger.log(TAG, "Archive clicked")
            startActivity(Intent(this, ArchiveActivity::class.java))
        }

        // Выданные предметы
        binding.btnLentItems.setOnClickListener {
            Logger.log(TAG, "Lent items clicked")
            startActivity(Intent(this, LentItemsActivity::class.java))
        }

        // ===== СВОРАЧИВАНИЕ ОПАСНОЙ ЗОНЫ =====
        binding.dangerZoneHeader.setOnClickListener {
            val isVisible = binding.dangerZoneContent.visibility == View.VISIBLE
            if (isVisible) {
                binding.dangerZoneContent.visibility = View.GONE
                binding.dangerZoneArrow.text = "▼"
                Logger.log(TAG, "Danger zone collapsed")
            } else {
                binding.dangerZoneContent.visibility = View.VISIBLE
                binding.dangerZoneArrow.text = "▲"
                Logger.log(TAG, "Danger zone expanded")
            }
        }
    }

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

    // ===== РЕЗЕРВНОЕ КОПИРОВАНИЕ =====

    private fun exportBackup(local: Boolean) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@SettingsActivity, "Создание бэкапа...", Toast.LENGTH_SHORT).show()
                val db = AppDatabase.getInstance(this@SettingsActivity)
                val backupManager = BackupManagerV2(this@SettingsActivity)
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
                val backupManager = BackupManagerV2(this@SettingsActivity)

                val success = if (local) {
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
        var selectedIndex = -1
        AlertDialog.Builder(this)
            .setTitle("Выберите бэкап")
            .setItems(names) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("OK") { _, _ -> }
            .setNegativeButton("Отмена", null)
            .show()
        return if (selectedIndex >= 0) files[selectedIndex] else null
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
        } catch (e: Exception) {
            Logger.log(TAG, "Error loading settings", e)
        }
    }

    private fun saveSettings() {
        try {
            val prefs = getSharedPreferences("baza_settings", MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("logging_enabled", binding.switchLogging.isChecked)
                apply()
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error saving settings", e)
        }
    }

    // ============================================================
    // ОЧИСТКА КЭША И ЛОГОВ
    // ============================================================

    private fun showClearCacheDialog() {
        AlertDialog.Builder(this)
            .setTitle("Очистить кэш")
            .setMessage("Удалить все загруженные изображения?")
            .setPositiveButton("Да") { _, _ ->
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
        } catch (e: Exception) {
            Logger.log(TAG, "Error clearing cache", e)
        }
    }

    private fun showClearLogsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Очистить логи")
            .setMessage("Удалить все файлы логов?")
            .setPositiveButton("Да") { _, _ ->
                Logger.clearLogs()
                Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun sendLogs() {
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
                val uris = logFiles.map { file -> android.net.Uri.fromFile(file) }
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }

            startActivity(Intent.createChooser(intent, "Отправить логи"))
        } catch (e: Exception) {
            Logger.log(TAG, "Error sending logs", e)
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
                if (input == randomWord) clearAllData()
                else Toast.makeText(this, "Неверное слово", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun generateRandomWord(): String {
        return listOf("DELETE", "CLEAR", "REMOVE", "ERASE", "RESET", "PURGE", "WIPE").random()
    }

    private fun clearAllData() {
        Toast.makeText(this, "Очистка данных...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val db = AppDatabase.getInstance(this@SettingsActivity)
                db.close()

                getDatabasePath("baza.db").delete()
                getDatabasePath("baza.db-wal").delete()
                getDatabasePath("baza.db-shm").delete()

                AppDatabase.resetInstance()

                java.io.File(filesDir, "images").deleteRecursively()

                val token = tokenStorage.getAccessToken()
                if (token != null) {
                    try {
                        val api = YandexDiskApi.getInstance()
                        val auth = "OAuth $token"
                        val folderName = tokenStorage.getFolderName() ?: "BAZA"
                        val rootPath = "/$folderName"

                        api.deleteFile(auth, "$rootPath/data", true)
                        api.deleteFile(auth, "$rootPath/images", true)
                        api.deleteFile(auth, "$rootPath/.last_modified", true)
                    } catch (e: Exception) {
                        Logger.log(TAG, "Error clearing disk", e)
                    }
                }

                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
                Logger.clearLogs()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "✅ Все данные очищены", Toast.LENGTH_LONG).show()
                    val intent = Intent(this@SettingsActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error clearing all data", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ============================================================
    // ВЫХОД
    // ============================================================

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Выход")
            .setMessage("Вы уверены, что хотите выйти? Все локальные данные будут удалены.")
            .setPositiveButton("Да") { _, _ -> logout() }
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
                    db.itemDao().getAllItemsRaw().forEach { db.itemDao().deleteItem(it) }
                    db.lockDao().deleteAllLocks()
                    db.syncQueueDao().clearAll()

                    java.io.File(filesDir, "images").deleteRecursively()
                } catch (e: Exception) {
                    Logger.log(TAG, "Error clearing database", e)
                }
            }

            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
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

        Toast.makeText(this, "Проверка обновлений...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val updateManager = UpdateManager(this@SettingsActivity)
                val latestVersion = updateManager.checkForUpdate(currentVersionCode)

                if (latestVersion != null && latestVersion.versionCode > currentVersionCode) {
                    showUpdateDialog(latestVersion)
                } else {
                    Toast.makeText(
                        this@SettingsActivity,
                        "У вас последняя версия ($currentVersionName)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error checking for updates", e)
                Toast.makeText(this@SettingsActivity, "Ошибка проверки: ${e.message}", Toast.LENGTH_SHORT).show()
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
                val updateManager = UpdateManager(this)
                updateManager.downloadAndInstall(version.downloadUrl, version.versionName)
                Toast.makeText(this, "Загрузка началась...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    // ============================================================
    // ПОДЕЛИТЬСЯ ССЫЛКОЙ
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
            Toast.makeText(this, "Ссылка не найдена", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

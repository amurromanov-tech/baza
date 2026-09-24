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

        setupListeners()
        showCurrentVersion()

        Logger.log(TAG, "=== SettingsActivity onCreate FINISHED ===")
    }

    private fun showCurrentVersion() {
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        binding.tvVersion.text = "Версия $versionName (код $versionCode)"
    }

    // ============================================================
    // СЛУШАТЕЛИ
    // ============================================================
    private fun setupListeners() {
        Logger.log(TAG, "Setting up listeners")

        // ===== СИНХРОНИЗАЦИЯ =====
        binding.btnSyncNow.setOnClickListener {
            Logger.log(TAG, "Sync now clicked")
            Toast.makeText(this, "Синхронизация запущена...", Toast.LENGTH_SHORT).show()
            viewModel.forceSync()
        }

        // ===== ПОДЕЛИТЬСЯ ССЫЛКОЙ =====
        binding.btnShareLink.setOnClickListener {
            Logger.log(TAG, "Share link clicked")
            shareFolderLink()
        }

        // ===== РАЗДЕЛЫ =====
        binding.btnStatsAndAccounting.setOnClickListener {
            Logger.log(TAG, "Stats and accounting clicked")
            startActivity(Intent(this, StatsAndAccountingActivity::class.java))
        }

        binding.btnBackup.setOnClickListener {
            Logger.log(TAG, "Backup clicked")
            startActivity(Intent(this, BackupActivity::class.java))
        }

        binding.btnDiagnostics.setOnClickListener {
            Logger.log(TAG, "Diagnostics clicked")
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        binding.btnAppSettings.setOnClickListener {
            Logger.log(TAG, "App settings clicked")
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }

        // ===== ОБНОВЛЕНИЯ =====
        binding.btnCheckUpdate.setOnClickListener {
            Logger.log(TAG, "Check for updates clicked")
            checkForUpdates()
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

        // ===== ОЧИСТКА КЭША =====
        binding.btnClearCache.setOnClickListener {
            Logger.log(TAG, "Clear cache clicked")
            showClearCacheDialog()
        }

        // ===== ПОЛНАЯ ОЧИСТКА ДАННЫХ =====
        binding.btnClearAllData.setOnClickListener {
            Logger.log(TAG, "Clear all data clicked")
            showClearAllDataDialog()
        }

        // ===== ВЫХОД =====
        binding.btnLogout.setOnClickListener {
            Logger.log(TAG, "Logout clicked")
            showLogoutDialog()
        }
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

    // ============================================================
    // ОЧИСТКА КЭША
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
            Logger.log(TAG, "Cache cleared successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "Error clearing cache", e)
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
        Logger.log(TAG, "=== CLEAR ALL DATA START ===")
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
                lifecycleScope.launch {
                    try {
                        updateManager.downloadAndInstall(version.downloadUrl, version.versionName)
                    } catch (e: Exception) {
                        Logger.log(TAG, "Error starting download", e)
                        Toast.makeText(
                            this@SettingsActivity,
                            "Ошибка загрузки: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                Toast.makeText(this, "Загрузка началась...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

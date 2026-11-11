package com.family.base.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.family.base.AppUser
import com.family.base.BuildConfig
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.databinding.ActivityBackupBinding
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.BackupManagerV2
import com.family.base.util.FullBackupManager
import com.family.base.util.FullBackupRestoreManager
import com.family.base.util.Logger
import kotlinx.coroutines.launch
import java.io.File

/**
 * 🆕 v21.0.1: Полный бэкап (ZIP с фото) и восстановление из него.
 *
 * Два режима, передаются через EXTRA_MODE:
 *   - MODE_FULL_EXPORT — сразу открыть диалог экспорта полного бэкапа.
 *   - MODE_FULL_IMPORT — сразу открыть диалог импорта (ACTION_OPEN_DOCUMENT).
 *
 * 🆕 v21.0.1 (fix Android 11+): ensureStoragePermission() —
 * перед экспортом/импортом проверяет Environment.isExternalStorageManager().
 * Если разрешения нет — открывает Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
 * и показывает Toast. После возврата пользователь жмёт кнопку снова.
 * На Android 10 и ниже проверка всегда проходит.
 *
 * 🆕 v21.0.2 (UI): блок полного бэкапа сворачиваемый —
 * по умолчанию кнопки скрыты, по нажатию на заголовок раскрываются/сворачиваются.
 */
class BackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupBinding
    private lateinit var tokenStorage: TokenStorage
    private lateinit var viewModel: MainViewModel
    private val TAG = "BackupActivity"

    // 🆕 v21.0.1: диалог прогресса
    private var progressDialog: AlertDialog? = null
    private var progressBarRef: ProgressBar? = null
    private var progressPercentRef: TextView? = null
    private var progressTitleRef: TextView? = null

    // 🆕 v21.0.2: состояние сворачиваемого блока полного бэкапа
    private var fullBackupExpanded = false

    // 🆕 v21.0.1: launcher для выбора ZIP при импорте
    private val importZipLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleImportZip(uri) }
        }
    }

    // 🆕 v21.0.1: launcher для открытия настроек разрешения.
    // Результат не важен — пользователь вернётся и нажмёт кнопку снова.
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = FullBackupManager.hasStoragePermission()
        Toast.makeText(
            this,
            if (granted) "✅ Разрешение получено, повторите действие"
            else "⚠️ Разрешение не выдано",
            Toast.LENGTH_LONG
        ).show()
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_FULL_EXPORT = "full_export"
        const val MODE_FULL_IMPORT = "full_import"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== BackupActivity onCreate START ===")

        try {
            binding = ActivityBackupBinding.inflate(layoutInflater)
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

        binding.btnBack.setOnClickListener { finish() }

        binding.btnExportLocal.setOnClickListener {
            Logger.log(TAG, "Export local clicked")
            exportBackup(local = true)
        }

        val currentUser = tokenStorage.getCurrentUser()
        val isMaster = AppUser.canDelete(currentUser)
        binding.btnExportCloud.isEnabled = isMaster
        binding.btnExportCloud.alpha = if (isMaster) 1.0f else 0.5f
        Logger.log(TAG, "Export to cloud enabled=$isMaster (user=$currentUser)")

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

        applyFullBackupPermissions()

        val mode = intent.getStringExtra(EXTRA_MODE)
        when (mode) {
            MODE_FULL_EXPORT -> {
                Logger.log(TAG, "Auto mode: full export")
                startFullExport()
            }
            MODE_FULL_IMPORT -> {
                Logger.log(TAG, "Auto mode: full import")
                startFullImport()
            }
        }

        Logger.log(TAG, "=== BackupActivity onCreate FINISHED ===")
    }

    /**
     * 🆕 v21.0.1: блок полного бэкапа виден только Алексею.
     * 🆕 v21.0.2: кнопки внутри блока свёрнуты по умолчанию,
     * раскрываются/сворачиваются по нажатию на заголовок llFullBackupHeader.
     */
    private fun applyFullBackupPermissions() {
        val currentUser = tokenStorage.getCurrentUser()
        val isAdmin = AppUser.canDelete(currentUser)
        binding.llFullBackupBlock.visibility = if (isAdmin) View.VISIBLE else View.GONE
        Logger.log(TAG, "Full backup block visible=$isAdmin (user=$currentUser)")

        // 🆕 v21.0.2: стартовое состояние — свёрнуто
        fullBackupExpanded = false
        binding.llFullBackupButtons.visibility = View.GONE
        binding.tvFullBackupChevron.text = "▼"

        // 🆕 v21.0.2: клик по заголовку — раскрыть/свернуть кнопки
        binding.llFullBackupHeader.setOnClickListener {
            fullBackupExpanded = !fullBackupExpanded
            binding.llFullBackupButtons.visibility =
                if (fullBackupExpanded) View.VISIBLE else View.GONE
            binding.tvFullBackupChevron.text = if (fullBackupExpanded) "▲" else "▼"
            Logger.log(TAG, "Full backup block expanded=$fullBackupExpanded")
        }

        binding.btnFullBackupExport.setOnClickListener {
            Logger.log(TAG, "Full backup export clicked")
            if (!AppUser.canDelete(tokenStorage.getCurrentUser())) {
                Toast.makeText(this, "Только Алексей может делать полный бэкап", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startFullExport()
        }

        binding.btnFullBackupImport.setOnClickListener {
            Logger.log(TAG, "Full backup import clicked")
            if (!AppUser.canDelete(tokenStorage.getCurrentUser())) {
                Toast.makeText(this, "Только Алексей может восстанавливать из бэкапа", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startFullImport()
        }
    }

    // ============================================================
    // 🆕 v21.0.1: ПРОВЕРКА РАЗРЕШЕНИЯ НА ЗАПИСЬ В Download/
    // ============================================================

    /**
     * Гарантирует, что есть MANAGE_EXTERNAL_STORAGE (Android 11+).
     *
     * Если разрешения нет — открывает настройки и возвращает false.
     * Пользователь возвращается и нажимает кнопку снова.
     * На Android 10 и ниже возвращает true (requestLegacyExternalStorage).
     */
    private fun ensureStoragePermission(): Boolean {
        if (FullBackupManager.hasStoragePermission()) return true

        AlertDialog.Builder(this)
            .setTitle("⚠️ Нужен доступ к файлам")
            .setMessage(
                "Для сохранения полного бэкапа в Download/BAZA/full_backup/ " +
                    "приложению нужно разрешение «Доступ ко всем файлам».\n\n" +
                    "Открыть настройки и выдать разрешение?"
            )
            .setPositiveButton("Открыть настройки") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    storagePermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    Logger.log(TAG, "ensureStoragePermission: cannot open settings: ${e.message}", e)
                    Toast.makeText(
                        this,
                        "Не удалось открыть настройки. Откройте вручную: Настройки → Приложения → БАЗА → Разрешения → Все файлы",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()

        return false
    }

    // ============================================================
    // ОБЫЧНЫЙ ЭКСПОРТ (JSON)
    // ============================================================
    private fun exportBackup(local: Boolean) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@BackupActivity, "Создание бэкапа...", Toast.LENGTH_SHORT).show()
                val db = AppDatabase.getInstance(this@BackupActivity)
                val backupManager = BackupManagerV2(this@BackupActivity)
                val folderName = tokenStorage.getFolderName()

                val success = if (local) {
                    val file = backupManager.exportToLocal(db, folderName)
                    file != null
                } else {
                    backupManager.exportToCloud(db, folderName, tokenStorage)
                }

                if (success) {
                    Toast.makeText(
                        this@BackupActivity,
                        if (local) "✅ Бэкап сохранён в Download/BAZA/backup"
                        else "✅ Бэкап загружен в облако",
                        Toast.LENGTH_LONG
                    ).show()
                    Logger.log(TAG, "Backup exported successfully (local=$local)")
                } else {
                    Toast.makeText(this@BackupActivity, "❌ Ошибка создания бэкапа", Toast.LENGTH_SHORT).show()
                    Logger.log(TAG, "Backup export failed")
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error exporting backup", e)
                Toast.makeText(this@BackupActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // ОБЫЧНЫЙ ИМПОРТ (JSON)
    // ============================================================
    private fun showImportDialog(local: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("Восстановление данных")
            .setMessage("ВНИМАНИЕ! Все текущие данные будут заменены данными из бэкапа. Продолжить?")
            .setPositiveButton("Да") { _, _ ->
                if (local) showLocalBackupPicker() else importFromCloud()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showLocalBackupPicker() {
        val backupManager = BackupManagerV2(this)
        val backups = backupManager.getLocalBackups()

        if (backups.isEmpty()) {
            Toast.makeText(this, "Нет локальных бэкапов", Toast.LENGTH_SHORT).show()
            return
        }

        val names = backups.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Выберите бэкап")
            .setItems(names) { _, which ->
                val file = backups[which]
                importFromLocal(file)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun importFromLocal(file: File) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@BackupActivity, "Восстановление...", Toast.LENGTH_SHORT).show()
                val db = AppDatabase.getInstance(this@BackupActivity)
                val backupManager = BackupManagerV2(this@BackupActivity)

                val success = backupManager.importFromLocal(file, db)

                if (success) {
                    Toast.makeText(this@BackupActivity, "✅ Данные восстановлены", Toast.LENGTH_LONG).show()
                    Logger.log(TAG, "Import from local completed: ${file.name}")
                    viewModel.loadContents()
                    finish()
                } else {
                    Toast.makeText(this@BackupActivity, "❌ Ошибка восстановления", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error importing from local", e)
                Toast.makeText(this@BackupActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importFromCloud() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@BackupActivity, "Восстановление из облака...", Toast.LENGTH_SHORT).show()
                val db = AppDatabase.getInstance(this@BackupActivity)
                val backupManager = BackupManagerV2(this@BackupActivity)

                val success = backupManager.importFromCloud(tokenStorage, db)

                if (success) {
                    Toast.makeText(this@BackupActivity, "✅ Данные восстановлены из облака", Toast.LENGTH_LONG).show()
                    Logger.log(TAG, "Import from cloud completed")
                    viewModel.loadContents()
                    finish()
                } else {
                    Toast.makeText(this@BackupActivity, "❌ Ошибка восстановления из облака", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error importing from cloud", e)
                Toast.makeText(this@BackupActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // 🆕 v21.0.1: ПОЛНЫЙ БЭКАП
    // ============================================================

    private fun startFullExport() {
        // 🆕 v21.0.1: сначала разрешение на запись в Download/
        if (!ensureStoragePermission()) return

        val currentUser = tokenStorage.getCurrentUser() ?: "unknown"
        showProgressDialog("Подготовка полного бэкапа…")

        lifecycleScope.launch {
            try {
                val db = AppDatabase.getInstance(this@BackupActivity)
                val manager = FullBackupManager(this@BackupActivity)

                val result = manager.exportFullBackup(
                    db = db,
                    userName = currentUser,
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                    onProgress = { percent, text -> updateProgress(percent, text) },
                    checkCancel = { false }
                )

                hideProgressDialog()

                if (result.success && result.file != null) {
                    val size = FullBackupManager.formatFileSize(result.file.length())
                    AlertDialog.Builder(this@BackupActivity)
                        .setTitle("✅ Полный бэкап готов")
                        .setMessage(
                            "Файл: ${result.file.name}\n" +
                                "Размер: $size\n" +
                                "Предметов: ${result.manifest?.itemCount ?: 0}\n" +
                                "Папок: ${result.manifest?.folderCount ?: 0}\n" +
                                "Задач: ${result.manifest?.taskCount ?: 0}\n" +
                                "Фото: ${result.manifest?.imageCount ?: 0}\n" +
                                "Иконок папок: ${result.manifest?.folderIconCount ?: 0}\n\n" +
                                "Сохранён в:\nDownload/BAZA/full_backup/"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                    Logger.log(TAG, "Full backup exported: ${result.file.absolutePath}")
                } else {
                    Toast.makeText(
                        this@BackupActivity,
                        "❌ Ошибка: ${result.error ?: "неизвестно"}",
                        Toast.LENGTH_LONG
                    ).show()
                    Logger.log(TAG, "Full backup export failed: ${result.error}")
                }
            } catch (e: Exception) {
                hideProgressDialog()
                Logger.log(TAG, "Error full export", e)
                Toast.makeText(this@BackupActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startFullImport() {
        // 🆕 v21.0.1: сначала разрешение на запись в Download/ (для pre-backup).
        if (!ensureStoragePermission()) return

        AlertDialog.Builder(this)
            .setTitle("⚠️ Восстановление из полного бэкапа")
            .setMessage(
                "ВНИМАНИЕ! Импорт ПОЛНОСТЬЮ перезапишет все данные:\n" +
                    "• предметы\n" +
                    "• папки\n" +
                    "• задачи\n" +
                    "• историю\n" +
                    "• настройки\n" +
                    "• фотографии\n\n" +
                    "Перед импортом автоматически будет создан бэкап текущего состояния.\n\n" +
                    "Продолжить?"
            )
            .setPositiveButton("Выбрать файл…") { _, _ -> openZipPicker() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openZipPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        importZipLauncher.launch(intent)
    }

    private fun handleImportZip(uri: Uri) {
        val currentUser = tokenStorage.getCurrentUser() ?: "unknown"
        showProgressDialog("Чтение ZIP…")

        lifecycleScope.launch {
            try {
                val tempFile = File(cacheDir, "import_${System.currentTimeMillis()}.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Не удалось открыть файл")

                Logger.log(TAG, "Import ZIP staged: ${tempFile.absolutePath} (${tempFile.length()} bytes)")

                val db = AppDatabase.getInstance(this@BackupActivity)
                val manager = FullBackupRestoreManager(this@BackupActivity)

                val result = manager.restore(
                    zipFile = tempFile,
                    db = db,
                    currentUserName = currentUser,
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                    onProgress = { percent, text -> updateProgress(percent, text) }
                )

                hideProgressDialog()

                try { tempFile.delete() } catch (_: Exception) {}

                if (result.success) {
                    AlertDialog.Builder(this@BackupActivity)
                        .setTitle("✅ Восстановление завершено")
                        .setMessage(
                            "Предметов: ${result.manifest?.itemCount ?: 0}\n" +
                                "Папок: ${result.manifest?.folderCount ?: 0}\n" +
                                "Задач: ${result.manifest?.taskCount ?: 0}\n" +
                                "Фото: ${result.manifest?.imageCount ?: 0}\n\n" +
                                (result.preRestoreBackup?.let {
                                    "Старое состояние сохранено в:\n${it.name}\n\n"
                                } ?: "") +
                                "Рекомендуется запустить синхронизацию (🔄), чтобы подтянуть свежие данные с Диска."
                        )
                        .setPositiveButton("OK") { _, _ ->
                            viewModel.loadContents()
                            finish()
                        }
                        .setCancelable(false)
                        .show()
                    Logger.log(TAG, "Restore completed, pre-backup=${result.preRestoreBackup?.absolutePath}")
                } else {
                    Toast.makeText(
                        this@BackupActivity,
                        "❌ Ошибка восстановления: ${result.error ?: "неизвестно"}",
                        Toast.LENGTH_LONG
                    ).show()
                    Logger.log(TAG, "Restore failed: ${result.error}")
                }
            } catch (e: Exception) {
                hideProgressDialog()
                Logger.log(TAG, "Error importing ZIP", e)
                Toast.makeText(this@BackupActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // 🆕 v21.0.1: ДИАЛОГ ПРОГРЕССА (через поля, а не tags)
    // ============================================================

    private fun showProgressDialog(title: String) {
        hideProgressDialog()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val tvTitle = TextView(this).apply { text = title; textSize = 14f }
        container.addView(tvTitle)

        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        container.addView(pb)

        val tvPercent = TextView(this).apply {
            text = "0%"
            textSize = 12f
        }
        container.addView(tvPercent)

        progressTitleRef = tvTitle
        progressBarRef = pb
        progressPercentRef = tvPercent

        progressDialog = AlertDialog.Builder(this)
            .setTitle("Полный бэкап")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("Отмена", null)
            .create()

        progressDialog?.show()
    }

    private fun updateProgress(percent: Int, text: String) {
        progressBarRef?.progress = percent
        progressPercentRef?.text = "$percent%"
        progressTitleRef?.text = text
    }

    private fun hideProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
        progressBarRef = null
        progressPercentRef = null
        progressTitleRef = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideProgressDialog()
        Logger.log(TAG, "onDestroy called")
    }
}

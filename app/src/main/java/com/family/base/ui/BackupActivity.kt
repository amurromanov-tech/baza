package com.family.base.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.databinding.ActivityBackupBinding
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.BackupManagerV2
import com.family.base.util.Logger
import kotlinx.coroutines.launch
import java.io.File

class BackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupBinding
    private lateinit var tokenStorage: TokenStorage
    private lateinit var viewModel: MainViewModel
    private val TAG = "BackupActivity"

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

        Logger.log(TAG, "=== BackupActivity onCreate FINISHED ===")
    }

    // ============================================================
    // ЭКСПОРТ
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
    // ИМПОРТ
    // ============================================================
    private fun showImportDialog(local: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("Восстановление данных")
            .setMessage("ВНИМАНИЕ! Все текущие данные будут заменены данными из бэкапа. Продолжить?")
            .setPositiveButton("Да") { _, _ ->
                if (local) {
                    // Сначала показываем список локальных бэкапов
                    showLocalBackupPicker()
                } else {
                    // Импорт из облака
                    importFromCloud()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ===== СПИСОК ЛОКАЛЬНЫХ БЭКАПОВ =====
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

    // ===== ИМПОРТ ИЗ ЛОКАЛЬНОГО ФАЙЛА =====
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

    // ===== ИМПОРТ ИЗ ОБЛАКА =====
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

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

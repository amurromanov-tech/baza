package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.base.BaseApplication
import com.family.base.data.TokenStorage
import com.family.base.databinding.ActivitySettingsBinding
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.Logger

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
            viewModel = BaseApplication.mainViewModel
            Logger.log(TAG, "TokenStorage and ViewModel initialized")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to initialize", e)
            return
        }

        observeSyncResult()
        setupListeners()

        Logger.log(TAG, "=== SettingsActivity onCreate FINISHED ===")
    }

    private fun observeSyncResult() {
        viewModel.syncResultMessage.observe(this) { message ->
            if (!message.isNullOrEmpty()) {
                Logger.log(TAG, "Sync result: $message")
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupListeners() {
        Logger.log(TAG, "Setting up listeners")

        binding.btnBack.setOnClickListener { finish() }

        binding.btnSyncNow.setOnClickListener {
            Logger.log(TAG, "Sync now clicked")
            viewModel.forceSync()
        }

        binding.btnShareLink.setOnClickListener {
            Logger.log(TAG, "Share link clicked")
            shareFolderLink()
        }

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

        binding.btnAbout.setOnClickListener {
            Logger.log(TAG, "About clicked")
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

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

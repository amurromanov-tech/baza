package com.family.base.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.family.base.BuildConfig
import com.family.base.data.TokenStorage
import com.family.base.data.remote.AppVersion
import com.family.base.databinding.ActivityAboutBinding
import com.family.base.util.Logger
import com.family.base.util.UpdateManager
import kotlinx.coroutines.launch

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private lateinit var tokenStorage: TokenStorage
    private val TAG = "AboutActivity"

    private val DEVELOPER_EMAIL = "Romanov_sa@mail.ru"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== AboutActivity onCreate START ===")

        try {
            binding = ActivityAboutBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Binding inflated successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        try {
            tokenStorage = TokenStorage(this)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to initialize", e)
            return
        }

        showVersionInfo()
        setupListeners()

        Logger.log(TAG, "=== AboutActivity onCreate FINISHED ===")
    }

    // ============================================================
    // ВЕРСИЯ
    // ============================================================
    private fun showVersionInfo() {
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE

        binding.tvVersion.text = "v$versionName"
        binding.tvVersionInfo.text = "Текущая версия: v$versionName (код $versionCode)"

        Logger.log(TAG, "Version shown: v$versionName ($versionCode)")
    }

    // ============================================================
    // СЛУШАТЕЛИ
    // ============================================================
    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.tvEmail.setOnClickListener {
            Logger.log(TAG, "Email clicked")
            openEmail()
        }

        binding.btnCheckUpdate.setOnClickListener {
            Logger.log(TAG, "Check update clicked")
            checkForUpdates()
        }

        binding.btnLogout.setOnClickListener {
            Logger.log(TAG, "Logout clicked")
            showLogoutDialog()
        }
    }

    // ============================================================
    // EMAIL
    // ============================================================
    private fun openEmail() {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$DEVELOPER_EMAIL")
                putExtra(Intent.EXTRA_SUBJECT, "БАЗА — обратная связь")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Logger.log(TAG, "No email client, copying to clipboard", e)
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Email", DEVELOPER_EMAIL)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Email скопирован: $DEVELOPER_EMAIL", Toast.LENGTH_LONG).show()
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
                val updateManager = UpdateManager(this@AboutActivity)
                val latestVersion = updateManager.checkForUpdate(currentVersionCode)

                if (latestVersion != null && latestVersion.versionCode > currentVersionCode) {
                    showUpdateDialog(latestVersion)
                } else {
                    Toast.makeText(
                        this@AboutActivity,
                        "У вас последняя версия ($currentVersionName)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error checking for updates", e)
                Toast.makeText(this@AboutActivity, "Ошибка проверки: ${e.message}", Toast.LENGTH_SHORT).show()
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
                            this@AboutActivity,
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

    // ============================================================
    // ВЫХОД ИЗ АККАУНТА
    // ============================================================
    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Выход из аккаунта")
            .setMessage(
                "Вы выходите из аккаунта Яндекс.Диска.\n\n" +
                "Локальные данные (каталог, предметы, фото) СОХРАНЯТСЯ на устройстве. " +
                "После повторного входа в ту же папку они будут доступны.\n\n" +
                "Продолжить?"
            )
            .setPositiveButton("Выйти") { _, _ -> logout() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Logout: стираем только токены и метаданные сессии.
     * Локальная БД (folders, items, history, images) НЕ трогается.
     */
    private fun logout() {
        try {
            Logger.log(TAG, "=== LOGOUT START (local DB preserved) ===")

            tokenStorage.clear()

            Logger.log(TAG, "TokenStorage cleared. Local DB and images preserved.")

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

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

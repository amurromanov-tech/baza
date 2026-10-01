package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.family.base.data.TokenStorage
import com.family.base.data.remote.YandexDiskApi
import com.family.base.databinding.ActivityConnectFamilyBinding
import com.family.base.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectFamilyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectFamilyBinding
    private lateinit var tokenStorage: TokenStorage
    private val TAG = "ConnectFamilyActivity"

    // ===== ЗАПУСК ЭКРАНА ВЫБОРА ПОЛЬЗОВАТЕЛЯ =====
    private val selectUserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Logger.log(TAG, "SelectUserActivity result: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK)
            finish()
        } else {
            // Пользователь не выбрал — остаёмся на этом экране
            Logger.log(TAG, "User not selected, staying on ConnectFamilyActivity")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== ConnectFamilyActivity onCreate START ===")

        try {
            binding = ActivityConnectFamilyBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Layout inflated successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        try {
            tokenStorage = TokenStorage(this)
            Logger.log(TAG, "TokenStorage initialized")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to initialize TokenStorage", e)
            return
        }

        setupListeners()
        Logger.log(TAG, "=== ConnectFamilyActivity onCreate FINISHED ===")
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener {
            Logger.log(TAG, "=== Save button clicked ===")
            saveLink()
        }
    }

    private fun saveLink() {
        val link = binding.etLink.text.toString().trim()
        Logger.log(TAG, "Link from EditText: '$link' (length: ${link.length})")

        if (link.isEmpty()) {
            Toast.makeText(this, "Введите ссылку на папку", Toast.LENGTH_SHORT).show()
            return
        }

        val publicKey = extractPublicKeyFromLink(link)
        if (publicKey == null) {
            Logger.log(TAG, "Invalid link format")
            Toast.makeText(this, "Неверный формат ссылки", Toast.LENGTH_SHORT).show()
            return
        }

        Logger.log(TAG, "Extracted public key: $publicKey")

        try {
            tokenStorage.savePublicKey(publicKey)
            tokenStorage.saveSharedFolderLink(link)
            Logger.log(TAG, "Public key and link saved")

            // ===== ПОЛУЧАЕМ ИМЯ ПАПКИ ЧЕРЕЗ API =====
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val api = YandexDiskApi.getInstance()
                    val response = api.getPublicResources(publicKey)

                    Logger.log(TAG, "getPublicResources response: code=${response.code()}")

                    if (response.isSuccessful) {
                        val body = response.body()
                        val folderName = body?.name ?: body?.path?.substringAfterLast("/") ?: "BAZA"
                        Logger.log(TAG, "Folder name from API: $folderName")
                        Logger.log(TAG, "Folder path from API: ${body?.path}")

                        withContext(Dispatchers.Main) {
                            tokenStorage.saveFolderName(folderName)
                            Logger.log(TAG, "Folder name saved: $folderName")
                            openSelectUser()
                        }
                    } else {
                        Logger.log(TAG, "API error: ${response.code()}, using default BAZA")
                        withContext(Dispatchers.Main) {
                            tokenStorage.saveFolderName("BAZA")
                            openSelectUser()
                        }
                    }
                } catch (e: Exception) {
                    Logger.log(TAG, "Error getting folder name: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        tokenStorage.saveFolderName("BAZA")
                        openSelectUser()
                    }
                }
            }

        } catch (e: Exception) {
            Logger.log(TAG, "Error saving public key", e)
            Toast.makeText(this, "Ошибка сохранения ключа", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Открывает экран выбора пользователя после успешного ввода пути.
     */
    private fun openSelectUser() {
        Logger.log(TAG, "Opening SelectUserActivity")
        try {
            selectUserLauncher.launch(Intent(this, SelectUserActivity::class.java))
        } catch (e: Exception) {
            Logger.log(TAG, "Error launching SelectUserActivity: ${e.message}", e)
            // Фолбэк: если экран не открылся — завершаем как раньше
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun extractPublicKeyFromLink(link: String): String? {
        val trimmed = link.trim()

        val patterns = listOf(
            Regex("""(?:https?://)?(?:disk\.yandex\.ru|yadi\.sk)/d/([A-Za-z0-9_-]+)"""),
            Regex("""/d/([A-Za-z0-9_-]+)$""")
        )

        for (pattern in patterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val key = match.groupValues[1]
                if (key.isNotEmpty() && key.length > 5) {
                    return key
                }
            }
        }

        if (trimmed.matches(Regex("""^[A-Za-z0-9_-]{10,}$"""))) {
            return trimmed
        }

        return null
    }
}

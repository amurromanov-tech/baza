package com.family.base.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.base.R
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== ConnectFamilyActivity onCreate START ===")
        Logger.log(TAG, "SavedInstanceState: ${savedInstanceState != null}")

        try {
            Logger.log(TAG, "Inflating layout...")
            binding = ActivityConnectFamilyBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Layout inflated successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        try {
            Logger.log(TAG, "Initializing TokenStorage...")
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
        Logger.log(TAG, "Setting up button listener...")

        binding.btnSave.setOnClickListener {
            Logger.log(TAG, "=== Save button clicked ===")
            saveLink()
        }

        Logger.log(TAG, "Button listener set up successfully")
    }

    private fun saveLink() {
        val link = binding.etLink.text.toString().trim()
        Logger.log(TAG, "Link from EditText: '$link' (length: ${link.length})")

        if (link.isEmpty()) {
            Logger.log(TAG, "Link is empty")
            Toast.makeText(this, "Введите ссылку на папку", Toast.LENGTH_SHORT).show()
            return
        }

        Logger.log(TAG, "Extracting public key from link...")
        val publicKey = extractPublicKeyFromLink(link)

        if (publicKey == null) {
            Logger.log(TAG, "Invalid link format")
            Toast.makeText(this, "Неверный формат ссылки", Toast.LENGTH_SHORT).show()
            return
        }

        Logger.log(TAG, "Extracted public key: $publicKey")

        // Сохраняем ключ и получаем имя папки
        try {
            Logger.log(TAG, "Saving public key...")
            tokenStorage.savePublicKey(publicKey)
            Logger.log(TAG, "Public key saved successfully")

            // Получаем имя папки через API
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val api = YandexDiskApi.getInstance()
                    val response = api.getPublicResources(publicKey)
                    val folderName = if (response.isSuccessful) {
                        response.body()?.name ?: "BAZA"
                    } else {
                        "BAZA"
                    }
                    withContext(Dispatchers.Main) {
                        tokenStorage.saveFolderName(folderName)
                        Logger.log(TAG, "Folder name saved: $folderName")
                        Logger.log(TAG, "Setting result to OK and finishing")
                        setResult(RESULT_OK)
                        finish()
                    }
                } catch (e: Exception) {
                    Logger.log(TAG, "Error getting folder name: ${e.message}")
                    withContext(Dispatchers.Main) {
                        // Даже если не удалось получить имя, используем BAZA по умолчанию
                        tokenStorage.saveFolderName("BAZA")
                        Logger.log(TAG, "Using default folder name: BAZA")
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }

        } catch (e: Exception) {
            Logger.log(TAG, "Error saving public key", e)
            Toast.makeText(this, "Ошибка сохранения ключа", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractPublicKeyFromLink(link: String): String? {
        Logger.log(TAG, "extractPublicKeyFromLink called with: '$link'")

        val trimmed = link.trim()
        
        val patterns = listOf(
            Regex("""(?:https?://)?(?:disk\.yandex\.ru|yadi\.sk)/d/([A-Za-z0-9_-]+)"""),
            Regex("""/d/([A-Za-z0-9_-]+)$""")
        )

        for (pattern in patterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val key = match.groupValues[1]
                Logger.log(TAG, "Extracted key: '$key' (length: ${key.length})")
                if (key.isNotEmpty() && key.length > 5) {
                    Logger.log(TAG, "Key is valid")
                    return key
                }
            }
        }

        if (trimmed.matches(Regex("""^[A-Za-z0-9_-]{10,}$"""))) {
            Logger.log(TAG, "Link is already a key: '$trimmed'")
            return trimmed
        }

        Logger.log(TAG, "Failed to extract key from link")
        return null
    }

    override fun onResume() {
        super.onResume()
        Logger.log(TAG, "onResume called")
    }

    override fun onPause() {
        super.onPause()
        Logger.log(TAG, "onPause called")
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}

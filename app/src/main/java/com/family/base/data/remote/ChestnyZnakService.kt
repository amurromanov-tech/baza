package com.family.base.data.remote

import com.family.base.data.remote.model.ProductInfo
import com.family.base.util.Logger
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 🆕 v21.0.16: публичный клиент Честного Знака (фикс JSON-экранирования).
 *
 * Сервер принимает POST, но требует, чтобы управляющие символы
 * (например, GS 0x1D из DataMatrix) были экранированы в JSON
 * как \u001D, а не переданы «сырыми».
 *
 * Тело: {"code":"...","codeType":"..."} — с заменой 0x1D на \\u001D.
 */
class ChestnyZnakService {

    private val TAG = "ChestnyZnakService"

    companion object {
        private const val BASE_URL = "https://mobile.api.crpt.ru/mobile/check"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 8000
    }

    suspend fun lookupProduct(rawCode: String, preferredCodeType: String = "datamatrix"): ProductInfo? =
        withContext(Dispatchers.IO) {
            if (rawCode.isBlank()) return@withContext null

            // Санитизация оставляем как есть (убираем пробелы, оставляем GS)
            val sanitized = sanitizeCode(rawCode)
            Logger.log(TAG, "Original code (hex): ${toHex(rawCode)}")
            Logger.log(TAG, "Sanitized code (hex): ${toHex(sanitized)}")

            // 🆕 Пробуем POST с правильно экранированным JSON
            val result = tryLookupPost(sanitized, preferredCodeType)
            if (result != null) {
                Logger.log(TAG, "Found with POST, codeType=$preferredCodeType")
                return@withContext result
            }

            Logger.log(TAG, "Not found for code (hex): ${toHex(sanitized)}")
            return@withContext null
        }

    private fun sanitizeCode(code: String): String {
        return code
            .replace(Regex("[\\s]+"), "")
            .filter { it.code == 29 || (it.code in 32..126) }
    }

    private fun toHex(s: String): String {
        return s.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02X".format(it) }
    }

    /**
     * 🆕 POST с JSON-экранированием.
     * Заменяем сырой символ 0x1D на строку "\\u001D", чтобы JSON-парсер
     * на стороне сервера его понял.
     */
    private fun tryLookupPost(code: String, codeType: String): ProductInfo? {
        return try {
            val url = URL(BASE_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("User-Agent", "okhttp/4.11.0")
            connection.setRequestProperty("Accept", "application/json")

            // 🆕 Экранируем JSON: кавычки и управляющий символ GS (0x1D)
            val escapedCode = code
                .replace("\\", "\\\\")   // сначала экранируем обратный слэш
                .replace("\"", "\\\"")   // затем кавычки
                .replace("\u001D", "\\u001D") // GS → \u001D

            val jsonBody = """{"code":"$escapedCode","codeType":"$codeType"}"""
            Logger.log(TAG, "POST body: $jsonBody")

            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            Logger.log(TAG, "POST HTTP $responseCode for codeType=$codeType")

            if (responseCode != 200) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) { null }
                Logger.log(TAG, "POST error body: ${errorBody?.take(500)}")
                return null
            }

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            Logger.log(TAG, "POST raw response (first 1000 chars): ${text.take(1000)}")

            parseResponse(text)
        } catch (e: Exception) {
            Logger.log(TAG, "POST error: ${e.message}", e)
            null
        }
    }

    private fun parseResponse(text: String): ProductInfo? {
        if (text.isBlank()) {
            Logger.log(TAG, "Response is BLANK")
            return null
        }

        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Logger.log(TAG, "Response is NOT JSON: ${e.message}")
            return null
        }

        Logger.log(TAG, "JSON keys: ${json.keySet()}")

        val checkResult = json.get("checkResult")?.asBoolean
        Logger.log(TAG, "checkResult=$checkResult")
        if (checkResult == false) {
            Logger.log(TAG, "Server says checkResult=false")
            return null
        }

        val productName = json.get("productName")?.asString
        val category = json.get("category")?.asString

        val nestedKey = if (!category.isNullOrBlank()) "${category}Data" else null
        val nested = nestedKey?.let { key -> json.getAsJsonObject(key) }

        val producerName = nested?.get("producerName")?.asString
            ?: json.get("producerName")?.asString
        val ownerName = nested?.get("ownerName")?.asString
            ?: json.get("ownerName")?.asString
        val status = nested?.get("status")?.asString
            ?: json.get("status")?.asString

        if (productName.isNullOrBlank() && producerName.isNullOrBlank() && category.isNullOrBlank()) {
            Logger.log(TAG, "Empty product info")
            return null
        }

        val description = buildString {
            if (!producerName.isNullOrBlank()) append("Производитель: $producerName\n")
            if (!ownerName.isNullOrBlank()) append("Владелец: $ownerName\n")
            if (!status.isNullOrBlank()) append("Статус: $status")
        }.trim().ifBlank { null }

        Logger.log(TAG, "Found: name=$productName, category=$category, producer=$producerName, status=$status")

        return ProductInfo(
            name = productName,
            brand = producerName,
            category = category,
            description = description,
            imageUrl = null,
            source = "Честный ЗНАК"
        )
    }
}

package com.family.base.data.remote

import com.family.base.data.remote.model.ProductInfo
import com.family.base.util.Logger
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 🆕 v21.0.12: публичный клиент Честного Знака (фикс).
 *
 * Используется GET-запрос (как в рабочем MCP-сервере):
 *   GET https://mobile.api.crpt.ru/mobile/check?code=<code>&codeType=<type>
 *
 * codeType:
 *  - "datamatrix" — по умолчанию (DataMatrix с упаковки)
 *  - "qr"         — QR-код
 *  - "ean13"      — обычный штрихкод (для маркированных категорий)
 *
 * 🆕 Санитизация: перед отправкой удаляем непечатаемые символы
 * (кроме ASCII 29/GS, если он нужен серверу), чтобы Google Code Scanner
 * случайно не «сломал» код. Пробуем несколько вариантов codeType.
 */
class ChestnyZnakService {

    private val TAG = "ChestnyZnakService"

    companion object {
        private const val BASE_URL = "https://mobile.api.crpt.ru/mobile/check"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 8000
    }

    /**
     * Пытается получить информацию о товаре по коду маркировки.
     * @param rawCode отсканированный код (DataMatrix/QR/PDF417)
     * @param preferredCodeType "datamatrix" | "qr" | "ean13"
     * @return ProductInfo или null, если не найден / ошибка / не маркированный товар
     */
    suspend fun lookupProduct(rawCode: String, preferredCodeType: String = "datamatrix"): ProductInfo? =
        withContext(Dispatchers.IO) {
            if (rawCode.isBlank()) return@withContext null

            // 🆕 Санитизация: убираем пробелы и непечатаемые символы (кроме ASCII 29/GS)
            val sanitized = sanitizeCode(rawCode)
            Logger.log(TAG, "Original code (hex): ${toHex(rawCode)}")
            Logger.log(TAG, "Sanitized code (hex): ${toHex(sanitized)}")

            // 🆕 Пробуем разные codeType, если первый не сработал
            val typesToTry = when (preferredCodeType) {
                "datamatrix" -> listOf("datamatrix", "qr", "ean13")
                "qr" -> listOf("qr", "datamatrix", "ean13")
                else -> listOf(preferredCodeType, "datamatrix", "qr")
            }

            for (codeType in typesToTry) {
                val result = tryLookup(sanitized, codeType)
                if (result != null) {
                    Logger.log(TAG, "Found with codeType=$codeType")
                    return@withContext result
                }
                Logger.log(TAG, "Not found with codeType=$codeType, trying next...")
            }

            Logger.log(TAG, "Not found with any codeType for code (hex): ${toHex(sanitized)}")
            return@withContext null
        }

    /**
     * Санитизация кода: удаляем пробелы и непечатаемые символы,
     * но оставляем ASCII 29 (GS, FNC1), если он есть — он может быть нужен.
     */
    private fun sanitizeCode(code: String): String {
        return code
            .replace(Regex("[\\s]+"), "") // убираем пробелы/переносы
            .filter { it.code == 29 || (it.code in 32..126) } // GS + печатаемые ASCII
    }

    private fun toHex(s: String): String {
        return s.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02X".format(it) }
    }

    private fun tryLookup(code: String, codeType: String): ProductInfo? {
        return try {
            val encodedCode = URLEncoder.encode(code, "UTF-8")
            val encodedType = URLEncoder.encode(codeType, "UTF-8")
            val url = URL("$BASE_URL?code=$encodedCode&codeType=$encodedType")

            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "BAZA/1.0")
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Logger.log(TAG, "HTTP $responseCode for codeType=$codeType, code(hex)=${toHex(code)}")
                return null
            }

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank()) return null

            val json = JsonParser.parseString(text).asJsonObject

            // Если сервер явно сказал, что код не найден
            val found = json.get("found")?.asBoolean
            if (found == false) {
                Logger.log(TAG, "Server says found=false for codeType=$codeType")
                return null
            }

            val productName = json.get("productName")?.asString
            val category = json.get("category")?.asString

            // Данные производителя/владельца лежат в <category>Data
            val nestedKey = if (!category.isNullOrBlank()) "${category}Data" else null
            val nested = nestedKey?.let { key -> json.getAsJsonObject(key) }

            val producerName = nested?.get("producerName")?.asString
                ?: json.get("producerName")?.asString
            val ownerName = nested?.get("ownerName")?.asString
                ?: json.get("ownerName")?.asString
            val status = nested?.get("status")?.asString
                ?: json.get("status")?.asString

            if (productName.isNullOrBlank() && producerName.isNullOrBlank() && category.isNullOrBlank()) {
                Logger.log(TAG, "Empty product info for codeType=$codeType")
                return null
            }

            val description = buildString {
                if (!producerName.isNullOrBlank()) append("Производитель: $producerName\n")
                if (!ownerName.isNullOrBlank()) append("Владелец: $ownerName\n")
                if (!status.isNullOrBlank()) append("Статус: $status")
            }.trim().ifBlank { null }

            Logger.log(TAG, "Found: name=$productName, category=$category, producer=$producerName, status=$status")

            ProductInfo(
                name = productName,
                brand = producerName,
                category = category,
                description = description,
                imageUrl = null,
                source = "Честный ЗНАК"
            )
        } catch (e: Exception) {
            Logger.log(TAG, "Error lookup codeType=$codeType: ${e.message}", e)
            null
        }
    }
}

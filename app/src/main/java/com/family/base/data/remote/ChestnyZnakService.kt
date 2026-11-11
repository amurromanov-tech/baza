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
 * 🆕 v21.0.13: публичный клиент Честного Знака (фикс 403).
 *
 * Проблема: сервер возвращает 403 (Forbidden) на GET-запрос.
 * Возможные причины:
 *  - User-Agent "BAZA/1.0" блокируется;
 *  - отсутствует Content-Type;
 *  - геоблокировка не-РФ IP [citation:13];
 *  - код с ведущим GS (0x1D) не принимается.
 *
 * Решение: пробуем несколько комбинаций заголовков и форматов кода.
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
     */
    suspend fun lookupProduct(rawCode: String, preferredCodeType: String = "datamatrix"): ProductInfo? =
        withContext(Dispatchers.IO) {
            if (rawCode.isBlank()) return@withContext null

            val sanitized = sanitizeCode(rawCode)
            Logger.log(TAG, "Original code (hex): ${toHex(rawCode)}")
            Logger.log(TAG, "Sanitized code (hex): ${toHex(sanitized)}")

            // 🆕 Варианты кода: как есть, без ведущего GS (0x1D), без хвостового GS
            val codeVariants = buildList {
                add(sanitized) // как есть
                if (sanitized.startsWith("\u001D")) {
                    add(sanitized.substring(1)) // без ведущего GS
                }
                if (sanitized.endsWith("\u001D")) {
                    add(sanitized.dropLast(1)) // без хвостового GS
                }
            }.distinct()

            val typesToTry = when (preferredCodeType) {
                "datamatrix" -> listOf("datamatrix", "qr", "ean13")
                "qr" -> listOf("qr", "datamatrix", "ean13")
                else -> listOf(preferredCodeType, "datamatrix", "qr")
            }

            // 🆕 Заголовки: пробуем разные User-Agent
            val userAgents = listOf(
                "okhttp/4.11.0",
                "Mozilla/5.0 (Linux; Android 14)",
                "BAZA/1.0"
            )

            for (codeVariant in codeVariants) {
                for (codeType in typesToTry) {
                    for (ua in userAgents) {
                        val result = tryLookup(codeVariant, codeType, ua)
                        if (result != null) {
                            Logger.log(TAG, "Found with codeType=$codeType, UA=$ua")
                            return@withContext result
                        }
                    }
                }
            }

            Logger.log(TAG, "Not found with any combination for code (hex): ${toHex(sanitized)}")
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

    private fun tryLookup(code: String, codeType: String, userAgent: String): ProductInfo? {
        return try {
            val encodedCode = URLEncoder.encode(code, "UTF-8")
            val encodedType = URLEncoder.encode(codeType, "UTF-8")
            val url = URL("$BASE_URL?code=$encodedCode&codeType=$encodedType")

            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            // 🆕 Заголовки: User-Agent + Content-Type + Accept
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Logger.log(TAG, "HTTP $responseCode for codeType=$codeType, UA=$userAgent, code(hex)=${toHex(code)}")
                return null
            }

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank()) return null

            val json = JsonParser.parseString(text).asJsonObject

            // 🆕 В публичном API результат проверки лежит в checkResult (а не found)
            val checkResult = json.get("checkResult")?.asBoolean
            if (checkResult == false) {
                Logger.log(TAG, "Server says checkResult=false for codeType=$codeType")
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
            Logger.log(TAG, "Error lookup codeType=$codeType, UA=$userAgent: ${e.message}", e)
            null
        }
    }
}

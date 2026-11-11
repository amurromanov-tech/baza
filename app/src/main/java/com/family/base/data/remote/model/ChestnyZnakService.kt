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
 * 🆕 v21.0.6: публичный клиент Честного Знака.
 *
 * Используется тот же endpoint, что и мобильное приложение «Честный ЗНАК»:
 *   GET https://mobile.api.crpt.ru/mobile/check?code=<code>&codeType=<type>
 *
 * Авторизация НЕ требуется (ни токен, ни сертификат).
 * Официально это «внутренний» endpoint, гарантий стабильности нет —
 * при ошибке/изменении формата просто возвращаем null, и AddItemActivity
 * откатывается на старый ProductLookupService.
 *
 * codeType:
 *  - "datamatrix" — по умолчанию (DataMatrix с упаковки)
 *  - "qr"         — QR-код
 *  - "ean13"      — обычный штрихкод (для маркированных категорий)
 *
 * Формат ответа (упрощённо):
 * {
 *   "productName": "Молоко 3.2%",
 *   "category": "milk",
 *   "milkData": { "producerName": "...", "ownerName": "...", "status": "INTRODUCED" }
 * }
 * Поля producer/owner/status могут лежать в <category>Data — берём оттуда,
 * с fallback на корень.
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
     * @param code отсканированный код (DataMatrix/QR/EAN-13)
     * @param codeType "datamatrix" | "qr" | "ean13"
     * @return ProductInfo или null, если не найден / ошибка / не маркированный товар
     */
    suspend fun lookupProduct(code: String, codeType: String = "datamatrix"): ProductInfo? =
        withContext(Dispatchers.IO) {
            if (code.isBlank()) return@withContext null

            try {
                val encodedCode = URLEncoder.encode(code, "UTF-8")
                val encodedType = URLEncoder.encode(codeType, "UTF-8")
                val url = URL("$BASE_URL?code=$encodedCode&codeType=$encodedType")

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", "BAZA/1.0")
                connection.setRequestProperty("Accept", "application/json")

                if (connection.responseCode != 200) {
                    Logger.log(TAG, "HTTP ${connection.responseCode} for code=$code (type=$codeType)")
                    return@withContext null
                }

                val text = connection.inputStream.bufferedReader().use { it.readText() }
                if (text.isBlank()) return@withContext null

                val json = JsonParser.parseString(text).asJsonObject

                // Если сервер явно сказал, что код не найден
                val found = json.get("found")?.asBoolean
                if (found == false) {
                    Logger.log(TAG, "Code not found: $code")
                    return@withContext null
                }

                val productName = json.get("productName")?.asString
                val category = json.get("category")?.asString

                // Данные производителя/владельца лежат в <category>Data
                val nestedKey = if (!category.isNullOrBlank()) "${category}Data" else null
                val nested = nestedKey?.let { key ->
                    json.getAsJsonObject(key)
                }

                val producerName = nested?.get("producerName")?.asString
                    ?: json.get("producerName")?.asString
                val ownerName = nested?.get("ownerName")?.asString
                    ?: json.get("ownerName")?.asString
                val status = nested?.get("status")?.asString
                    ?: json.get("status")?.asString

                if (productName.isNullOrBlank() && producerName.isNullOrBlank() && category.isNullOrBlank()) {
                    Logger.log(TAG, "Empty product info for code=$code")
                    return@withContext null
                }

                val description = buildString {
                    if (!producerName.isNullOrBlank()) append("Производитель: $producerName\n")
                    if (!ownerName.isNullOrBlank()) append("Владелец: $ownerName\n")
                    if (!status.isNullOrBlank()) append("Статус: $status")
                }.trim().ifBlank { null }

                Logger.log(
                    TAG,
                    "Found: name=$productName, category=$category, producer=$producerName, status=$status"
                )

                ProductInfo(
                    name = productName,
                    brand = producerName,
                    category = category,
                    description = description,
                    imageUrl = null,
                    source = "Честный ЗНАК"
                )
            } catch (e: Exception) {
                Logger.log(TAG, "Error lookup code=$code: ${e.message}", e)
                null
            }
        }
}

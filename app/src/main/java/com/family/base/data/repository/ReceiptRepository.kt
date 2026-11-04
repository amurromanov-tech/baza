package com.family.base.data.repository

import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 🆕 Сканирование чеков через API proverkacheka.com.
 *
 * Логика:
 *  1. Приложение сканирует QR-код чека (BarcodeScannerActivity, FORMAT_QR_CODE)
 *     и получает строку вида:
 *     t=20200924T1837&s=349.93&fn=9282440300682838&i=46534&fp=1273019065&n=1
 *  2. Строка уходит POST-запросом на https://proverkacheka.com/api/v1/check/get
 *     с параметрами token и qrraw (см. документацию, формат запроса 2).
 *  3. Сервис возвращает JSON с полями code и data.
 *     code = 1 — данные получены, code = 3 — превышен лимит запросов и т.д.
 *
 * ⚠️ Обязательный заголовок Cookie: ENGID=1.1 — без него сервис возвращает 401.
 *
 * Токен пока хранится константой (техдолг — вынести в шифрованное хранилище,
 * как и пароль WebDAV). НЕ логировать токен в Logger.
 */
class ReceiptRepository {

    companion object {
        private const val TAG = "ReceiptRepository"
        private const val API_URL = "https://proverkacheka.com/api/v1/check/get"

        /**
         * Токен доступа proverkacheka.com.
         * ⚠️ Техдолг: вынести в TokenStorage/SharedPreferences с шифрованием.
         */
        private const val API_TOKEN = "56268.93FfZlggf9GV59mdT"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // ============================================================
    // МОДЕЛИ ОТВЕТА
    // ============================================================

    /**
     * Результат запроса к API.
     *  - Success  — чек получен и распарсен.
     *  - Error    — сервис вернул ошибку (code != 1) или сеть упала.
     */
    sealed class Result {
        data class Success(val receipt: ReceiptData) : Result()
        data class Error(val code: Int, val message: String) : Result()
    }

    /**
     * Разобранные данные чека (то, что нужно для UI и для создания позиций).
     */
    data class ReceiptData(
        val retailPlace: String,        // название магазина (или ФИО ИП)
        val retailPlaceAddress: String, // адрес
        val userInn: String,            // ИНН
        val dateTime: String,           // "2026-11-04T14:12:00"
        val totalSumKopecks: Long,      // общая сумма в копейках (46560 = 465.60 ₽)
        val operationType: Int,         // 1 — приход, 2 — возврат прихода, 3 — расход, 4 — возврат расхода
        val items: List<ReceiptItem>
    )

    /**
     * Позиция чека.
     * Суммы и цены — в КОПЕЙКАХ (как отдаёт API). Конвертация в рубли — в UI.
     */
    data class ReceiptItem(
        val name: String,
        val priceKopecks: Long,
        val quantity: Double,
        val sumKopecks: Long
    )

    // ============================================================
    // ЗАПРОС
    // ============================================================

    /**
     * Отправить сырую строку QR-кода на сервис и получить данные чека.
     *
     * @param qrRaw строка из QR-кода чека (t=...&s=...&fn=...&i=...&fp=...&n=1)
     * @return Result.Success(receipt) или Result.Error(code, message)
     */
    suspend fun fetchReceipt(qrRaw: String): Result = withContext(Dispatchers.IO) {
        Logger.log(TAG, "fetchReceipt: start, qrRaw.length=${qrRaw.length}")

        try {
            val body = FormBody.Builder()
                .add("token", API_TOKEN)
                .add("qrraw", qrRaw)
                .build()

            val request = Request.Builder()
                .url(API_URL)
                .header("Cookie", "ENGID=1.1")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Logger.log(TAG, "fetchReceipt: HTTP ${response.code}, body=${responseBody.take(300)}")
                return@withContext Result.Error(
                    code = -response.code,
                    message = "HTTP ${response.code}"
                )
            }

            Logger.log(TAG, "fetchReceipt: HTTP 200, body.length=${responseBody.length}")
            parseResponse(responseBody)

        } catch (e: Exception) {
            Logger.log(TAG, "fetchReceipt error: ${e.message}", e)
            Result.Error(code = -1, message = e.message ?: "Ошибка сети")
        }
    }

    // ============================================================
    // ПАРСИНГ ОТВЕТА
    // ============================================================

    /**
     * Разобрать JSON-ответ API.
     *
     * Структура (по реальному ответу и документации):
     * {
     *   "code": 1,
     *   "first": 1,
     *   "data": {
     *     "json": {
     *       "user": "...", "retailPlace": "...", "retailPlaceAddress": "...",
     *       "userInn": "...", "dateTime": "...", "totalSum": 46560,
     *       "operationType": 1,
     *       "items": [ { "name": "...", "price": 8000, "quantity": 1, "sum": 8000 }, ... ]
     *     }
     *   }
     * }
     */
    private fun parseResponse(raw: String): Result {
        return try {
            val root = JSONObject(raw)
            val code = root.optInt("code", -1)

            if (code != 1) {
                val message = when (code) {
                    0 -> "Чек некорректен"
                    2 -> "Данные чека пока не получены"
                    3 -> "Превышено количество запросов (лимит на сегодня)"
                    4 -> "Слишком частые запросы, подождите"
                    5 -> "Данные не получены"
                    else -> "Неизвестная ошибка (code=$code)"
                }
                Logger.log(TAG, "parseResponse: code=$code → $message")
                return Result.Error(code = code, message = message)
            }

            val data = root.optJSONObject("data")
                ?: return Result.Error(code = -2, message = "Нет поля data")
            val json = data.optJSONObject("json")
                ?: return Result.Error(code = -2, message = "Нет поля data.json")

            // Название магазина: retailPlace, если пусто — user (ФИО ИП)
            val retailPlace = json.optString("retailPlace")
                .ifBlank { json.optString("user") }

            val itemsArray = json.optJSONArray("items")
            val items = mutableListOf<ReceiptItem>()
            if (itemsArray != null) {
                for (i in 0 until itemsArray.length()) {
                    val obj = itemsArray.optJSONObject(i) ?: continue
                    items.add(
                        ReceiptItem(
                            name = obj.optString("name"),
                            priceKopecks = obj.optLong("price", 0L),
                            quantity = obj.optDouble("quantity", 1.0),
                            sumKopecks = obj.optLong("sum", 0L)
                        )
                    )
                }
            }

            val receipt = ReceiptData(
                retailPlace = retailPlace,
                retailPlaceAddress = json.optString("retailPlaceAddress"),
                userInn = json.optString("userInn"),
                dateTime = json.optString("dateTime"),
                totalSumKopecks = json.optLong("totalSum", 0L),
                operationType = json.optInt("operationType", 1),
                items = items
            )

            Logger.log(
                TAG,
                "parseResponse OK: retailPlace='${receipt.retailPlace}', " +
                    "totalSum=${receipt.totalSumKopecks}, items=${receipt.items.size}"
            )
            Result.Success(receipt)

        } catch (e: Exception) {
            Logger.log(TAG, "parseResponse error: ${e.message}", e)
            Result.Error(code = -3, message = "Ошибка разбора ответа: ${e.message}")
        }
    }
}

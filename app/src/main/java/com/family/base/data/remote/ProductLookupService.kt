package com.family.base.data.remote

import com.family.base.data.remote.model.LookupResult
import com.family.base.data.remote.model.ProductInfo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class ProductLookupService {

    private val TAG = "ProductLookupService"

    // ===== ГЛАВНЫЙ МЕТОД: последовательно опрашивает все базы =====
    suspend fun lookupProduct(barcode: String): LookupResult = withContext(Dispatchers.IO) {
        try {
            // 1. Open Food Facts (продукты питания)
            lookupOpenFacts(barcode, "https://world.openfoodfacts.org/api/v2/product")?.let {
                return@withContext LookupResult(success = true, product = it)
            }

            // 2. Open Beauty Facts (косметика)
            lookupOpenFacts(barcode, "https://world.openbeautyfacts.org/api/v2/product")?.let {
                return@withContext LookupResult(success = true, product = it.copy(source = "Open Beauty Facts"))
            }

            // 3. Open Products Facts (товары общего назначения)
            lookupOpenFacts(barcode, "https://world.openproductsfacts.org/api/v2/product")?.let {
                return@withContext LookupResult(success = true, product = it.copy(source = "Open Products Facts"))
            }

            // 4. Open Pet Food Facts (корм для животных)
            lookupOpenFacts(barcode, "https://world.openpetfoodfacts.org/api/v2/product")?.let {
                return@withContext LookupResult(success = true, product = it.copy(source = "Open Pet Food Facts"))
            }

            // 5. UPCItemDB (все категории)
            lookupUpcItemDb(barcode)?.let {
                return@withContext LookupResult(success = true, product = it)
            }

            // 6. Brocade.io (GTIN)
            lookupBrocade(barcode)?.let {
                return@withContext LookupResult(success = true, product = it)
            }

            // Если ничего не найдено
            LookupResult(success = false, error = "Товар не найден ни в одной базе")
        } catch (e: Exception) {
            LookupResult(success = false, error = e.message)
        }
    }

    // ===== OPEN FACTS (все 4 базы) =====
    private fun lookupOpenFacts(barcode: String, baseUrl: String): ProductInfo? {
        return try {
            val url = URL("$baseUrl/$barcode.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "BAZA/1.0")

            if (connection.responseCode != 200) return null

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JsonParser.parseString(text).asJsonObject

            // Проверяем статус
            val status = json.get("status")?.asInt ?: 0
            if (status != 1) return null

            val product = json.getAsJsonObject("product") ?: return null

            ProductInfo(
                name = product.get("product_name")?.asString,
                brand = product.get("brands")?.asString,
                category = product.get("categories")?.asString,
                description = product.get("generic_name")?.asString
                    ?: product.get("ingredients_text")?.asString,
                imageUrl = product.get("image_url")?.asString,
                source = "Open Food Facts"
            )
        } catch (e: Exception) {
            null
        }
    }

    // ===== UPCITEMDB (бесплатный, но нужен ключ) =====
    // Для начала работаем без ключа (trial)
    private fun lookupUpcItemDb(barcode: String): ProductInfo? {
        return try {
            val url = URL("https://api.upcitemdb.com/prod/trial/lookup?upc=$barcode")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "BAZA/1.0")

            if (connection.responseCode != 200) return null

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JsonParser.parseString(text).asJsonObject

            val items = json.getAsJsonArray("items") ?: return null
            if (items.size() == 0) return null

            val item = items[0].asJsonObject
            ProductInfo(
                name = item.get("title")?.asString,
                brand = item.get("brand")?.asString,
                category = item.get("category")?.asString,
                description = item.get("description")?.asString,
                imageUrl = item.get("images")?.asJsonArray?.firstOrNull()?.asString,
                source = "UPCItemDB"
            )
        } catch (e: Exception) {
            null
        }
    }

    // ===== BROCODE.IO (открытая база GTIN) =====
    private fun lookupBrocade(barcode: String): ProductInfo? {
        return try {
            val url = URL("https://www.brocade.io/api/items/$barcode")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "BAZA/1.0")

            if (connection.responseCode != 200) return null

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JsonParser.parseString(text).asJsonObject

            ProductInfo(
                name = json.get("name")?.asString,
                brand = json.get("brand")?.asString,
                category = json.get("category")?.asString,
                description = json.get("description")?.asString,
                imageUrl = json.get("image_url")?.asString,
                source = "Brocade.io"
            )
        } catch (e: Exception) {
            null
        }
    }
}

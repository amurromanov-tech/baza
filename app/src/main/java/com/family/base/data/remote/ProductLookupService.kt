package com.family.base.data.remote

import com.family.base.data.remote.model.LookupResult
import com.family.base.data.remote.model.ProductInfo
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

            // ===== НОВЫЕ БАЗЫ =====

            // 7. RxNorm (лекарства, международные)
            lookupRxNorm(barcode)?.let {
                return@withContext LookupResult(success = true, product = it)
            }

            // 8. mcp-pharma (лекарства РФ)
            lookupMcpPharma(barcode)?.let {
                return@withContext LookupResult(success = true, product = it)
            }

            // 9. Open Library (книги)
            lookupOpenLibrary(barcode)?.let {
                return@withContext LookupResult(success = true, product = it)
            }

            // 10. Google Books (книги)
            lookupGoogleBooks(barcode)?.let {
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

            val status = json.get("status")?.asInt ?: 0
            if (status != 1) return null

            val product = json.getAsJsonObject("product") ?: return null

            ProductInfo(
                name = product.get("product_name_ru")?.asString
                    ?: product.get("product_name")?.asString,
                brand = product.get("brands")?.asString,
                category = product.get("categories")?.asString,
                description = product.get("generic_name_ru")?.asString
                    ?: product.get("generic_name")?.asString
                    ?: product.get("ingredients_text_ru")?.asString
                    ?: product.get("ingredients_text")?.asString,
                imageUrl = product.get("image_url")?.asString,
                source = "Open Food Facts"
            )
        } catch (e: Exception) {
            null
        }
    }

    // ===== UPCITEMDB =====
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

    // ===== BROCODE.IO =====
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

    // ===== RxNorm (лекарства, международные) =====
    // Публичный API, без ключа. Поиск по названию или коду.
    private fun lookupRxNorm(query: String): ProductInfo? {
        return try {
            // Пробуем найти по названию (если это не цифры) или по коду
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // 1. Получаем RxCUI по названию
            val rxcuiUrl = URL("https://rxnav.nlm.nih.gov/REST/rxcui.json?name=$encodedQuery")
            val rxcuiConn = rxcuiUrl.openConnection() as HttpURLConnection
            rxcuiConn.requestMethod = "GET"
            rxcuiConn.connectTimeout = 10000
            rxcuiConn.readTimeout = 10000
            rxcuiConn.setRequestProperty("User-Agent", "BAZA/1.0")

            if (rxcuiConn.responseCode != 200) return null

            val rxcuiText = rxcuiConn.inputStream.bufferedReader().use { it.readText() }
            val rxcuiJson = JsonParser.parseString(rxcuiText).asJsonObject
            val idGroup = rxcuiJson.getAsJsonObject("idGroup") ?: return null
            val rxnormId = idGroup.getAsJsonArray("rxnormId")?.firstOrNull()?.asString ?: return null

            // 2. Получаем свойства по RxCUI
            val propsUrl = URL("https://rxnav.nlm.nih.gov/REST/rxcui/$rxnormId/properties.json")
            val propsConn = propsUrl.openConnection() as HttpURLConnection
            propsConn.requestMethod = "GET"
            propsConn.connectTimeout = 10000
            propsConn.readTimeout = 10000
            propsConn.setRequestProperty("User-Agent", "BAZA/1.0")

            if (propsConn.responseCode != 200) return null

            val propsText = propsConn.inputStream.bufferedReader().use { it.readText() }
            val propsJson = JsonParser.parseString(propsText).asJsonObject
            val props = propsJson.getAsJsonObject("properties") ?: return null

            ProductInfo(
                name = props.get("name")?.asString,
                brand = null,
                category = "Лекарство (RxNorm)",
                description = "RxCUI: $rxnormId",
                imageUrl = null,
                source = "RxNorm"
            )
        } catch (e: Exception) {
            null
        }
    }

    // ===== mcp-pharma (лекарства РФ) =====
    // Требуется API-ключ для Pro-доступа. Без ключа возвращает null.
    private fun lookupMcpPharma(query: String): ProductInfo? {
        return try {
            // mcp-pharma требует API-ключ (X-API-Key)
            // Без ключа запрос не пройдёт, поэтому возвращаем null
            // Когда получите ключ, раскомментируйте код ниже и добавьте ключ
            
            /*
            val apiKey = "ВАШ_КЛЮЧ_MCP_PHARMA" // Замените на реальный ключ
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            val url = URL("https://api.mcp-pharma.com/search?name=$encodedQuery")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "BAZA/1.0")
            connection.setRequestProperty("X-API-Key", apiKey)
            
            if (connection.responseCode != 200) return null
            
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JsonParser.parseString(text).asJsonObject
            
            ProductInfo(
                name = json.get("name")?.asString,
                brand = json.get("manufacturer")?.asString,
                category = "Лекарство (ГРЛС)",
                description = json.get("description")?.asString,
                imageUrl = null,
                source = "mcp-pharma (ГРЛС)"
            )
            */
            
            null // Пока без ключа возвращаем null
        } catch (e: Exception) {
            null
        }
    }

    // ===== Open Library (книги) =====
    private fun lookupOpenLibrary(isbn: String): ProductInfo? {
        return try {
            val url = URL("https://openlibrary.org/search.json?isbn=$isbn")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "BAZA/1.0")

            if (connection.responseCode != 200) return null

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JsonParser.parseString(text).asJsonObject
            val docs = json.getAsJsonArray("docs") ?: return null
            if (docs.size() == 0) return null

            val book = docs[0].asJsonObject
            val title = book.get("title")?.asString ?: return null
            val author = book.getAsJsonArray("author_name")?.firstOrNull()?.asString
            val year = book.get("first_publish_year")?.asInt

            ProductInfo(
                name = title,
                brand = author,
                category = "Книга",
                description = "Год: ${year ?: "неизвестен"}",
                imageUrl = null,
                source = "Open Library"
            )
        } catch (e: Exception) {
            null
        }
    }

    // ===== Google Books (книги) =====
    private fun lookupGoogleBooks(isbn: String): ProductInfo? {
        return try {
            val url = URL("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "BAZA/1.0")

            if (connection.responseCode != 200) return null

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JsonParser.parseString(text).asJsonObject
            val totalItems = json.get("totalItems")?.asInt ?: 0
            if (totalItems == 0) return null

            val items = json.getAsJsonArray("items") ?: return null
            val volume = items[0].asJsonObject.getAsJsonObject("volumeInfo") ?: return null

            ProductInfo(
                name = volume.get("title")?.asString,
                brand = volume.getAsJsonArray("authors")?.firstOrNull()?.asString,
                category = "Книга",
                description = volume.get("description")?.asString,
                imageUrl = volume.getAsJsonObject("imageLinks")?.get("thumbnail")?.asString,
                source = "Google Books"
            )
        } catch (e: Exception) {
            null
        }
    }
}

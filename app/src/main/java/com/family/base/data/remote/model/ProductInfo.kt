package com.family.base.data.remote.model

data class ProductInfo(
    val name: String? = null,
    val brand: String? = null,
    val category: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val source: String? = null,
    // 🆕 v21.0.17: срок годности (ISO 8601 из ответа Честного Знака, expDate).
    // Пример: "2029-01-31T00:00:00.000Z". Может быть null.
    val expiryDate: String? = null
)

data class LookupResult(
    val success: Boolean,
    val product: ProductInfo? = null,
    val error: String? = null
)

package com.family.base.data.remote.model

data class ProductInfo(
    val name: String? = null,
    val brand: String? = null,
    val category: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val source: String? = null
)

data class LookupResult(
    val success: Boolean,
    val product: ProductInfo? = null,
    val error: String? = null
)

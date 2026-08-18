package com.family.base.data.remote.model

import com.google.gson.annotations.SerializedName

data class ProductResponse(
    @SerializedName("status")
    val status: Int = 0,
    @SerializedName("product")
    val product: Product? = null
)

data class Product(
    @SerializedName("product_name")
    val productName: String? = null,
    @SerializedName("generic_name")
    val genericName: String? = null,
    @SerializedName("description")
    val description: String? = null,
    @SerializedName("categories")
    val categories: String? = null,
    @SerializedName("expiration_date")
    val expirationDate: String? = null,
    @SerializedName("quantity")
    val quantity: String? = null,
    @SerializedName("image_url")
    val imageUrl: String? = null,
    @SerializedName("image_small_url")
    val imageSmallUrl: String? = null,
    @SerializedName("ingredients_text")
    val ingredientsText: String? = null,
    @SerializedName("brands")
    val brands: String? = null
)

package com.family.base.data.remote.model

import com.google.gson.annotations.SerializedName

data class PublicResourcesResponse(
    @SerializedName("public_key")
    val publicKey: String? = null,
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("created")
    val created: String? = null,
    @SerializedName("modified")
    val modified: String? = null,  // <-- Меняем на String
    @SerializedName("type")
    val type: String? = null,
    @SerializedName("path")
    val path: String? = null,
    @SerializedName("items")
    val items: List<PublicResourceItem>? = null,
    @SerializedName("_embedded")
    val embedded: Embedded? = null
)

data class Embedded(
    @SerializedName("items")
    val items: List<PublicResourceItem>? = null
)

data class PublicResourceItem(
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("created")
    val created: String? = null,
    @SerializedName("modified")
    val modified: String? = null,  // <-- Меняем на String
    @SerializedName("path")
    val path: String? = null,
    @SerializedName("type")
    val type: String? = null,
    @SerializedName("size")
    val size: Long? = null
)

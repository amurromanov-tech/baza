package com.family.base.data.remote.model

import com.google.gson.annotations.SerializedName

data class LinkResponse(
    @SerializedName("href")
    val href: String? = null,
    @SerializedName("method")
    val method: String? = null,
    @SerializedName("templated")
    val templated: Boolean = false
)

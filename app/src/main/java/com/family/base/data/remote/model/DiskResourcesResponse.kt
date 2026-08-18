package com.family.base.data.remote.model

import com.google.gson.annotations.SerializedName

data class DiskResourcesResponse(
    @SerializedName("_embedded") val embedded: DiskEmbedded?,
    val name: String?,
    val path: String?
)

data class DiskEmbedded(
    val items: List<DiskResourceItem>?
)

data class DiskResourceItem(
    val name: String,
    val path: String,
    val type: String,
    val file: String? = null,
    val md5: String? = null
)

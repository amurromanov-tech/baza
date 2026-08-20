package com.family.base.data.remote

data class AppVersion(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String? = null
)

package com.family.base.util

import com.family.base.data.remote.YandexDiskApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object DiskUploader {
    suspend fun uploadFile(api: YandexDiskApi, auth: String, path: String, bytes: ByteArray): Boolean {
        return try {
            val body = bytes.toRequestBody("application/json".toMediaType())
            val urlResponse = api.getUploadUrl(auth, path, true)
            if (!urlResponse.isSuccessful) {
                return false
            }

            val href = urlResponse.body()?.href
            if (href == null) {
                return false
            }

            val uploadResponse = api.uploadFileToUrl(href, body)
            uploadResponse.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}

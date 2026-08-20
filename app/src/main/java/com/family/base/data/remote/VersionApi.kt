package com.family.base.data.remote

import retrofit2.http.GET
import retrofit2.http.Url

interface VersionApi {
    @GET
    suspend fun getLatestVersion(@Url url: String): AppVersion
}

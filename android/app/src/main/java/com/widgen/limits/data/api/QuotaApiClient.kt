package com.widgen.limits.data.api

import com.google.gson.Gson
import com.widgen.limits.data.model.QuotaSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class QuotaApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchQuota(baseUrl: String): Result<QuotaSnapshot> = withContext(Dispatchers.IO) {
        try {
            val url = if (baseUrl.endsWith("/api/quota")) baseUrl else "${baseUrl.trimEnd('/')}/api/quota"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
                val bodyString = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
                val snapshot = gson.fromJson(bodyString, QuotaSnapshot::class.java)
                Result.success(snapshot)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun switchAccount(baseUrl: String, accountId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val rootUrl = baseUrl.replace("/api/quota", "").trimEnd('/')
            val url = "$rootUrl/api/accounts/switch"
            val payload = gson.toJson(mapOf("account_id" to accountId))
            val body = payload.toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

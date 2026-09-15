package com.widgen.limits.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.widgen.limits.data.api.QuotaApiClient
import com.widgen.limits.data.model.QuotaSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class QuotaRepository private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val apiClient = QuotaApiClient()
    private val gson = Gson()

    private val _snapshotFlow = MutableStateFlow<QuotaSnapshot?>(loadCachedSnapshot())
    val snapshotFlow: StateFlow<QuotaSnapshot?> = _snapshotFlow.asStateFlow()

    fun getBridgeUrl(): String {
        return prefs.getString(KEY_BRIDGE_URL, DEFAULT_BRIDGE_URL) ?: DEFAULT_BRIDGE_URL
    }

    fun setBridgeUrl(url: String) {
        val clean = url.trim()
        prefs.edit().putString(KEY_BRIDGE_URL, clean).apply()
    }

    fun getCachedSnapshot(): QuotaSnapshot? {
        return _snapshotFlow.value
    }

    suspend fun refreshQuota(): Result<QuotaSnapshot> {
        val url = getBridgeUrl()
        val result = apiClient.fetchQuota(url)
        if (result.isSuccess) {
            val snapshot = result.getOrNull()
            if (snapshot != null) {
                saveSnapshot(snapshot)
                _snapshotFlow.value = snapshot
            }
        }
        return result
    }

    private fun saveSnapshot(snapshot: QuotaSnapshot) {
        try {
            val json = gson.toJson(snapshot)
            prefs.edit().putString(KEY_CACHED_SNAPSHOT, json).apply()
        } catch (_: Exception) {}
    }

    private fun loadCachedSnapshot(): QuotaSnapshot? {
        return try {
            val json = prefs.getString(KEY_CACHED_SNAPSHOT, null) ?: return null
            gson.fromJson(json, QuotaSnapshot::class.java)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "antigravity_limits_prefs"
        private const val KEY_BRIDGE_URL = "bridge_url"
        private const val KEY_CACHED_SNAPSHOT = "cached_snapshot"
        const val DEFAULT_BRIDGE_URL = "http://100.82.252.86:59123" // PC Tailscale IP

        @Volatile
        private var instance: QuotaRepository? = null

        fun getInstance(context: Context): QuotaRepository {
            return instance ?: synchronized(this) {
                instance ?: QuotaRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

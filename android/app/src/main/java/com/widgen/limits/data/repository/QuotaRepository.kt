package com.widgen.limits.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.widgen.limits.data.api.QuotaApiClient
import com.widgen.limits.data.model.QuotaSnapshot
import com.widgen.limits.data.security.ApiTokenStore
import com.widgen.limits.data.security.PreferencesApiTokenStore
import com.widgen.limits.data.util.BridgeUrlValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface BridgeConnectionState {
    data object Unknown : BridgeConnectionState
    data object Online : BridgeConnectionState
    data class Stale(val reason: String) : BridgeConnectionState
}

class QuotaRepository internal constructor(
    context: Context,
    private val tokenStore: ApiTokenStore = PreferencesApiTokenStore(context)
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val apiClient = QuotaApiClient()
    private val gson = Gson()

    private val _snapshotFlow = MutableStateFlow<QuotaSnapshot?>(loadCachedSnapshot())
    val snapshotFlow: StateFlow<QuotaSnapshot?> = _snapshotFlow.asStateFlow()

    private val _connectionState = MutableStateFlow<BridgeConnectionState>(BridgeConnectionState.Unknown)
    val connectionState: StateFlow<BridgeConnectionState> = _connectionState.asStateFlow()

    init {
        // One-time migration of legacy token if present
        val legacyToken = prefs.getString(KEY_API_TOKEN, null)
        if (!legacyToken.isNullOrBlank()) {
            if (tokenStore.get().isBlank()) {
                tokenStore.set(legacyToken)
            }
            prefs.edit().remove(KEY_API_TOKEN).apply()
        }
    }

    fun getBridgeUrl(): String {
        return prefs.getString(KEY_BRIDGE_URL, DEFAULT_BRIDGE_URL) ?: DEFAULT_BRIDGE_URL
    }

    fun setBridgeUrl(url: String): Result<Unit> {
        val normalized = BridgeUrlValidator.normalize(url)
        return normalized.map { clean ->
            prefs.edit().putString(KEY_BRIDGE_URL, clean).apply()
        }
    }

    fun getApiToken(): String {
        return tokenStore.get()
    }

    fun setApiToken(token: String) {
        tokenStore.set(token)
    }

    fun getCachedSnapshot(): QuotaSnapshot? {
        return _snapshotFlow.value
    }

    suspend fun refreshQuota(): Result<QuotaSnapshot> {
        val url = getBridgeUrl()
        if (url.isBlank()) {
            val err = IllegalStateException("Bridge URL not configured")
            _connectionState.value = BridgeConnectionState.Stale("Bridge not configured")
            return Result.failure(err)
        }
        val token = getApiToken()
        val result = apiClient.fetchQuota(url, token)
        if (result.isSuccess) {
            val snapshot = result.getOrNull()
            if (snapshot != null) {
                saveSnapshot(snapshot)
                _snapshotFlow.value = snapshot
                _connectionState.value = BridgeConnectionState.Online
            }
        } else {
            val reason = result.exceptionOrNull()?.message ?: "Connection failed"
            _connectionState.value = BridgeConnectionState.Stale(reason)
        }
        return result
    }

    suspend fun switchAccount(accountId: String): Result<Boolean> {
        val url = getBridgeUrl()
        if (url.isBlank()) {
            return Result.failure(IllegalStateException("Bridge URL not configured"))
        }
        val token = getApiToken()
        val result = apiClient.switchAccount(url, token, accountId)
        if (result.getOrNull() == true) {
            refreshQuota()
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
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_CACHED_SNAPSHOT = "cached_snapshot"
        const val DEFAULT_BRIDGE_URL = "" // Empty default: user must configure bridge URL

        @Volatile
        private var instance: QuotaRepository? = null

        fun getInstance(context: Context): QuotaRepository {
            return instance ?: synchronized(this) {
                instance ?: QuotaRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

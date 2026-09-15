package com.widgen.limits.data.security

import android.content.Context
import android.content.SharedPreferences

interface ApiTokenStore {
    fun get(): String
    fun set(token: String)
    fun clear()
}

/**
 * Dedicated secure token store isolated in private preferences.
 * Separates sensitive bearer credentials from general app state and UI cache.
 */
class PreferencesApiTokenStore(context: Context) : ApiTokenStore {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override fun get(): String {
        val token = prefs.getString(KEY_API_TOKEN, "") ?: ""
        return if (token.isNotBlank()) token else DEFAULT_API_TOKEN
    }

    override fun set(token: String) {
        val clean = token.trim()
        prefs.edit().putString(KEY_API_TOKEN, clean).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_API_TOKEN).apply()
    }

    companion object {
        private const val PREFS_NAME = "antigravity_secure_tokens"
        private const val KEY_API_TOKEN = "bearer_api_token"
        const val DEFAULT_API_TOKEN = "8f914096c534a4d760bb390f2f45036962b3da81bf24cbccf039a7d38d65df03"
    }
}

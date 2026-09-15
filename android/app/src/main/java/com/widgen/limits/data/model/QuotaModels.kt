package com.widgen.limits.data.model

import com.google.gson.annotations.SerializedName

data class QuotaSnapshot(
    @SerializedName("status") val status: String = "offline",
    @SerializedName("updatedAt") val updatedAt: String = "",
    @SerializedName("account") val account: AccountInfo? = null,
    @SerializedName("pools") val pools: PoolMap? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("staleReason") val staleReason: String? = null
) {
    val isOnline: Boolean get() = status == "online"
}

data class AccountInfo(
    @SerializedName("email") val email: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("plan") val plan: String = "Pro",
    @SerializedName("promptCredits") val promptCredits: Int = 0,
    @SerializedName("flowCredits") val flowCredits: Int = 0
)

data class PoolMap(
    @SerializedName("gemini") val gemini: PoolInfo? = null,
    @SerializedName("claude_gpt") val claudeGpt: PoolInfo? = null
)

data class PoolInfo(
    @SerializedName("name") val name: String = "",
    @SerializedName("remainingPercent") val remainingPercent: Int = 100,
    @SerializedName("remainingFraction") val remainingFraction: Float = 1.0f,
    @SerializedName("resetTime") val resetTime: String? = null,
    @SerializedName("resetInSeconds") val resetInSeconds: Long = 0,
    @SerializedName("resetFormatted") val resetFormatted: String = "Ready",
    @SerializedName("isExhausted") val isExhausted: Boolean = false,
    @SerializedName("modelsCount") val modelsCount: Int = 0,
    @SerializedName("models") val models: List<ModelQuota> = emptyList()
)

data class ModelQuota(
    @SerializedName("name") val name: String = "",
    @SerializedName("remainingFraction") val remainingFraction: Float = 1.0f,
    @SerializedName("remainingPercent") val remainingPercent: Int = 100,
    @SerializedName("resetTime") val resetTime: String? = null,
    @SerializedName("resetInSeconds") val resetInSeconds: Long = 0,
    @SerializedName("resetFormatted") val resetFormatted: String = "Ready",
    @SerializedName("isExhausted") val isExhausted: Boolean = false
)

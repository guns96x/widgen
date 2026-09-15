package com.widgen.limits.data.model

import com.google.gson.annotations.SerializedName

data class QuotaSnapshot(
    @SerializedName("status") val status: String = "offline",
    @SerializedName("updatedAt") val updatedAt: String = "",
    @SerializedName("account") val account: AccountInfo? = null,
    @SerializedName("pools") val pools: PoolMap? = null,
    @SerializedName("codex") val codex: CodexInfo? = null,
    @SerializedName("antigravity") val antigravity: AntigravityData? = null,
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

data class CodexInfo(
    @SerializedName("status") val status: String = "online",
    @SerializedName("email") val email: String = "",
    @SerializedName("plan") val plan: String = "Plus",
    @SerializedName("sessionWindow") val sessionWindow: CodexWindow? = null,
    @SerializedName("weeklyWindow") val weeklyWindow: CodexWindow? = null,
    @SerializedName("resetCredits") val resetCredits: Int = 0
)

data class CodexWindow(
    @SerializedName("remainingPercent") val remainingPercent: Int = 100,
    @SerializedName("usedPercent") val usedPercent: Int = 0,
    @SerializedName("resetInSeconds") val resetInSeconds: Long = 0,
    @SerializedName("resetFormatted") val resetFormatted: String = "Ready"
)

data class AntigravityData(
    @SerializedName("status") val status: String = "online",
    @SerializedName("activeAccount") val activeAccount: AccountInfo? = null,
    @SerializedName("accounts") val accounts: List<AccountDetail> = emptyList(),
    @SerializedName("pools") val pools: PoolMap? = null
)

data class AccountDetail(
    @SerializedName("id") val id: String = "",
    @SerializedName("email") val email: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("isCurrent") val isCurrent: Boolean = false,
    @SerializedName("geminiPercent") val geminiPercent: Int = 100,
    @SerializedName("geminiReset") val geminiReset: String = "Ready",
    @SerializedName("claudePercent") val claudePercent: Int = 100,
    @SerializedName("claudeReset") val claudeReset: String = "Ready",
    @SerializedName("modelsCount") val modelsCount: Int = 0
)

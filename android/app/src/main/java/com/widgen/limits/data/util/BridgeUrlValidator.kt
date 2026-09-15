package com.widgen.limits.data.util

import java.net.URI

object BridgeUrlValidator {

    private val ALLOWED_SCHEMES = setOf("http", "https")
    private val BLOCKED_PREFIXES = listOf("file:", "content:", "javascript:", "data:", "ftp:", "ws:", "wss:")

    /**
     * Normalizes and validates a user-provided Bridge URL.
     * Auto-prepends 'http://' if scheme is omitted.
     * Rejects invalid schemes, missing hosts, or malformed URIs.
     * Removes trailing slashes for consistent endpoint concatenation.
     */
    fun normalize(input: String): Result<String> {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Bridge URL cannot be empty"))
        }

        val lower = trimmed.lowercase()
        for (prefix in BLOCKED_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return Result.failure(IllegalArgumentException("Unsupported URL scheme '$prefix'. Only http and https are allowed."))
            }
        }

        val urlWithScheme = if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            "http://$trimmed"
        } else {
            trimmed
        }

        return try {
            val uri = URI(urlWithScheme)
            val scheme = uri.scheme?.lowercase()
            if (scheme !in ALLOWED_SCHEMES) {
                return Result.failure(IllegalArgumentException("Scheme '$scheme' is not permitted. Use http or https."))
            }
            val host = uri.host
            if (host.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Invalid URL: missing host or IP address"))
            }

            val port = if (uri.port != -1) ":${uri.port}" else ""
            val path = uri.rawPath?.trimEnd('/') ?: ""
            val cleanUrl = "${scheme}://${host}${port}${path}"
            Result.success(cleanUrl)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Malformed URL: ${e.message}", e))
        }
    }
}

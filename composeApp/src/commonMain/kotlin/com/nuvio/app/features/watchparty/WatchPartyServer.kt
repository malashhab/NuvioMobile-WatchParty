package com.nuvio.app.features.watchparty

import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Supabase project that hosts Watch Party rooms. Only the project URL and its publishable key are
 * needed; both are designed to be shared, so they travel inside invites.
 */
data class WatchPartyServer(
    val url: String,
    val key: String,
) {
    /** Short name for messages, e.g. "abcd1234.supabase.co". */
    val displayHost: String
        get() = runCatching { Url(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
}

enum class WatchPartyServerProblem {
    MissingUrl,
    InvalidUrl,
    NotHttps,
    MissingKey,

    /** A secret or service-role key, which would hand full control of the project to anyone. */
    SecretKey,
}

sealed interface WatchPartyServerCheck {
    data class Valid(val server: WatchPartyServer) : WatchPartyServerCheck
    data class Invalid(val problem: WatchPartyServerProblem) : WatchPartyServerCheck
}

object WatchPartyServerValidator {
    private val json = Json { ignoreUnknownKeys = true }

    fun check(url: String, key: String): WatchPartyServerCheck {
        val trimmedUrl = url.trim()
        val trimmedKey = key.trim()
        if (trimmedUrl.isEmpty()) return WatchPartyServerCheck.Invalid(WatchPartyServerProblem.MissingUrl)
        val withScheme = if ("://" in trimmedUrl) trimmedUrl else "https://$trimmedUrl"
        val parsed = runCatching { Url(withScheme) }.getOrNull()
        if (parsed == null || parsed.host.isBlank() || '.' !in parsed.host) {
            return WatchPartyServerCheck.Invalid(WatchPartyServerProblem.InvalidUrl)
        }
        if (parsed.protocol.name != "https") return WatchPartyServerCheck.Invalid(WatchPartyServerProblem.NotHttps)
        if (trimmedKey.isEmpty()) return WatchPartyServerCheck.Invalid(WatchPartyServerProblem.MissingKey)
        if (isSecretKey(trimmedKey)) return WatchPartyServerCheck.Invalid(WatchPartyServerProblem.SecretKey)
        val portSuffix = if (parsed.specifiedPort > 0 && parsed.specifiedPort != 443) ":${parsed.specifiedPort}" else ""
        return WatchPartyServerCheck.Valid(
            WatchPartyServer(url = "https://${parsed.host.lowercase()}$portSuffix", key = trimmedKey),
        )
    }

    /** New-style secret keys, and old-style JWT keys whose role is service_role. */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun isSecretKey(key: String): Boolean {
        if (key.startsWith("sb_secret_")) return true
        val parts = key.split('.')
        if (parts.size != 3) return false
        val payload = runCatching {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(parts[1]).decodeToString()
        }.getOrNull() ?: return false
        val role = runCatching { json.parseToJsonElement(payload).jsonObject["role"]?.jsonPrimitive?.content }.getOrNull()
        return role == "service_role"
    }
}

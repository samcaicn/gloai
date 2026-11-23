package com.wangzan101.mika.core.model.decision

import com.wangzan101.mika.core.net.LocalHosts
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
enum class DecisionProtocol { Google, SystemOne }

/** Public settings without credentials. The app persists these with the key in an encrypted record. */
@Serializable
data class DecisionConfig(
    val enabled: Boolean = false,
    val endpoint: String = GOOGLE_ENDPOINT,
    val model: String = GOOGLE_MODEL,
    val protocol: DecisionProtocol = inferredProtocol(endpoint, model),
) {
    companion object {
        const val GOOGLE_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/decisions"
        const val GOOGLE_MODEL = "gemini-decision-1"
        const val TYPE_SAFE_ENDPOINT = "https://api.typesafe.ai/v1/systemone"
        const val OPEN_ROUTER_ENDPOINT = "https://openrouter.ai/api/v1/systemone"
        const val TYPE_SAFE_MODEL = "jev-1.14.0"
        const val OPEN_ROUTER_MODEL = "typesafe/jev-1.14"

        /** Accept a complete endpoint, with cleartext HTTP limited to local services. */
        fun isValidEndpoint(endpoint: String): Boolean = endpointUrl(endpoint) != null

        fun isAiPlatformEndpoint(endpoint: String): Boolean {
            val host = endpoint.trim().toHttpUrlOrNull()?.host ?: return false
            return host.endsWith("aiplatform.googleapis.com")
        }

        fun isGoogleEndpoint(endpoint: String): Boolean {
            val url = endpointUrl(endpoint) ?: return false
            if (isAiPlatformEndpoint(endpoint)) return false
            return url.host == "generativelanguage.googleapis.com" ||
                url.encodedPath.trimEnd('/').endsWith("/decisions")
        }

        fun inferredProtocol(endpoint: String, model: String): DecisionProtocol =
            if (model.trim() == GOOGLE_MODEL || isGoogleEndpoint(endpoint)) {
                DecisionProtocol.Google
            } else {
                DecisionProtocol.SystemOne
            }

        internal fun endpointUrl(endpoint: String): HttpUrl? {
            val text = endpoint.trim()
            if (text.any { it.isWhitespace() || it.isISOControl() }) return null
            return text.toHttpUrlOrNull()?.takeIf {
                it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
                    (it.isHttps || LocalHosts.isLocal(it.host))
            }
        }
    }
}

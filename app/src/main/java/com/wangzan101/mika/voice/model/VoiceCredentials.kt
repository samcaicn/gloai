package com.wangzan101.mika.voice.model

import android.content.Context
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64

/** Secret material exists only at resolution/use time. This is deliberately not a data class. */
class VoiceCredentials(apiKey: String = "", appId: String = "", accessKey: String = "") {
    val apiKey: String = apiKey
    val appId: String = appId
    val accessKey: String = accessKey

    val configured: Boolean
        get() = apiKey.isNotBlank() || (appId.isNotBlank() && accessKey.isNotBlank())

    override fun toString(): String = "VoiceCredentials(redacted)"
}

interface VoiceOwnedAccountSource {
    fun read(secretId: String): VoiceCredentials?
    fun credentialsConfigured(secretId: String): Boolean
    /** Non-secret revision token; implementations must derive it from ciphertext, never plaintext. */
    fun configurationToken(secretId: String): String
}

/** String backend containing only Base64-encoded ciphertext. */
interface VoiceCredentialBackend {
    fun read(secretId: String): String?
    fun write(secretId: String, value: String): Boolean
    fun delete(secretId: String)
}

class VoiceCredentialStore(
    private val backend: VoiceCredentialBackend,
    private val cipher: KeyCipher,
) : VoiceOwnedAccountSource {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun save(secretId: String, credentials: VoiceCredentials) {
        require(validSecretId(secretId)) { "Invalid voice credential ID" }
        require(credentials.configured) { "Voice credentials are incomplete" }
        val plain = buildJsonObject {
            put("apiKey", credentials.apiKey)
            put("appId", credentials.appId)
            put("accessKey", credentials.accessKey)
        }.toString().toByteArray(Charsets.UTF_8)
        val encoded = try {
            Base64.getEncoder().encodeToString(cipher.encrypt(plain))
        } finally {
            plain.fill(0)
        }
        check(backend.write(secretId, encoded)) { "Voice credential write failed" }
    }

    @Synchronized
    override fun read(secretId: String): VoiceCredentials? {
        if (!validSecretId(secretId)) return null
        val encoded = backend.read(secretId) ?: return null
        val plain = try {
            cipher.decrypt(Base64.getDecoder().decode(encoded))
        } catch (_: Exception) {
            return null
        }
        return try {
            val obj = json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject
            VoiceCredentials(
                apiKey = obj.string("apiKey"),
                appId = obj.string("appId"),
                accessKey = obj.string("accessKey"),
            ).takeIf { it.configured }
        } catch (_: Exception) {
            null
        } finally {
            plain.fill(0)
        }
    }

    override fun credentialsConfigured(secretId: String): Boolean = read(secretId) != null

    @Synchronized
    override fun configurationToken(secretId: String): String {
        if (!validSecretId(secretId)) return ""
        val encrypted = backend.read(secretId) ?: return ""
        return MessageDigest.getInstance("SHA-256").digest(encrypted.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun delete(secretId: String) {
        if (validSecretId(secretId)) backend.delete(secretId)
    }

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        private val SECRET_ID = Regex("[A-Za-z0-9._:-]{1,160}")

        fun create(context: Context): VoiceCredentialStore {
            val prefs = context.applicationContext.getSharedPreferences("mika_voice_model_accounts", Context.MODE_PRIVATE)
            return VoiceCredentialStore(
                backend = object : VoiceCredentialBackend {
                    override fun read(secretId: String): String? = prefs.getString(key(secretId), null)
                    override fun write(secretId: String, value: String): Boolean =
                        prefs.edit().putString(key(secretId), value).commit()
                    override fun delete(secretId: String) {
                        prefs.edit().remove(key(secretId)).apply()
                    }
                },
                cipher = KeystoreKeyCipher("mika-voice-model-accounts"),
            )
        }

        private fun validSecretId(secretId: String): Boolean = SECRET_ID.matches(secretId)
        private fun key(secretId: String) = "voice_owned_$secretId"
    }
}

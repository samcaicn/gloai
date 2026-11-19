package com.wangzan101.mika.calls

import android.content.Context
import android.os.Build
import com.wangzan101.mika.settings.KeyCipher
import java.util.Base64
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class CallVoiceProtocol { VOLC_DUPLEX, OPENAI_REALTIME }

@Serializable
data class CallVoiceProfile(
    val id: String = "",
    val name: String = "",
    val protocol: CallVoiceProtocol = CallVoiceProtocol.VOLC_DUPLEX,
    val endpoint: String = VolcRealtimeProtocol.ENDPOINT,
    val model: String = VolcRealtimeConfig.MODEL,
    val speaker: String = VolcRealtimeConfig.DEFAULT_SPEAKER,
    val credentialsConfigured: Boolean = false,
    val connectionTested: Boolean = false,
    val audioVerifications: Set<String> = emptySet(),
) {
    override fun toString(): String = "CallVoiceProfile(id=$id, protocol=$protocol, model=$model, configured=$credentialsConfigured, tested=$connectionTested)"

    companion object {
        const val DEFAULT_ID = "default"
        const val MAX_PROFILES = 20
        const val MAX_NAME = 64
        const val MAX_ENDPOINT = 2_048
        const val MAX_MODEL = 128
    }
}

@Serializable
private data class StoredVoiceCredentials(val apiKey: String = "", val appId: String = "", val accessKey: String = "")

@Serializable
private data class StoredVoiceProfiles(
    val profiles: List<CallVoiceProfile> = emptyList(),
    val credentials: Map<String, StoredVoiceCredentials> = emptyMap(),
)

internal interface CallVoiceProfileBackend {
    fun read(): String?
    fun write(value: String): Boolean
}

class CallVoiceProfileStore internal constructor(
    private val backend: CallVoiceProfileBackend,
    private val cipher: KeyCipher? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var stored = StoredVoiceProfiles()
    private val mutable = MutableStateFlow<List<CallVoiceProfile>>(emptyList())
    val state = mutable.asStateFlow()
    val revision = MutableStateFlow(0L)
    private var loaded = false

    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        return try {
            val raw = backend.read()
            require(raw == null || raw.length <= MAX_STORAGE_CHARS)
            val decoded = raw?.let {
                if (cipher == null) it else {
                    val bytes = cipher.decrypt(Base64.getDecoder().decode(it))
                    try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
                }
            }
            stored = decoded?.let { json.decodeFromString<StoredVoiceProfiles>(it) } ?: StoredVoiceProfiles()
            require(stored.profiles.size <= CallVoiceProfile.MAX_PROFILES)
            require(stored.profiles.map { it.id }.distinct().size == stored.profiles.size)
            stored.profiles.forEach(::validate)
            mutable.value = stored.profiles
            loaded = true
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun get(id: String): CallVoiceProfile? {
        if (!load()) return null
        return stored.profiles.firstOrNull { it.id == id }
    }

    @Synchronized
    fun save(profile: CallVoiceProfile, credentials: VolcRealtimeCredentials? = null): CallVoiceProfile {
        check(load()) { "无法读取实时语音配置" }
        require(credentials == null || credentials.valid) { "实时语音凭据不完整" }
        val id = profile.id.ifBlank(newId)
        val existing = stored.profiles.firstOrNull { it.id == id }
        require(existing == null || existing.protocol == profile.protocol) { "请为其他服务商新建配置" }
        if (profile.protocol == CallVoiceProtocol.OPENAI_REALTIME) {
            require(credentials == null || credentials.apiKey.isNotBlank()) { "请填写 OpenAI API Key" }
        }
        val normalized = profile.copy(id = id, name = profile.name.trim(), endpoint = profile.endpoint.trim(),
            model = profile.model.trim(), speaker = profile.speaker.trim().ifBlank { if (profile.protocol == CallVoiceProtocol.OPENAI_REALTIME) OpenAiCallProtocol.VOICE else VolcRealtimeConfig.DEFAULT_SPEAKER })
        validate(normalized)
        require(stored.profiles.size < CallVoiceProfile.MAX_PROFILES || existing != null) { "最多保存 ${CallVoiceProfile.MAX_PROFILES} 套实时语音配置" }
        val serviceChanged = existing != null && (existing.protocol != normalized.protocol || existing.endpoint != normalized.endpoint || existing.model != normalized.model)
        val savedCredentials = credentials?.let {
            StoredVoiceCredentials(it.apiKey, it.appId, it.accessKey)
        } ?: stored.credentials[id]
        val configured = savedCredentials?.let {
            VolcRealtimeCredentials(it.apiKey, it.appId, it.accessKey).valid
        } == true
        val next = normalized.copy(
            credentialsConfigured = configured,
            connectionTested = !serviceChanged && credentials == null && (existing?.connectionTested ?: normalized.connectionTested),
            audioVerifications = existing?.audioVerifications ?: normalized.audioVerifications,
        )
        val profiles = if (existing == null) stored.profiles + next else stored.profiles.map { if (it.id == id) next else it }
        val secrets = stored.credentials.toMutableMap()
        if (configured) secrets[id] = savedCredentials!! else secrets.remove(id)
        persist(StoredVoiceProfiles(profiles, secrets.toMap()))
        return next
    }

    @Synchronized
    fun delete(id: String, activeId: String) {
        check(load()) { "无法读取实时语音配置" }
        require(id != activeId) { "请先选择另一套默认配置" }
        require(stored.profiles.any { it.id == id }) { "实时语音配置不存在" }
        persist(StoredVoiceProfiles(stored.profiles.filterNot { it.id == id }, stored.credentials - id))
    }

    @Synchronized
    fun markConnectionTested(id: String, tested: Boolean) {
        edit(id) { it.copy(connectionTested = tested) }
    }

    @Synchronized
    fun markAudioVerified(id: String, mode: CallAudioMode, verified: Boolean, fingerprint: String = Build.FINGERPRINT ?: "unknown") {
        val key = verificationKey(mode, fingerprint)
        edit(id) { profile ->
            profile.copy(audioVerifications = if (verified) profile.audioVerifications + key else profile.audioVerifications - key)
        }
    }

    @Synchronized
    fun isAudioVerified(id: String, mode: CallAudioMode, fingerprint: String = Build.FINGERPRINT ?: "unknown"): Boolean =
        get(id)?.audioVerifications?.contains(verificationKey(mode, fingerprint)) == true

    @Synchronized
    fun credentials(id: String): VolcRealtimeCredentials? {
        if (!load()) return null
        val storedCredentials = stored.credentials[id] ?: return null
        return VolcRealtimeCredentials(storedCredentials.apiKey, storedCredentials.appId, storedCredentials.accessKey).takeIf { it.valid }
    }

    @Synchronized
    internal fun migrateLegacy(config: CallConfig, readCredentials: () -> VolcRealtimeCredentials?): Boolean {
        if (!load()) return false
        val default = CallVoiceProfile(
            id = CallVoiceProfile.DEFAULT_ID,
            name = "默认实时语音",
            model = config.model,
            speaker = config.speaker,
        )
        val existing = stored.profiles.firstOrNull { it.id == default.id }
        if (stored.profiles.isNotEmpty() && (existing == null || existing.credentialsConfigured ||
                existing.name != default.name || existing.protocol != default.protocol ||
                existing.endpoint != default.endpoint || existing.model != default.model)) return true
        // A failed read must leave the store eligible for another attempt.
        val credentials = readCredentials()?.takeIf { it.valid }
        if (existing != null && credentials == null) return true
        val profile = (existing ?: default).copy(
            credentialsConfigured = credentials != null,
            connectionTested = credentials != null,
            audioVerifications = existing?.audioVerifications.orEmpty() +
                if (config.audioVerified) setOf(verificationKey(config.audioMode, Build.FINGERPRINT ?: "unknown")) else emptySet(),
        )
        validate(profile)
        val profiles = if (existing == null) listOf(profile) else stored.profiles.map { if (it.id == profile.id) profile else it }
        val secrets = if (credentials == null) stored.credentials else stored.credentials +
            (profile.id to StoredVoiceCredentials(credentials.apiKey, credentials.appId, credentials.accessKey))
        persist(StoredVoiceProfiles(profiles, secrets))
        return true
    }

    private fun edit(id: String, change: (CallVoiceProfile) -> CallVoiceProfile) {
        check(load()) { "无法读取实时语音配置" }
        val current = stored.profiles.firstOrNull { it.id == id } ?: return
        persist(stored.copy(profiles = stored.profiles.map { if (it.id == id) change(current) else it }))
    }

    private fun persist(next: StoredVoiceProfiles) {
        val plain = json.encodeToString(next).toByteArray()
        val encoded = try {
            if (cipher == null) String(plain, Charsets.UTF_8) else {
                Base64.getEncoder().encodeToString(cipher.encrypt(plain))
            }
        } finally {
            plain.fill(0)
        }
        check(backend.write(encoded)) { "无法保存实时语音配置" }
        stored = next
        mutable.value = next.profiles
        revision.value++
    }

    private fun validate(profile: CallVoiceProfile) {
        require(Regex("[A-Za-z0-9_-]{1,100}").matches(profile.id)) { "配置编号无效" }
        require(profile.name.isNotBlank() && profile.name.length <= CallVoiceProfile.MAX_NAME) { "配置名称需为 1–${CallVoiceProfile.MAX_NAME} 字" }
        require(profile.endpoint.length <= CallVoiceProfile.MAX_ENDPOINT) { "接口地址过长" }
        if (profile.protocol == CallVoiceProtocol.OPENAI_REALTIME) require(profile.endpoint == OpenAiCallProtocol.ENDPOINT) { "OpenAI 接口地址无效" }
        val uri = runCatching { java.net.URI(profile.endpoint) }.getOrNull()
        require(uri != null && uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "接口地址必须是有效的 wss 地址" }
        require(profile.model.isNotBlank() && profile.model.length <= CallVoiceProfile.MAX_MODEL && profile.model.all { it.code in 33..126 }) { "实时模型名称无效" }
        require(profile.speaker.length <= CallPolicy.MAX_SPEAKER_CHARS && profile.speaker.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_-" }) { "声音编号无效" }
    }

    private fun verificationKey(mode: CallAudioMode, fingerprint: String) = "${mode.name}:$fingerprint"

    companion object {
        private const val MAX_STORAGE_CHARS = 500_000
        fun create(context: Context): CallVoiceProfileStore {
            val prefs = context.applicationContext.getSharedPreferences("mika_call_voice_profiles", Context.MODE_PRIVATE)
            val cipher = KeystoreKeyCipher("mika-call-voice-profiles")
            return CallVoiceProfileStore(object : CallVoiceProfileBackend {
                override fun read(): String? = prefs.getString("encrypted_profiles_v1", null)
                override fun write(value: String): Boolean = prefs.edit().putString("encrypted_profiles_v1", value).commit()
            }, cipher)
        }
    }
}

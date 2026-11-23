package com.wangzan101.mika.calls

import com.wangzan101.mika.R
import com.wangzan101.mika.voice.cascade.CallUse
import com.wangzan101.mika.voice.model.CallEngine
import com.wangzan101.mika.voice.model.RealtimeSelectionController
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.VoiceModelRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wangzan101.mika.calls.CallRuntime.Companion.STANDARD_PROFILE_ID

// 电话运行时:语音档案、通话语音选择与试音
fun CallRuntime.activeVoiceProfile(): CallVoiceProfile {
    val config = settings.state.value
    if (standardCalls) {
        val ready = cascade.blocker(CallUse.Phone) == null
        return CallVoiceProfile(id = STANDARD_PROFILE_ID, name = STANDARD_PROFILE_ID, credentialsConfigured = ready, connectionTested = ready)
    }
    if (realtimeController.migrated) {
        val ref = realtimeController.callCurrent()
        if (ref != null) {
            return runCatching { realtimeController.callProfile() }.getOrElse {
                CallVoiceProfile(id = ref.modelId, name = ref.modelId, credentialsConfigured = false, connectionTested = false)
            }
        }
        return CallVoiceProfile(id = config.activeVoiceProfileId, name = app.getString(R.string.calls_voice_default_name), credentialsConfigured = false, connectionTested = false)
    }
    return voiceProfiles.get(config.activeVoiceProfileId) ?: CallVoiceProfile(
        id = config.activeVoiceProfileId,
        name = app.getString(R.string.calls_voice_default_name),
        speaker = config.speaker,
        credentialsConfigured = config.credentialsConfigured,
        connectionTested = config.credentialsConfigured,
    )
}

internal suspend fun CallRuntime.loadVoiceProfiles() {
    if (realtimeController.migrated) {
        syncRealtimeState()
        return
    }
    val migrated = withContext(Dispatchers.IO) {
        try {
            voiceProfiles.migrateLegacy(settings.state.value, settings::readLegacyCredentials)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
    if (!migrated) return
    val config = settings.state.value
    val profile = voiceProfiles.get(config.activeVoiceProfileId)
    val next = config.copy(
        credentialsConfigured = profile?.credentialsConfigured == true,
        audioVerified = profile != null && voiceProfiles.isAudioVerified(profile.id, config.audioMode),
    )
    if (next != config) runCatching { settings.save(next) }
}

/** Mirrors the catalog verification state into [CallSettings] so every gate reads one source. */
internal fun CallRuntime.syncRealtimeState(mode: CallAudioMode = settings.state.value.audioMode) {
    val ref = callVoiceRef()
    runCatching {
        synchronized(settings) {
            settings.mirrorRealtimeState(
                credentialsConfigured = if (standardCalls) cascade.blocker(CallUse.Phone) == null
                else ref?.let { realtimeController.credentialsConfigured(it) } ?: false,
                audioVerified = ref?.let { realtimeController.isAudioVerified(it, mode) } ?: false,
            )
        }
    }
}

/** Legacy connection path uses the plain provider; after migration the catalog creates it. */
internal fun CallRuntime.realtimeProvider(): CallRealtimeProvider =
    if (realtimeController.migrated) realtimeController.callProvider() else ProfileRealtimeProvider()

suspend fun CallRuntime.selectVoiceProfile(id: String): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("请先结束当前通话或锁屏拨出安排")
    if (realtimeController.migrated) {
        val profile = voiceProfiles.get(id) ?: return@onMain fail("实时语音配置不存在")
        val ref = RealtimeSelectionController.legacyProfileRef(id, profile.model)
        realtimeController.select(ref)
        val config = settings.state.value
        settings.save(config.copy(
            credentialsConfigured = realtimeController.credentialsConfigured(ref),
            audioVerified = realtimeController.isAudioVerified(ref, config.audioMode),
            autoAnswer = false,
        ))
        publish()
        return@onMain ok("已选择${profile.name}，自动接听需要重新开启")
    }
    val profile = voiceProfiles.get(id) ?: return@onMain fail("实时语音配置不存在")
    settings.save(settings.state.value.copy(
        activeVoiceProfileId = profile.id,
        speaker = profile.speaker,
        credentialsConfigured = profile.credentialsConfigured,
        audioVerified = voiceProfiles.isAudioVerified(profile.id, settings.state.value.audioMode),
        autoAnswer = false,
    ))
    publish()
    ok("已选择${profile.name}，自动接听需要重新开启")
}

/**
 * 迁移后选电话用的实时语音:[ref] 为 null 跟随「语音通话」,否则电话单独用 [ref](可带电话专用音色 [speaker])。
 * 实际用的模型变了就关掉自动接听,要用户在新模型上重新确认;凭据与验证镜像由目录同步。
 */
suspend fun CallRuntime.selectCallVoice(ref: VoiceModelRef?, speaker: String = ""): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("请先结束当前通话或锁屏拨出安排")
    if (!realtimeController.migrated) return@onMain fail("实时语音配置尚未迁移")
    val before = realtimeController.callCurrent()
    try {
        realtimeController.selectForCalls(ref, speaker)
    } catch (_: VoiceModelError) {
        return@onMain fail("实时语音模型不存在")
    }
    val now = realtimeController.callCurrent()
    val config = settings.state.value
    settings.save(config.copy(
        credentialsConfigured = now?.let { realtimeController.credentialsConfigured(it) } ?: false,
        audioVerified = now?.let { realtimeController.isAudioVerified(it, config.audioMode) } ?: false,
        autoAnswer = config.autoAnswer && now == before,
    ))
    publish()
    ok(if (now == before) "电话语音已保存" else "电话语音已更换，自动接听需要重新开启")
}

/**
 * Phone calls' own engine and listen/speak models ([com.wangzan101.mika.voice.model.VoiceCatalogStore.setPhoneVoice]);
 * a null [engine] with no models makes phone calls follow the voice settings again, realtime choice included.
 */
suspend fun CallRuntime.selectPhoneVoice(engine: CallEngine?, stt: VoiceModelRef?, tts: VoiceModelRef?, ttsVoice: String = ""): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("请先结束当前通话或锁屏拨出安排")
    if (!realtimeController.migrated) return@onMain fail("实时语音配置尚未迁移")
    val before = standardCalls to callVoiceRef()
    realtimeController.catalog.setPhoneVoice(engine, stt, tts, ttsVoice)
    if (engine == null && stt == null && tts == null) realtimeController.selectForCalls(null)
    syncRealtimeState()
    val changed = (standardCalls to callVoiceRef()) != before
    if (changed) settings.save(settings.state.value.copy(autoAnswer = false))
    publish()
    ok(if (changed) "电话语音已更换，自动接听需要重新开启" else "电话语音已保存")
}

suspend fun CallRuntime.saveVoiceProfile(profile: CallVoiceProfile, credentials: VolcRealtimeCredentials? = null): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("请先结束当前通话或锁屏拨出安排")
    if (realtimeController.migrated) {
        val ref = realtimeController.callCurrent() ?: return@onMain fail("请先选择实时语音模型")
        val model = runCatching { realtimeController.model(ref) }.getOrNull()
            ?: return@onMain fail("实时语音模型不存在")
        val binding = model.realtime ?: return@onMain fail("实时语音模型不支持通话")
        if (binding.speaker != profile.speaker) {
            realtimeController.catalog.upsertModel(model.copy(realtime = binding.copy(speaker = profile.speaker)))
        }
        publish()
        return@onMain ok("实时语音配置已保存")
    }
    val previous = voiceProfiles.get(profile.id)
    val saved = withContext(Dispatchers.IO) { voiceProfiles.save(profile, credentials) }
    if (settings.state.value.activeVoiceProfileId == saved.id) {
        val serviceChanged = credentials != null || previous != null &&
            (previous.protocol != saved.protocol || previous.endpoint != saved.endpoint || previous.model != saved.model)
        settings.save(settings.state.value.copy(
            speaker = saved.speaker,
            credentialsConfigured = saved.credentialsConfigured,
            audioVerified = voiceProfiles.isAudioVerified(saved.id, settings.state.value.audioMode),
            autoAnswer = if (serviceChanged) false else settings.state.value.autoAnswer,
        ))
    }
    publish()
    ok("实时语音配置已保存")
}

suspend fun CallRuntime.deleteVoiceProfile(id: String): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("请先结束当前通话或锁屏拨出安排")
    if (realtimeController.migrated) return@onMain fail("请在语音模型目录中删除实时模型")
    withContext(Dispatchers.IO) { voiceProfiles.delete(id, settings.state.value.activeVoiceProfileId) }
    publish()
    ok("实时语音配置已删除")
}

suspend fun CallRuntime.testVoiceProfile(id: String): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("请先结束当前通话或锁屏拨出安排")
    if (realtimeController.migrated) {
        val legacy = voiceProfiles.get(id) ?: return@onMain fail("实时语音配置不存在")
        val ref = RealtimeSelectionController.legacyProfileRef(id, legacy.model)
        val provider = try {
            realtimeController.provider(ref)
        } catch (e: VoiceModelError) {
            return@onMain fail("实时语音配置不可用，请检查凭据")
        }
        realtimeController.markConnectionTested(ref, false)
        publish()
        val result = try {
            val session = provider.connect(CallVoiceProfile(), VolcRealtimeCredentials(), "连接测试。不要生成语音。")
            session.close()
            realtimeController.markConnectionTested(ref, true)
            if (realtimeController.callCurrent() == ref) publish()
            ok("实时语音服务连接成功；手机双向音频仍需通过测试电话验证")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val reason = when ((error as? VoiceModelError)?.category) {
                VoiceErrorCategory.AUTH_FAILED -> "凭据或服务权限无效"
                VoiceErrorCategory.RATE_LIMITED -> "实时语音服务请求过于频繁"
                VoiceErrorCategory.TIMEOUT -> "实时语音服务连接超时"
                else -> "无法连接实时语音服务"
            }
            fail(reason)
        }
        return@onMain result
    }
    val profile = voiceProfiles.get(id) ?: return@onMain fail("实时语音配置不存在")
    val credentials = voiceProfiles.credentials(id) ?: return@onMain fail("请先配置实时语音凭据")
    voiceProfiles.markConnectionTested(id, false)
    publish()
    try {
        val session = realtimeProvider().connect(profile, credentials, "连接测试。不要生成语音。")
        session.close()
        voiceProfiles.markConnectionTested(id, true)
        if (settings.state.value.activeVoiceProfileId == id) publish()
        ok("实时语音服务连接成功；手机双向音频仍需通过测试电话验证")
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        val reason = when ((error as? VolcRealtimeException)?.kind) {
            VolcRealtimeException.Kind.AUTH -> "凭据或服务权限无效"
            VolcRealtimeException.Kind.RATE_LIMITED -> "实时语音服务请求过于频繁"
            VolcRealtimeException.Kind.TIMEOUT -> "实时语音服务连接超时"
            else -> "无法连接实时语音服务"
        }
        fail(reason)
    }
}

suspend fun CallRuntime.setAudioVerified(verified: Boolean): CallActionResult = onMain {
    if (work != null || lockedDial != null) return@onMain fail("请先结束当前通话或锁屏拨出安排")
    val config = settings.state.value
    if (realtimeController.migrated) {
        val ref = callVoiceRef() ?: return@onMain fail(if (standardCalls) "请先选择通话的听写模型" else "请先选择实时语音模型")
        realtimeController.markAudioVerified(ref, config.audioMode, verified)
        settings.save(config.copy(audioVerified = verified, autoAnswer = verified && config.autoAnswer))
        publish()
        return@onMain ok(if (verified) "已记录本机双向音频验证" else "已取消双向音频验证，自动接听已关闭")
    }
    val id = config.activeVoiceProfileId
    withContext(Dispatchers.IO) { voiceProfiles.markAudioVerified(id, config.audioMode, verified) }
    settings.save(config.copy(audioVerified = verified, autoAnswer = verified && config.autoAnswer))
    publish()
    ok(if (verified) "已记录本机双向音频验证" else "已取消双向音频验证，自动接听已关闭")
}

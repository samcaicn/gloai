package com.wangzan101.mika.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.clientHeaders
import com.wangzan101.mika.settings.providerKey
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.adapters.SttHooks
import kotlinx.coroutines.CancellationException

/** 界面(输入栏、悬浮胶囊)拿语音会话的地方;App 里是 [VoiceInput],界面测试换成假的。 */
interface VoiceSource {
    fun spec(): SpeechEngineSpec
    fun start(language: String? = null): SpeechSession
}

/**
 * 语音输入的入口:对话页输入框和悬浮胶囊只经这里拿会话。
 * 迁移前沿用旧选择;迁移后由同一目录解析显式模型,不再按服务商或模型名选择实现。
 */
class VoiceInput(
    context: Context,
    private val settings: SpeechSettings,
    private val providers: ProviderStore,
    private val transcriber: (Provider) -> Transcriber = { p -> Transcriber(ProviderStore.modelHttp, providers.clientHeaders(p)) },
    private val voiceModels: VoiceModelEnvironment = VoiceModelEnvironment.get(context),
    private val systemCapability: () -> SystemCapability = {
        SystemSpeech.capability(context, settings.systemSilent.value)
    },
    private val systemCompatibility: () -> Boolean = { SystemSpeech.serviceCompatibility() },
) : VoiceSource {
    private val context = context.applicationContext

    /** 界面先问一下能不能用、用的是哪个(卡片顶上那个小标签)。 */
    override fun spec(): SpeechEngineSpec {
        if (!voiceModels.stt.migrated) return legacySpec()
        return try {
            migratedSpec()
        } catch (_: VoiceModelError) {
            SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderGone)
        }
    }

    private fun legacySpec(): SpeechEngineSpec =
        SpeechResolver.resolve(settings.choice.value, systemCapability(), providers.providers.value)

    private fun migratedSpec(): SpeechEngineSpec {
        if (voiceModels.stt.catalog.state.value.error != null) {
            throw VoiceModelError.IncompleteConfig("Voice catalog is not readable")
        }
        return when (val selection = voiceModels.stt.current()) {
            SttSelection.Auto -> SpeechResolver.resolve(SpeechChoice.Auto, systemCapability(), emptyList())
            SttSelection.Off -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off)
            is SttSelection.Model -> if (selection.ref == SttSelectionController.SYSTEM_STT_REF) {
                SpeechResolver.resolve(SpeechChoice.System, systemCapability(), emptyList())
            } else {
                voiceModels.stt.spec(selection.ref)
            }
        }
    }

    /**
     * 开一次会话,按下时调(要在主线程)。[language] 是识别语言(BCP-47);null 跟 App 界面语言。
     * 没有录音权限、引擎用不了时,给出只发一个 Failed 的会话,界面按同一套处理。
     */
    override fun start(language: String?): SpeechSession =
        if (voiceModels.stt.migrated) startMigrated(language) else startLegacy(language)

    private fun startMigrated(language: String?): SpeechSession {
        val spec = try {
            migratedSpec()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return FailedSession(SpeechFailure.Provider(VoiceModelError.from(error)))
        }
        if (spec is SpeechEngineSpec.Unavailable) return FailedSession(SpeechFailure.Unavailable(spec.why))
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return FailedSession(SpeechFailure.NoPermission)
        }
        val tag = language ?: SystemSpeech.speechTag(AppLanguage.current(context))
        val selection = voiceModels.stt.current()
        val ref = when (selection) {
            SttSelection.Auto -> SttSelectionController.SYSTEM_STT_REF
            SttSelection.Off -> return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.Off))
            is SttSelection.Model -> selection.ref
        }
        val hooks = if (ref == SttSelectionController.SYSTEM_STT_REF) {
            SttHooks(
                onNoResponse = settings::noteSystemSilent,
                onAnswered = settings::noteSystemAnswered,
                systemCapability = systemCapability(),
                rememberedService = settings.systemService,
                compatibility = systemCompatibility(),
                serviceWithoutMic = { named, onDevice -> SpeechServiceMic.missing(context, named, onDevice) },
            )
        } else {
            SttHooks()
        }
        return try {
            voiceModels.stt.start(context, ref, tag, hooks)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            FailedSession(SpeechFailure.Provider(VoiceModelError.from(error)))
        }
    }

    private fun startLegacy(language: String?): SpeechSession {
        val spec = legacySpec()
        if (spec is SpeechEngineSpec.Unavailable) return FailedSession(SpeechFailure.Unavailable(spec.why))
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return FailedSession(SpeechFailure.NoPermission)
        }
        val tag = language ?: SystemSpeech.speechTag(AppLanguage.current(context))
        return when (spec) {
            is SpeechEngineSpec.Doubao -> {
                val credentials = settings.doubaoCredentials()
                    ?: return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.DoubaoNotConfigured))
                DoubaoSpeech(PcmRecorder(streaming = true), { DoubaoTransport().connect(credentials, spec.resourceId) })
            }
            is SpeechEngineSpec.FunAsr -> LiveSpeech(PcmRecorder(streaming = true, unbounded = true), FunAsrProtocol(spec.language), { LiveAsrTransport().connect(spec.url) })
            is SpeechEngineSpec.Bailian -> {
                val key = settings.bailianKey(spec.region)
                    ?: return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.BailianNotConfigured))
                LiveSpeech(PcmRecorder(streaming = true, unbounded = true), BailianProtocol(spec.model, spec.language), {
                    LiveAsrTransport().connect(spec.region.endpoint(spec.workspace), mapOf("Authorization" to "Bearer $key"))
                })
            }
            is SpeechEngineSpec.System -> {
                val compatibility = SystemSpeech.serviceCompatibility()
                SystemSpeechSession(
                    context, spec.onDevice, tag,
                    onNoResponse = settings::noteSystemSilent, onAnswered = settings::noteSystemAnswered,
                    attempts = SystemSpeech.attempts(SystemSpeech.services(context), settings.systemService, compatibility),
                    compatibility = compatibility,
                    serviceWithoutMic = { named, onDevice -> SpeechServiceMic.missing(context, named, onDevice) },
                )
            }
            is SpeechEngineSpec.Provider -> {
                val iso = SystemSpeech.isoCode(tag)
                ProviderSpeech(PcmRecorder(), { wav ->
                    val provider = providers.provider(spec.provider.id)
                        ?: throw ModelError.BadResponse(tr(R.string.voice_unavailable_provider_gone))
                    transcriber(provider).transcribe(provider, providers.providerKey(provider.id), spec.model, spec.method, wav, iso)
                })
            }
            is SpeechEngineSpec.Unavailable -> FailedSession(SpeechFailure.Unavailable(spec.why))
            // Vendor recognition exists only in the catalog path; the legacy path never resolves to it.
            is SpeechEngineSpec.Vendor -> FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone))
        }
    }
}

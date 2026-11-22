package com.wangzan101.mika.voice

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.Provider

/**
 * 设置 › 模型 里「语音转文字」用到的纯逻辑:选择页怎么给服务商分组、用途卡片右边写什么。
 * 不碰界面和网络,所以能直接单测。
 */
object SpeechOptions {
    /**
     * 选择页里的一组:[models] 只放像语音模型的。每组下面都另有一个手填入口(界面负责):
     * 服务商的模型列表常常不全,语音模型尤其容易漏掉,也可能根本不提供列表。
     */
    data class Group(val provider: Provider, val models: List<String>)

    /** 能做转写的服务商各一组;模型按服务商给的列表原样列出,不按名字猜用途。 */
    fun groups(providers: List<Provider>): List<Group> =
        providers.filter(Transcriber::supports).map { provider ->
            Group(provider, provider.models.orEmpty().map { it.id })
        }

    /** 卡片右边两行:[title] 是选了什么,[sub] 是补充(可能没有),[subOk] 为真时补充用「可用」的绿色。 */
    data class Summary(val title: String, val sub: String?, val subOk: Boolean)

    fun summary(choice: SpeechChoice, system: SystemCapability, providers: List<Provider>): Summary {
        val unset = Summary(tr(R.string.settings_not_set), null, false)
        // 系统说有、试过却不响应
        val silent = Summary(tr(R.string.speech_system), tr(R.string.speech_system_silent), false)
        return when (choice) {
            SpeechChoice.Off -> Summary(tr(R.string.models_off), null, false)
            is SpeechChoice.Doubao -> Summary(tr(R.string.speech_doubao_model), tr(R.string.speech_doubao_provider), false)
            is SpeechChoice.FunAsr -> Summary(tr(R.string.speech_funasr_model), FunAsrProtocol.hostOf(choice.url), false)
            is SpeechChoice.Bailian -> Summary(
                choice.model,
                tr(R.string.speech_bailian_provider) + " · " + tr(if (choice.region == BailianRegion.Singapore) R.string.speech_bailian_singapore else R.string.speech_bailian_beijing),
                false,
            )
            SpeechChoice.Auto -> when {
                system.usable -> systemSummary(system)
                system.available -> silent
                else -> unset
            }
            SpeechChoice.System -> when {
                // 明确选了手机自带、现在却用不了:照实说,不悄悄换别的
                system.usable -> systemSummary(system)
                system.available -> silent
                else -> Summary(tr(R.string.speech_system), tr(R.string.speech_system_gone), false)
            }
            is SpeechChoice.Provider -> {
                val p = providers.firstOrNull { it.id == choice.providerId } ?: return unset
                Summary(choice.model, p.name, false)
            }
        }
    }

    /** 边说边转的服务在设置页里试连没通时的说法;不带服务端原话。 */
    fun probeFailure(e: Throwable): String = when {
        e is ModelError.Auth || e is LiveAsrError && e.auth -> tr(R.string.speech_live_auth)
        e is LiveAsrError -> tr(R.string.speech_live_refused, e.code)
        e is ModelError.RateLimited -> tr(R.string.speech_live_refused, "429")
        else -> tr(R.string.speech_live_unreachable)
    }

    private fun systemSummary(system: SystemCapability) = Summary(
        tr(R.string.speech_system),
        tr(if (system.onDevice) R.string.speech_system_offline else R.string.speech_system_online),
        subOk = system.onDevice,
    )
}

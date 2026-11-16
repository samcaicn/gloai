package com.wangzan101.mika.voice

import com.wangzan101.mika.settings.Provider

/**
 * 手机自带识别的能力:系统说有没有([available])、是不是在本机上识别(不联网)、
 * 以及在这台手机上试过时理不理我们([responsive];系统说有、请求发过去却一个回调都不给,就记成 false)。
 */
data class SystemCapability(val available: Boolean, val onDevice: Boolean, val responsive: Boolean = true) {
    /** 真能拿来听写:系统有、而且没被记成不响应。 */
    val usable get() = available && responsive
}

/** 这一次实际要用的识别引擎;选择的东西不在了就给出 [Unavailable] 和原因,由界面提示。 */
sealed interface SpeechEngineSpec {
    data class System(val onDevice: Boolean) : SpeechEngineSpec
    data class Provider(val provider: com.wangzan101.mika.settings.Provider, val model: String, val method: TranscribeMethod) : SpeechEngineSpec
    data class Doubao(val resourceId: String) : SpeechEngineSpec
    data class FunAsr(val url: String, val language: AsrLanguage = AsrLanguage.Auto) : SpeechEngineSpec
    data class Bailian(val model: String, val region: BailianRegion, val workspace: String = "", val language: AsrLanguage = AsrLanguage.Auto) : SpeechEngineSpec
    data class Unavailable(val why: Why) : SpeechEngineSpec
    /**
     * [SystemNoResponse]:手机自带识别系统说有,但请求发过去一直没有回应(已记下,用户重新选它时清掉)。
     * [SystemFailed]:手机自带识别未能启动,可以重试;不据此认定服务不存在或麦克风被占用。
     */
    enum class Why { Off, NoSystemNoModel, SystemGone, SystemNoResponse, SystemFailed, ProviderGone, DoubaoNotConfigured, BailianNotConfigured }
}

/** 把用户的选择、手机能力和现有服务商对起来,得出这次用哪个引擎。纯函数,不碰系统。 */
object SpeechResolver {
    fun resolve(choice: SpeechChoice, system: SystemCapability, providers: List<Provider>): SpeechEngineSpec = when (choice) {
        SpeechChoice.Off -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off)
        is SpeechChoice.Doubao -> if (choice.resourceId in DoubaoProtocol.resources) SpeechEngineSpec.Doubao(choice.resourceId)
            else SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.DoubaoNotConfigured)
        is SpeechChoice.FunAsr -> SpeechEngineSpec.FunAsr(choice.url, choice.language)
        // 有没有存 Key 由 VoiceInput 开会话时再看(Key 不进选择),这里只按选择给
        is SpeechChoice.Bailian -> SpeechEngineSpec.Bailian(choice.model, choice.region, choice.workspace, choice.language)
        // 自带识别不响应时照实说、指去设置,不悄悄改用要花钱的服务商模型
        SpeechChoice.Auto -> when {
            system.usable -> SpeechEngineSpec.System(system.onDevice)
            system.available -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
            else -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.NoSystemNoModel)
        }
        SpeechChoice.System -> when {
            system.usable -> SpeechEngineSpec.System(system.onDevice)
            system.available -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
            else -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemGone)
        }
        is SpeechChoice.Provider -> {
            val p = providers.firstOrNull { it.id == choice.providerId }
            if (p == null) SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderGone)
            else SpeechEngineSpec.Provider(p, choice.model, choice.method)
        }
    }
}

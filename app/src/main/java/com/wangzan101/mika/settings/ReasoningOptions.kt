package com.wangzan101.mika.settings

import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.model.ListedModel

/**
 * 思考档位。服务商列表写明档位的模型,四种协议(Chat Completions、Messages、generateContent、Responses)都照它给
 * (Anthropic 列表逐项写明每个模型支持的档位和思考方式,见 ModelCatalog;OpenAI 兼容协议另补官方文档写明的 `none`,见 [withDocumentedOff]);
 * 没写明时:Responses 一族给通用手动档位,OpenAI 兼容协议先按预设里官方文档写明的档位给(见 [ProviderPreset.reasoningLevels]),
 * 对不上预设的第三方网关也给通用手动档位——两者都带提示,实际支持情况以服务端为准、默认不发字段;其余协议不猜:
 * 各模型认的档位和写法不一样,猜错了每次请求都被拒。
 */
data class ReasoningOptions(val levels: List<String>, val fromProvider: Boolean) {
    companion object {
        // 列表没说明档位时供手动选择,不声称每个模型都支持;界面会说明,默认始终不指定。
        private val manualLevels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        fun of(config: ProviderConfig, listed: ListedModel?): ReasoningOptions? {
            listed?.reasoningEfforts?.let { declared ->
                // 套餐接口的模型目录曾把服务端不接受的 ultra 列为可选。目录也是外部输入:
                // 这一条接入只展示请求层真正认识的档位,不能让坏元数据把每次调用都变成 HTTP 400。
                val levels = if (config.kind == ProviderConfig.Kind.ChatGpt) declared.filter(manualLevels::contains) else declared
                return ReasoningOptions(withDocumentedOff(config, listed, levels), fromProvider = true)
            }
            // 仍返回空选项,使已保存的旧档位能在界面恢复默认。
            if (listed?.reasoning == false) return ReasoningOptions(emptyList(), fromProvider = true)
            return when (ProviderStore.adapterFor(config, listed?.endpoint).first) {
                ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt, ProviderConfig.Kind.Xai ->
                    ReasoningOptions(manualLevels, fromProvider = false)
                // 预设里这家官方文档写明了几档就给几档;对不上预设的 OpenAI 兼容地址(第三方网关)
                // 也照给通用档位并标「来源不是服务商」,让入口能渲染、由用户显式承担风险;默认仍是「默认」不发字段。
                ProviderConfig.Kind.OpenAiCompat ->
                    ProviderPresets.forBaseUrl(config.baseUrl)?.reasoningLevels
                        ?.takeIf { it.isNotEmpty() }?.let { ReasoningOptions(it, fromProvider = false) }
                        ?: ReasoningOptions(manualLevels, fromProvider = false)
                ProviderConfig.Kind.Anthropic, ProviderConfig.Kind.Gemini -> null
            }
        }

        /**
         * 列表写的是开着思考时可选的强度(DeepSeek 的 `effort.supported_levels` 是 low / high / max),
         * 官方文档另写了 `none` 关掉思考时补在最前面,免得列表一读到就关不掉思考。只补预设里写明的 `none`,不补别的档位。
         */
        private fun withDocumentedOff(config: ProviderConfig, listed: ListedModel, levels: List<String>): List<String> {
            if (levels.isEmpty() || OFF in levels) return levels
            // 按这个模型实际走的接口算:列表给它写了别的协议时不补
            if (ProviderStore.adapterFor(config, listed.endpoint).first != ProviderConfig.Kind.OpenAiCompat) return levels
            val documented = ProviderPresets.forBaseUrl(config.baseUrl)?.reasoningLevels.orEmpty()
            return if (OFF in documented) listOf(OFF) + levels else levels
        }

        private const val OFF = "none"
    }
}

/**
 * 主动能力(后台的便宜调用)建模型时的回复上限和思考档位。
 *
 * 档位:只在服务商列表写明了这个模型的档位([ReasoningOptions.fromProvider])时,取其中最低的一档(按 [RANK] 排,
 * 列表本身可能没排序;不认识的档位不取);列表没写明的(手动档位、预设档位)不猜,照用这个用途选的档位。
 * 回复上限只有 Messages 和 generateContent 两种协议会带上(见 ProviderStore.buildModel),而且只给回复短的那两种请求
 * (判定、归纳话题,见 [ProactiveCall.long]):思考 token 也算在上限里,能确定不思考时(档位是 none,或 Messages 协议没选档位、
 * 本来就不开思考)给 [CAP],否则给宽一些的 [CAP_THINKING],免得思考把上限吃光、回复是空的。
 * 分段、当日、周、月归纳不另设上限(null,和普通任务一样用适配器的默认):一份有十几句、每句上百字,几百 token 放不下,
 * 被截断的回复读不出来,那一份就一直做不成。
 */
internal object ProactiveModelSetup {
    /** 判定一批最多 8 条、每条一句理由;归纳话题最多十几个短标记。按理由都写满算(一个字一个半 token)也放得下。 */
    const val CAP = 2_400
    const val CAP_THINKING = 4_096
    private val RANK = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    /** 服务商写明的档位里最低的一档;没写明、或一档都不认识时为 null。 */
    fun lowestDeclared(options: ReasoningOptions?): String? =
        options?.takeIf { it.fromProvider }?.levels?.filter { it in RANK }?.minByOrNull(RANK::indexOf)

    /** 实际用的档位和回复上限(null 是不另设)。[adapter] 是实际走的协议,[roleEffort] 是这个用途选的档位,[call] 是哪一种请求。 */
    fun of(adapter: ProviderConfig.Kind, options: ReasoningOptions?, roleEffort: String?, call: ProactiveCall): Pair<String?, Int?> {
        val effort = lowestDeclared(options) ?: roleEffort
        val noThinking = effort == "none" || (adapter == ProviderConfig.Kind.Anthropic && effort == null)
        return effort to when {
            call.long -> null
            noThinking -> CAP
            else -> CAP_THINKING
        }
    }
}

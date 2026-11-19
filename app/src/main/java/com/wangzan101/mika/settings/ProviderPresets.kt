package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.PromptCaching
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 添加模型时的常用服务商模板:只预填接口类型、Base URL 和能力的初始勾选,
 * 模型名和 Key 由用户填,能力也可以改。
 * [sessionHeader] 是服务商要求放对话会话号的请求头名(见 [com.wangzan101.mika.core.model.ModelSession]);不要的为 null。
 * [selfHosted] 是用户自己机器上跑的服务:[baseUrl] 只是本机的默认端口,表单里让用户改成那台机器的地址,Key 可以不填。
 * [familyTokens] 是这家自己的模型名里都有的词(小写):Key 下列出的还有别家的模型时,默认先试自家的(见 [ModelOptions.candidates]);
 * 托管多家模型的、自己跑的不给。
 */
data class ProviderPreset(
    val label: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val capabilities: Capabilities,
    val sessionHeader: String? = null,
    val selfHosted: Boolean = false,
    val familyTokens: List<String> = emptyList(),
    /**
     * 这家官方文档写明的思考档位:列表接口不回档位时(OpenAI 兼容协议大多如此),界面上按它给可选档。
     * 只填官方文档确认过的值,不猜;照服务商写明的顺序。
     */
    val reasoningLevels: List<String> = emptyList(),
)

object ProviderPresets {
    private fun caps(vision: Boolean = false, context: Int = 64_000, caching: PromptCaching = PromptCaching.Implicit) =
        Capabilities(toolCalls = true, vision = vision, contextTokens = context, reasoning = false, promptCaching = caching)

    /** Base URL 对得上的模板(添加时选了别的模板、或测试里直接给地址时,按地址补上能力的初始值);没有为 null。 */
    fun forBaseUrl(url: String): ProviderPreset? = all.firstOrNull { it.baseUrl.isNotEmpty() && url.trimEnd('/') == it.baseUrl.trimEnd('/') }

    /**
     * 主机名和哪个服务商模板的地址一样(路径可以不同):自定义填了某家的官方地址时,建议改用那个模板。
     * 自己跑的服务(本机地址)不算。认不出地址或没有对得上的为 null。
     */
    fun forHost(url: String): ProviderPreset? {
        val host = url.trim().toHttpUrlOrNull()?.host ?: return null
        // 智谱的标准 API 和 Coding Plan 同主机不同路径；共同路径更多的是更具体的预设。
        return all.filter { !it.selfHosted && it.baseUrl.isNotEmpty() && it.baseUrl.toHttpUrlOrNull()?.host == host }
            .maxByOrNull { pathOverlap(it.baseUrl, url) }
    }

    val all: List<ProviderPreset> = listOf(
        // V4 起两个模型(deepseek-flash / deepseek-v4-pro)上下文都是 1M:窗口还按旧默认 64k 时,
        // 压缩会频繁改写请求前缀,服务商的前缀缓存整段失效,同一任务的花费能差好几倍
        // 思考档位按官方文档(create-chat-completion):reasoning_effort 可能的值是 none/low/high/max,
        // none 关掉思考,其余开;省略时服务端默认 thinking 开。不填 minimal/medium/xhigh:官方说它们只是别名
        ProviderPreset("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", caps(context = 1_000_000),
            familyTokens = listOf("deepseek"), reasoningLevels = listOf("none", "low", "high", "max")),
        // 这家的 OpenAI 兼容接口上,不少模型(如 qwen3.5 系列)只有显式缓存:不标就从不复用
        ProviderPreset("通义千问", ProviderConfig.Kind.OpenAiCompat, "https://dashscope.aliyuncs.com/compatible-mode/v1", caps(caching = PromptCaching.Explicit), familyTokens = listOf("qwen")),
        ProviderPreset("Kimi", ProviderConfig.Kind.OpenAiCompat, "https://api.moonshot.cn/v1", caps(), familyTokens = listOf("kimi", "moonshot")),
        ProviderPreset("智谱", ProviderConfig.Kind.OpenAiCompat, "https://open.bigmodel.cn/api/paas/v4", caps(), familyTokens = listOf("glm")),
        // Coding Plan 的 Key 和标准 API 分开；OpenAI Chat Completion 专用地址见智谱的 Coding Plan 接入指南。
        ProviderPreset("智谱 Coding Plan", ProviderConfig.Kind.OpenAiCompat, "https://open.bigmodel.cn/api/coding/paas/v4", caps(), familyTokens = listOf("glm")),
        // 托管多家开源模型,能不能看图按模型定
        ProviderPreset("硅基流动", ProviderConfig.Kind.OpenAiCompat, "https://api.siliconflow.cn/v1", caps()),
        // 豆包:模型名填模型 ID 或推理接入点 ID(ep-…);这家不一定提供列模型接口,列不出时手填
        ProviderPreset("火山方舟", ProviderConfig.Kind.OpenAiCompat, "https://ark.cn-beijing.volces.com/api/v3", caps(vision = true, context = 128_000), familyTokens = listOf("doubao")),
        // 国内站;思考默认写在回复正文的 <think> 里,原样留在历史中
        ProviderPreset("MiniMax", ProviderConfig.Kind.OpenAiCompat, "https://api.minimax.cn/v1", caps(vision = true, context = 200_000), familyTokens = listOf("minimax")),
        ProviderPreset("阶跃星辰", ProviderConfig.Kind.OpenAiCompat, "https://api.stepfun.com/v1", caps(), familyTokens = listOf("step")),
        // 混元的新模型在 TokenHub 上(旧的 api.hunyuan.cloud.tencent.com 不再上新模型)
        ProviderPreset("腾讯混元", ProviderConfig.Kind.OpenAiCompat, "https://tokenhub.tencentmaas.com/v1", caps(context = 256_000), familyTokens = listOf("hunyuan")),
        // Key 是控制台生成的 bce-v3/… 那一串,照 Bearer 发
        ProviderPreset("百度千帆", ProviderConfig.Kind.OpenAiCompat, "https://qianfan.baidubce.com/v2", caps(), familyTokens = listOf("ernie")),
        ProviderPreset("OpenRouter", ProviderConfig.Kind.OpenAiCompat, "https://openrouter.ai/api/v1", caps()),
        // 订阅制,一个 Key 用多家模型;各模型走哪种协议、哪个地址由模型列表逐个写明,这里的接口类型只是没写明时的退路。
        // 每个请求都要带对话的会话号,不带就拒绝(HTTP 400)
        ProviderPreset("OpenCode Go", ProviderConfig.Kind.OpenAiCompat, "https://opencode.ai/zen/go/v1", caps(), sessionHeader = "x-opencode-session"),
        ProviderPreset("Anthropic", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", caps(vision = true, context = 200_000), familyTokens = listOf("claude")),
        ProviderPreset("Gemini", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com", caps(vision = true, context = 128_000), familyTokens = listOf("gemini")),
        // 官方 API 走 Responses:新的主力模型调用工具必须用 Responses,Chat Completions 带工具会被拒。列模型同样是 GET /models
        ProviderPreset("OpenAI", ProviderConfig.Kind.OpenAiResponses, "https://api.openai.com/v1", caps(vision = true, context = 400_000), familyTokens = listOf("gpt")),
        ProviderPreset("xAI", ProviderConfig.Kind.OpenAiCompat, "https://api.x.ai/v1", caps(vision = true, context = 256_000), familyTokens = listOf("grok")),
        // 列模型接口写明每个模型能不能调用工具、看图和上下文长度
        ProviderPreset("Mistral", ProviderConfig.Kind.OpenAiCompat, "https://api.mistral.ai/v1", caps(vision = true, context = 128_000), familyTokens = listOf("mistral")),
        ProviderPreset("Groq", ProviderConfig.Kind.OpenAiCompat, "https://api.groq.com/openai/v1", caps(context = 128_000)),
        // 自己机器上跑的:地址是本机的默认端口,跑在电脑上时改成电脑的局域网地址;不要 Key
        ProviderPreset("Ollama", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:11434/v1", caps(), selfHosted = true),
        ProviderPreset("LM Studio", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:1234/v1", caps(), selfHosted = true),
        ProviderPreset("自定义", ProviderConfig.Kind.OpenAiCompat, "", caps()),
    )

    val custom: ProviderPreset get() = all.last()

    /** 同主机有多家预设时，按逐段路径相同的长度挑更贴近的一个；根地址并列时保持预设顺序。 */
    private fun pathOverlap(presetUrl: String, inputUrl: String): Int {
        val preset = presetUrl.trimEnd('/').toHttpUrlOrNull()?.pathSegments?.filter { it.isNotBlank() } ?: return 0
        val input = inputUrl.trim().toHttpUrlOrNull()?.pathSegments?.filter { it.isNotBlank() } ?: return 0
        return preset.indices.count { preset[it] == input.getOrNull(it) }
    }
}

package com.wangzan101.mika.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 各家官方申请 API Key 的页面,按 baseUrl 的主机名认:选了预设、或自定义手填了官方地址,都能给出直达链接。
 * 只收官方文档/控制台写明的地址,跟着 [ProviderPresets] 的清单走;认不出的为 null,界面上不显示链接。
 */
object ProviderKeySites {
    private val byHost: Map<String, String> = mapOf(
        "api.deepseek.com" to "https://platform.deepseek.com/api_keys",
        "dashscope.aliyuncs.com" to "https://bailian.console.aliyun.com/#/api-key",
        "api.moonshot.cn" to "https://platform.moonshot.cn/console/api-keys",
        "open.bigmodel.cn" to "https://bigmodel.cn/usercenter/proj-mgmt/apikeys",
        "api.siliconflow.cn" to "https://cloud.siliconflow.cn/account/ak",
        "ark.cn-beijing.volces.com" to "https://ark.volcengine.com/region:cn-beijing/openManagement?advancedActiveKey=model",
        "api.minimax.cn" to "https://platform.minimaxi.com/user-center/basic-information/interface-key",
        "api.stepfun.com" to "https://platform.stepfun.com/interface-key",
        "tokenhub.tencentmaas.com" to "https://console.cloud.tencent.com/hunyuan/api-key",
        "qianfan.baidubce.com" to "https://console.bce.baidu.com/qianfan/ais/console/apiKey",
        "openrouter.ai" to "https://openrouter.ai/settings/keys",
        "api.anthropic.com" to "https://console.anthropic.com/settings/keys",
        "generativelanguage.googleapis.com" to "https://aistudio.google.com/app/apikey",
        "api.openai.com" to "https://platform.openai.com/api-keys",
        "api.x.ai" to "https://console.x.ai/",
        "api.mistral.ai" to "https://console.mistral.ai/api-keys",
        "api.groq.com" to "https://console.groq.com/keys",
        "api.cloudflare.com" to "https://dash.cloudflare.com/profile/api-tokens",
    )

    /** [baseUrl] 的主机名对得上的官方 Key 申请页;认不出(含地址不合法)为 null。 */
    fun forBaseUrl(baseUrl: String): String? = byHost[baseUrl.trim().toHttpUrlOrNull()?.host]
}

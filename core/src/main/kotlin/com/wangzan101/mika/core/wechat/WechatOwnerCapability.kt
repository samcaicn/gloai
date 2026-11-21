package com.wangzan101.mika.core.wechat

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 把任务结果投递到机主微信的出口;绑定校验、去重与真实发送都在 app 一侧,core 只认这个接口。 */
fun interface WechatOwnerPort {
    suspend fun send(text: String): WechatOwnerResult
}

/**
 * 一次推送的结局。[Uncertain] 是「交给渠道了但没拿到确证」,与失败一样如实告诉模型,都不许自动重发;
 * [message] 是给模型看的实话(失败原因、不可用缘由),原样进结果。
 */
data class WechatOwnerResult(val status: Status, val message: String? = null) {
    enum class Status { Delivered, Uncertain, Failed, Unavailable }
}

/** 一种语言的闸门模式:发送动词与机主目标都出现、且动词之前没有否定词,才算明确要发。 */
private class GatePattern(val verb: Regex, val target: Regex, val negation: Regex)

/** 各语言的显式意图模式表;以后加语言在这里添一行。 */
private val GATE_PATTERNS = listOf(
    GatePattern(
        verb = Regex("发送|发到|发给|推送|推给|推到|转发|传给|发"),
        target = Regex("我的?微信"),
        negation = Regex("不|别|无需"),
    ),
    GatePattern(
        verb = Regex("\\b(?:send|deliver|forward|push)\\b", RegexOption.IGNORE_CASE),
        target = Regex("\\bmy wechat\\b", RegexOption.IGNORE_CASE),
        negation = Regex("\\b(?:don't|do not|never)\\b", RegexOption.IGNORE_CASE),
    ),
)

/**
 * 用户开局这句话([goal])是不是明确说了「把结果发到我的微信」。
 * 动词与目标各自整句匹配,不设字数距离窗口(长句照常见于真实请求);否定词只看它是否出现在发送动词之前,
 * 拿不准时不放行——这道闸只决定工具进不进供给,宁缺毋滥。
 */
fun explicitWechatOwnerRequest(goal: String): Boolean = GATE_PATTERNS.any { p ->
    val verb = p.verb.find(goal) ?: return@any false
    if (!p.target.containsMatchIn(goal)) return@any false
    p.negation.find(goal)?.let { it.range.first < verb.range.first } != true
}

/**
 * 机主微信推送能力:用户在任务里明说「发我微信」、app 一侧又有绑定的投递出口时,才给模型 wechat_send_owner。
 * 端口为空时不出工具、不出段落(装配层据此决定装不装,见 TaskTools 里包它的匿名 Provider)。
 */
internal class WechatOwnerCapability(private val port: WechatOwnerPort?) : Capability {
    override val id: String = WECHAT_OWNER

    override fun status(): CapabilityStatus =
        if (port != null) CapabilityStatus.Available else CapabilityStatus.Unavailable("owner push channel not bound")

    override fun tools(): List<AgentTool> = if (port == null) emptyList() else wechatOwnerAgentTools

    override fun sections(env: PromptEnv): List<String> =
        if (port != null && env.isOpen(id) && SEND_OWNER in env.offered) listOf(SECTION) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        port?.let { ctx[KEY] = it }
    }

    companion object {
        const val WECHAT_OWNER = "wechat_owner"
        const val SEND_OWNER = "wechat_send_owner"
        /** 一次推送正文的上限(字)。 */
        const val TEXT_MAX = 4000
        val KEY = CapabilityKey<WechatOwnerPort>(WECHAT_OWNER)

        /** 行为守则:工具被供给时随系统提示词给出。 */
        private const val SECTION = "WeChat owner push: the user explicitly asked for this task's result to be sent to their bound WeChat chat. " +
            "Call wechat_send_owner once, when the task is done, with only the final result and any honest uncertainty. " +
            "A delivered status means the channel accepted the text, not that the owner has read it. " +
            "If delivery fails or is uncertain, report exactly that and never resend on your own. " +
            "Never use the WeChat app or a browser chat as a substitute for this tool."
    }
}

/** 这一能力的全部工具;无状态,端口在任务开局经 [WechatOwnerCapability.KEY] 进工具上下文。 */
internal val wechatOwnerAgentTools: List<AgentTool> = listOf(WechatSendOwner())

private class WechatSendOwner : AgentTool {
    override val capability = WechatOwnerCapability.WECHAT_OWNER
    override val verb = Verb.WechatSendOwner
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val def = ToolDef(
        WechatOwnerCapability.SEND_OWNER,
        Prompts.tool(WechatOwnerCapability.SEND_OWNER),
        mapOf("text" to str("Final result text to deliver to the owner's bound WeChat chat.")),
        listOf("text"),
    )

    /** 能力没进装配(没绑定出口)时工具不在,双重确认。 */
    override fun available(ctx: ToolContext) = ctx[WechatOwnerCapability.KEY] != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx[WechatOwnerCapability.KEY]
            ?: return ToolExecution(buildJsonObject {
                put("ok", false)
                put("error", "wechat_owner_unavailable")
                put("status", WechatOwnerResult.Status.Unavailable.name.lowercase())
            })
        val text = a.str("text", min = 1, max = WechatOwnerCapability.TEXT_MAX)!!
        val result = port.send(text)
        return ToolExecution(buildJsonObject {
            put("ok", result.status == WechatOwnerResult.Status.Delivered)
            put("status", result.status.name.lowercase())
            result.message?.let { put("text", it) }
        })
    }
}

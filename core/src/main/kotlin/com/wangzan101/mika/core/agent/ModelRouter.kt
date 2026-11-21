package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.tool.Verb

/** 决定下一步用哪个模型时看的上一步情况。 */
data class StepContext(
    val step: Int,
    val lastOk: Boolean,
    val lastHadGuard: Boolean,
    /** 上一步之后画面变没变;读屏一类不报时为 null。 */
    val lastChanged: Boolean?,
    /** 回放交还、批量停止、核查退回、接管或追问恢复。 */
    val lastWasRecovery: Boolean,
    val sameAppAsLast: Boolean,
    /** 要发出的上下文里有截图;不看图的轻量模型不能接。 */
    val hasImages: Boolean = false,
)

/**
 * 实验开关 lightSteps 的分工:简单步给轻量模型,其余给主模型。
 * 轻量模型没配、不支持工具调用时恒用主模型。一步出了错误、守卫提示或画面没变,接下来 [TROUBLE_STEPS] 步强制主模型。
 *
 * 轻量侧沿用多级备援(Light Waterfall Chain):当前轻量模型不可用时依次切到下一个,全部用尽才回退主模型。
 * 主模型、轻量模型都可以是模型组([ModelPool]);[pick] 返回的是池本身,池再按成员选,失败换成员。
 */
class ModelRouter(
    private val main: ChatModel,
    private val light: ChatModel?,
    private val fallbacks: List<ChatModel> = emptyList(),
) {
    private val mainPool: ModelPool = main.asPool()
    /** 轻量侧备援链;每一级都可能是单个模型或一个模型组。 */
    private val lightChain: List<ChatModel> = listOfNotNull(light) + fallbacks
    private val lightPools: List<ModelPool> = lightChain.map { it.asPool() }
    private var lightIndex = 0
    private var forcedMain = 0
    private var disabled = false

    /** 当前活跃的轻量模型(可能是组)。 */
    val currentLight: ChatModel? get() = lightChain.getOrNull(lightIndex)
    private val currentPool: ModelPool? get() = lightPools.getOrNull(lightIndex)

    /** 轻量模型能不能接手机步;不能时 lightSteps 不生效。组里只要有一个支持工具的成员就行,具体到每一步再按 need 过滤。 */
    val active: Boolean get() = !disabled && currentPool?.members?.any { it.model.capabilities.toolCalls } == true

    /**
     * 轻量模型出了重试也没用的错(Key、额度、模型名、请求不对):
     * 若有备援轻量模型，切换到下一个备援；否则余下都用主模型。
     */
    fun disable() {
        if (!switchToNextLight()) {
            disabled = true
        }
    }

    /** 尝试切到下一个轻量备援模型，成功返回 true，无更多备援返回 false */
    fun switchToNextLight(): Boolean {
        if (lightIndex + 1 < lightChain.size) {
            lightIndex++
            return true
        }
        disabled = true
        return false
    }

    fun pick(ctx: StepContext): ChatModel {
        if (!ctx.lastOk || ctx.lastHadGuard || ctx.lastChanged == false) noteTrouble()
        if (forcedMain > 0) {
            forcedMain--
            return main
        }
        val l = currentLight?.takeIf { active } ?: return main
        val simple = ctx.step > 1 && !ctx.lastWasRecovery && ctx.sameAppAsLast && (!ctx.hasImages || l.capabilities.vision)
        return if (simple) l else main
    }

    /** 出了状况(含轻量模型调用失败、回复被改派给主模型):之后 [TROUBLE_STEPS] 步用主模型。 */
    fun noteTrouble() { forcedMain = TROUBLE_STEPS }

    /** 这个模型是不是轻量侧(含备援)的成员(不是按对象相等:组里换过成员也要认得出);轻量池本身也算。 */
    fun isLight(m: ChatModel): Boolean = lightChain.any { it === m } || lightPools.any { it.owns(m) }

    /** 这个成员属于哪个模型组;单模型为 null。组本身作为成员传进来时也算。 */
    fun groupOf(m: ChatModel): String? {
        val pool = if (isLight(m)) {
            lightChain.firstOrNull { it === m }?.asPool() ?: lightPools.firstOrNull { it.owns(m) }
        } else {
            mainPool.takeIf { it === m || it.owns(m) }
        }
        return pool?.groupName
    }

    companion object {
        const val TROUBLE_STEPS = 2
        /** 轻量模型给出这些调用(还有 do_steps)时不执行,这一步改问主模型:放弃、确认、接管、追问、沙箱命令都由主模型决定。 */
        private val MAIN_ONLY_VERBS = setOf(Verb.EndNotDone, Verb.RequestConfirmation, Verb.HandToUser, Verb.AskUser, Verb.HandleObstacle,
            Verb.PhoneRun, Verb.Collect, Verb.PhoneFind, Verb.PhoneDialog, Verb.PhoneGesture, Verb.PhoneCommand) +
            // 沙箱里跑的是模型写的命令:只让主模型写
            setOf(Verb.RunCommand, Verb.RunBundledScript, Verb.ReadFile, Verb.WriteFile, Verb.SearchFiles, Verb.ShareFile, Verb.ViewImage, Verb.ListFolderFiles)
        /** 只能由主模型给出的调用([name] 是工具名,[verb] 是它做的动作)。 */
        fun mainOnly(name: String, verb: Verb?): Boolean = name == DO_STEPS_TOOL || verb in MAIN_ONLY_VERBS

        /** 这些动作之后的一步算恢复(等过用户)。 */
        internal val RECOVERY_VERBS = setOf(Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation)
        /** 这些动作会换前台 App 或网页(打开网址、换标签);之后的一步不算同一个地方。 */
        internal val APP_SWITCH_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.Navigate, Verb.Tabs)
    }
}

/** AgentLoop 用:记下每一步的情况,拼出下一步的 [StepContext]。 */
internal class StepTrack {
    private var ok = true
    private var guard = false
    private var changed: Boolean? = null
    private var recovery = false
    private var app: String? = null
    private var appBefore: String? = null
    private var switched = false

    fun context(step: Int, hasImages: Boolean) =
        StepContext(step, ok, guard, changed, recovery, !switched && app != null && app == appBefore, hasImages)

    /** [app] 为 null 表示这一步的结果没报前台 App,沿用之前的。 */
    /** 刚从回放交还、批量停止这类状况里恢复:下一步按恢复步算。在 [after] 之后调用。 */
    fun markRecovery() { recovery = true }

    fun after(verb: Verb?, ok: Boolean, guard: Boolean, changed: Boolean?, recovery: Boolean, app: String?) {
        this.ok = ok
        this.guard = guard
        this.changed = changed
        this.recovery = recovery || verb in ModelRouter.RECOVERY_VERBS
        appBefore = this.app
        if (app != null) this.app = app
        switched = verb in ModelRouter.APP_SWITCH_VERBS
    }
}

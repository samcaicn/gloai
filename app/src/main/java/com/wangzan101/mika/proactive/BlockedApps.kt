package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.device.access.AppAccessState
import com.wangzan101.mika.core.proactive.NoticeDecision
import com.wangzan101.mika.core.proactive.NoticeFilter
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.NoticeRecord

/**
 * 「应用访问」那份设置此刻的样子:[known] 是读不读得出来,读得出来时 [blocked] 是禁用了的包名。
 * 读不出来不等于一个都没禁用:那时不知道哪些 App 不让碰,主动能力整个停着(不读、不发、不带进任务),攒下的不动。
 */
data class AppGate(val known: Boolean, val blocked: Set<String>) {
    companion object {
        /** 从「应用访问」存着的那份来:连读不读得出来一起带上(只带禁用的那份会把读不出来当成一个都没禁用)。 */
        fun of(state: AppAccessState) = AppGate(state.available, state.blocked)
    }
}

/** 「应用访问」里禁用的 App 在主动能力里的样子:不归纳;禁用之后,已经收下的一并去掉。都在 ProactiveRuntime 那条串行协程上调。 */
internal object BlockedApps {
    /** 这个 App 的通知现在能不能读(包名这一关,在读内容之前):「应用访问」读不出来时一个都不读。 */
    fun readable(pkg: String, allowed: Set<String>, gate: AppGate): Boolean =
        gate.known && NoticeFilter.decidePackage(pkg, allowed, gate.blocked) == NoticeDecision.Keep

    /** 这条通知现在收不收(清洗、拆好之后再过一遍);「应用访问」读不出来时一条都不收。 */
    fun admits(r: NoticeRecord, allowed: Set<String>, gate: AppGate): Boolean =
        gate.known && NoticeFilter.decide(r, allowed, gate.blocked) == NoticeDecision.Keep

    /**
     * 这个 App 现在能不能归纳:勾选了、不是「不从它归纳」、没在「应用访问」里禁用、不是 Mika 自己。
     * 「应用访问」读不出来不在这里管([blocked] 那时是空的):归纳那边拿这个去清待归纳的事件,读不出来时只该停着,不该清。
     */
    fun learnEligible(pkg: String, allowed: Set<String>, optOut: Set<String>, blocked: Set<String>): Boolean =
        pkg in allowed && pkg !in optOut && pkg !in blocked && !NoticeFilter.isSelf(pkg)

    /**
     * 禁用的 App 变了([blocked] 是现在的整份):它们的记录从收件箱里去掉(判定结果、提醒跟着收件箱走),
     * 正在发的判定请求是它们的就取消,待归纳的事件去掉、正在发的归纳请求取消(归纳那边按 [learnEligible] 认),
     * 它们存着的通知原文和归纳出的句子也去掉。
     */
    fun purge(blocked: Set<String>, inbox: NoticeInbox, triage: TriageRunner, learn: LearnRunner) {
        inbox.removePackages { it !in blocked }
        triage.dropApps { it !in blocked }
        learn.forgetApps(blocked)
    }

    /**
     * 「应用访问」变了([gate] 是现在的):读得出来就按禁用的那份清一遍([purge]);读不出来什么都不删(不知道禁用了哪些)。
     * 两种情况都让判定和归纳重新看一遍:读不出来时它们停下在发的请求、停着等,读得出来了接着做——先清后看,禁用的不会又发出去。
     */
    fun accessChanged(gate: AppGate, inbox: NoticeInbox, triage: TriageRunner, learn: LearnRunner) {
        if (gate.known) purge(gate.blocked, inbox, triage, learn) else learn.poke()
        triage.poke()
    }
}

package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural

/** 对话页上按状态算出来的文字;不碰界面,单测直接测。 */
object ChatCopy {
    /** 空对话的问候,按时段。 */
    fun greeting(hour: Int): String = when (hour) {
        in 5..10 -> tr(R.string.chat_greeting_morning)
        in 11..12 -> tr(R.string.chat_greeting_noon)
        in 13..17 -> tr(R.string.chat_greeting_afternoon)
        in 18..22 -> tr(R.string.chat_greeting_evening)
        else -> tr(R.string.chat_greeting_night)
    }

    val EMPTY_PROMPT get() = tr(R.string.chat_empty_prompt)
    /** 空对话里的一句价值主张:说明 Mika 能干嘛。 */
    val EMPTY_VALUE get() = tr(R.string.chat_empty_value)

    /** 内置示例的类型,只决定首页卡片上的图标和色调。 */
    enum class ExampleKind { Web, Follow, GroupChat, Alarm, Schedule }
    data class Example(val kind: ExampleKind, val title: String, val prompt: String)

    /**
     * 内置推荐任务的原文。只在本机还没有保存过推荐任务时用来初始化一次;
     * 标题和提示词都用这一句,之后用户在设置里改,不会因为换语言或升级被加回来。
     */
    val examples get() = listOf(
        Example(ExampleKind.Web, tr(R.string.chat_example_github_star_title), tr(R.string.chat_example_github_star)),
        Example(ExampleKind.Follow, tr(R.string.chat_example_follow_title), tr(R.string.chat_example_follow)),
        Example(ExampleKind.GroupChat, tr(R.string.chat_example_group_summary_title), tr(R.string.chat_example_group_summary)),
        Example(ExampleKind.Alarm, tr(R.string.chat_example_alarms_title), tr(R.string.chat_example_alarms)),
        Example(ExampleKind.Schedule, tr(R.string.chat_example_schedule_title), tr(R.string.chat_example_schedule)),
    )

    fun recommendationSeeds(): List<Pair<String, String>> = examples.map { it.title to it.prompt }

    /**
     * 一条推荐任务属于哪种类型:提示词和内置示例一字不差就是内置的那条(标题改过也认得出);
     * 自己加的任务没有类型,返回 null,界面上用通用图标。
     */
    fun kindOf(prompt: String): ExampleKind? = examples.firstOrNull { it.prompt == prompt }?.kind

    /**
     * 曾经内置、现在已经去掉的示例。升级时把用户还没动过的那一条从推荐列表里清掉;
     * 标题或提示词改过的留着——那已经是用户自己的任务了。
     */
    fun retiredRecommendationSeeds(): List<Pair<String, String>> = listOf(
        tr(R.string.chat_example_pdf_title) to tr(R.string.chat_example_pdf),
    )

    /** 对话页顶栏的标题:固定是 App 名;副标题说此刻在做什么。 */
    const val APP_TITLE = "Mika"

    /** 对话的名字:第一句话(只取第一行,附件那几行不算;只发了屏幕时是「你此刻的屏幕」);空对话为空。侧栏里用它称呼对话。 */
    fun title(exchanges: List<Exchange>): String =
        exchanges.firstOrNull { it.goal.isNotBlank() }?.goal?.let(::plainTitle).orEmpty()

    /**
     * 一句用户的话当标题怎么写:去掉网页说明([AttachedPage])、引用([QuoteMessage])、附件那几行(只发了附件时是「照片」「合同.pdf」之类)和屏幕那一行(只发了屏幕时是「你此刻的屏幕」),
     * 只取第一行。
     */
    fun plainTitle(text: String): String =
        ScreenCopy.plain(AttachmentMessage.plain(QuoteMessage.words(AttachedPage.words(text)))).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /**
     * 分支的标题:源标题(或第一句话)后面加本地化后缀,总长不超过 [max] 字。
     * 截的是源标题,后缀留着;按码点截,不会把 emoji 截成半个。
     */
    fun branchTitle(base: String, suffix: String, max: Int = 24): String {
        val room = (max - suffix.length).coerceAtLeast(0)
        var end = 0
        var taken = 0
        while (end < base.length && taken < room) {
            end += Character.charCount(base.codePointAt(end))
            taken++
        }
        return base.substring(0, end).trimEnd() + suffix
    }

    enum class Tone { Accent, Warn, Ok }

    /** 顶栏副标题:这个对话留着一页在后台运行(没有任务在跑时)。 */
    val KEPT_PAGE: String get() = tr(R.string.kept_chat_sub)

    /** 顶栏副标题:正在做什么(强调色)或等你(橙色);没有任务在跑时为 null。[place] 是正在操作的 App 或网站。 */
    fun subtitle(ex: Exchange?, place: String?): Pair<String, Tone>? = when (ex?.status) {
        null -> null
        ExchangeStatus.Working -> (if (place.isNullOrBlank()) tr(R.string.chat_working) else tr(R.string.chat_working_on, place)) to Tone.Accent
        ExchangeStatus.WaitingUnlock -> tr(R.string.chat_wait_unlock) to Tone.Warn
        is ExchangeStatus.Queued -> tr(R.string.chat_queued) to Tone.Accent
        is ExchangeStatus.NeedsTakeover, is ExchangeStatus.NeedsInput,
        is ExchangeStatus.NeedsConfirmation, is ExchangeStatus.Paused -> tr(R.string.chat_waiting_for_you) to Tone.Warn
        else -> null
    }

    /** 进度那一行:在跑时是此刻的进展,停下来等你时是「已暂停」。 */
    fun progressLine(ex: Exchange): String =
        if (paused(ex.status)) tr(R.string.chat_paused) else liveLine(ex) ?: tr(R.string.chat_working)

    /** 任务停下来等你(要你处理、回答、同意、解锁、点继续)。 */
    fun paused(status: ExchangeStatus): Boolean =
        status is ExchangeStatus.NeedsTakeover || status is ExchangeStatus.NeedsInput ||
            status is ExchangeStatus.NeedsConfirmation || status is ExchangeStatus.Paused || status == ExchangeStatus.WaitingUnlock

    /** 进度行右边的用时:「0:24」「12:05」「1:02:03」。 */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** 在跑时步骤只列最近这几步,前面的收成一行「前面还有 n 步」,点开看全部。 */
    const val VISIBLE_STEPS = 5

    fun earlierSteps(n: Int) = trPlural(R.plurals.chat_earlier_steps, n)

    /** 在后台操作手机时步骤下面那行小字。 */
    val BACKGROUND_NOTE get() = tr(R.string.chat_background_note)

    /**
     * 一个动作的结果(记录里的 effect 代号 → 给人看的一句话)。错误码只对开发有意义,
     * 给人看的一律说「没成功」;按规矩没收的那几种除外([declinedLabel])。运行过程页用的是同一份说法。
     */
    fun effectLabel(effect: String?): String = when (effect) {
        "changed", "done", "place_changed" -> tr(R.string.result_effective)
        "small_change" -> tr(R.string.effect_small_change)
        "unchanged", "no_effect" -> tr(R.string.result_unchanged)
        "completed" -> tr(R.string.result_completed)
        null, "" -> ""
        else -> declinedLabel(effect) ?: tr(R.string.step_failed)
    }

    /**
     * 写记忆、存技能、改资料时按规矩没收(不是用户亲口说的、敏感、没明确要改删):这不是出了故障,
     * 写「没成功」会让人以为坏了。说清没收和原因;别的错误码返回 null。
     */
    fun declinedLabel(code: String?): String? = when (code) {
        "quote_not_from_user", "content_not_from_user" -> tr(R.string.step_declined_not_said)
        "inference_not_requested" -> tr(R.string.step_declined_not_requested)
        "sensitive" -> tr(R.string.step_declined_sensitive)
        "edit_not_requested" -> tr(R.string.step_declined_not_asked)
        else -> null
    }

    /** 点开一步后每件事一行:做了什么 · 对什么 · 结果(结果为空时不写)。 */
    fun actionLine(a: StepAction): String =
        listOfNotNull(a.label, a.target?.takeIf(String::isNotBlank), effectLabel(a.effect).takeIf(String::isNotBlank)).joinToString(" · ")

    fun placeholder(mode: ComposerMode, hasHistory: Boolean): String = when (mode) {
        ComposerMode.Busy -> tr(R.string.chat_placeholder_busy)
        ComposerMode.Answer -> tr(R.string.chat_placeholder_answer)
        ComposerMode.Idle -> if (hasHistory) tr(R.string.chat_placeholder_more) else EMPTY_PROMPT
    }

    /** 能按住说话时的居中灰字:告诉人能按住说、点一下打字;任务在跑时说的就是补充消息。 */
    fun voicePlaceholder(mode: ComposerMode): String = when (mode) {
        ComposerMode.Busy -> tr(R.string.voice_placeholder_busy)
        ComposerMode.Answer -> tr(R.string.voice_placeholder_answer)
        ComposerMode.Idle -> tr(R.string.voice_placeholder_idle)
    }
}

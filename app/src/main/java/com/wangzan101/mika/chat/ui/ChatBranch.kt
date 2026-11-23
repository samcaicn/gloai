package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.screen.user.UserScreenMessage

/**
 * 第 [turn] 轮(0 起)此刻能不能作为分支点:轮次在范围内、这一轮已经结束、对话没有被挡住([blocked]:有任务在跑或排队、
 * 正在删、输入框正忙等,由调用方算好)。菜单显不显示「从这里分支」和点了之后做不做,都只看它。
 */
internal fun canBranchAt(exchanges: List<Exchange>, turn: Int, blocked: Boolean): Boolean =
    !blocked && turn in exchanges.indices && exchanges[turn].status.branchable

/** 第 [turn] 轮此刻能不能「从这里重新问」:能分支([canBranchAt]),而且这一轮用户说的话不是空的。菜单和动作都只看它。 */
internal fun canRetryAt(exchanges: List<Exchange>, turn: Int, blocked: Boolean): Boolean =
    canBranchAt(exchanges, turn, blocked) && exchanges[turn].goal.isNotBlank()

/** 消息键 `<prefix><轮次>` 里的轮次;不是这一类消息时为 null。 */
private fun turnOf(key: String, prefix: String): Int? =
    if (key.startsWith(prefix)) key.removePrefix(prefix).toIntOrNull() else null

/**
 * 长按的这条消息能不能作为分支点:只有 Mika 的回答(`r<轮次>`)且 [canBranchAt] 成立时返回轮次,否则 null。
 * 用户的话、插话、状态卡、过程行都不算。
 */
internal fun branchableTurn(exchanges: List<Exchange>, key: String, blocked: Boolean): Int? =
    turnOf(key, "r")?.takeIf { canBranchAt(exchanges, it, blocked) }

/** 这一轮已经结束(成功、失败、被停止都算);在跑、等你处理、排队的不算。 */
internal val ExchangeStatus.branchable: Boolean
    get() = when (this) {
        is ExchangeStatus.Done, is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> true
        else -> false
    }

/** 「从这里重新问」的结果:新对话编号、预填的文字、原消息是否带了要重新添加的附件/屏幕。 */
data class BranchRetry(
    val conversationId: String,
    val prefill: String,
    val needsReattach: Boolean,
    val failures: List<com.wangzan101.mika.chat.RetryAttachmentResult> = emptyList(),
    val screenNeedsReattach: Boolean = false,
)

/**
 * 长按的是哪条用户自己的话能「从这里重新问」:`u<轮次>` 且 [canRetryAt] 成立时返回轮次,否则 null。
 * 插话(`m…`)、回答、状态卡都不算。
 */
internal fun retryableTurn(exchanges: List<Exchange>, key: String, blocked: Boolean): Int? =
    turnOf(key, "u")?.takeIf { canRetryAt(exchanges, it, blocked) }

/** 原来那句话里能重新问的文字:去掉网页说明、引用、附件行和屏幕行;只发了附件/屏幕时为空。 */
internal fun retryText(raw: String): String =
    UserScreenMessage.parse(AttachmentMessage.parse(QuoteMessage.words(AttachedPage.words(raw))).words).words.trim()

/** 原话带了附件或屏幕:重新问时不会自动带上,界面据此提示重新添加。 */
internal fun needsReattach(raw: String): Boolean {
    val attached = AttachmentMessage.parse(QuoteMessage.words(AttachedPage.words(raw)))
    return attached.hasAttachments || UserScreenMessage.parse(attached.words).hasScreen
}

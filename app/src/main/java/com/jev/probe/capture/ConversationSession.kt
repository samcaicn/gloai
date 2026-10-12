package com.jev.probe.capture

/** Main-thread session state. A return to the same chat never revives an old request. */
internal class ConversationSession {
    data class Target(
        val pkg: String,
        val windowId: Int,
        val title: String?,
        val messagesSignature: String? = null
    )
    data class Token(val target: Target, val revision: Long)

    var target: Target? = null
        private set
    private var revision = 0L

    fun observe(next: Target?): Boolean {
        if (target == next) return false
        target = next
        invalidate()
        return true
    }

    fun invalidate() { revision++ }

    fun token(): Token? = target?.let { Token(it, revision) }

    fun begin(): Token? {
        invalidate()
        return token()
    }

    /**
     * 后台纯通知场景专用：微信在后台、无障碍没抓到聊天窗口，[target] 可能为 null，
     * [token] 会因此返回 null 而把通知直驱分析整个吞掉。这里用通知里的会话名构造一个
     * 逻辑 target（windowId = [NOTIF_WINDOW] 作标记），[isCurrent] 见到该标记就跳过
     * 「前台窗口必须匹配」的检查——文本来自通知，本就不依赖实时窗口。
     */
    fun beginNotification(pkg: String, title: String?): Token {
        val t = Target(pkg, NOTIF_WINDOW, title, null)
        target = t
        revision++
        return Token(t, revision)
    }

    fun accepts(token: Token): Boolean {
        // 后台通知直驱 token（windowId=NOTIF_WINDOW）：autoOpenChat 会把微信拉到前台，
        // 无障碍随即使 observe() 疯狂换 target/涨 revision——按常规 revision 比对，
        // 通知轮次会被这些前台噪声判死刑（runAnalysis 在 context 打完后就静默 bail，
        // 即「通知到了、分析起了、打完上下文就断」）。通知文本不依赖实时窗口：
        // 这类 token 一旦签发，本轮内始终有效。
        if (token.target.windowId == NOTIF_WINDOW) return true
        return token.target == target && token.revision == revision
    }

    companion object {
        /** 后台通知驱动 target 的 windowId 标记，见 [beginNotification]。 */
        const val NOTIF_WINDOW = -1
    }
}

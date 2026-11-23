package com.wangzan101.mika.core.cap.phone.sms

import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot

/** Only status crosses the host boundary. SMS text, sender, account and code stay on device. */
interface SmsLoginPort {
    suspend fun login(packageName: String): SmsLoginStatus
}

enum class SmsLoginStatus {
    LOGGED_IN, LOGIN_FAILED, LOGIN_UNVERIFIED, NO_TRUSTED_PROFILE, DISABLED, PERMISSION_REQUIRED, BUSY, UNAVAILABLE, TARGET_UNVERIFIED,
    REVOKED, NOT_FILLED,
}

/** Matching scope. Only an explicitly configured local profile may persist it, never the SMS/code. */
class SmsLoginScope(val sender: String, val signature: String, val accountMarker: String) {
    val valid: Boolean get() = sender.matches(Regex("[+A-Za-z0-9]{3,24}")) &&
        signature.length in 2..30 && signature.none { it in "【】[]\r\n" } &&
        accountMarker.matches(Regex("[0-9]{4,11}"))

    fun accountMatches(text: String): Boolean {
        val escaped = Regex.escape(accountMarker)
        return Regex("(?<![0-9])$escaped(?![0-9])").containsMatchIn(text)
    }
}

/** Intentionally narrow: Chinese login messages with an exact sender, signature AND account marker. */
object SmsLoginMatcher {
    private val forbidden = Regex("支付|付款|转账|交易|扣款|提现|银行|改密|重置|找回|注册|绑定|解绑|注销|payment|transfer|reset|register", RegexOption.IGNORE_CASE)
    private val codeAfter = Regex("(?:验证码|校验码)[：:是为\\s]*([0-9]{4,8})(?![0-9])")
    private val codeBefore = Regex("(?<![0-9])([0-9]{4,8})[，,。\\s]*(?:是|为)?(?:您的)?(?:登录)?验证码")
    private val otpField = Regex("验证码|校验码|verification.?code|otp", RegexOption.IGNORE_CASE)

    fun code(scope: SmsLoginScope, sender: String, body: String): CharArray? {
        if (!scope.valid || sender != scope.sender || body.length !in 1..2000 || forbidden.containsMatchIn(body)) return null
        if (!(body.startsWith("【${scope.signature}】") || body.startsWith("[${scope.signature}]"))) return null
        if (!body.contains("登录") || !scope.accountMatches(body)) return null
        val candidates = (codeAfter.findAll(body).map { it.groupValues[1] } +
            codeBefore.findAll(body).map { it.groupValues[1] }).toList()
        if (candidates.size != 1 || candidates.single() == scope.accountMarker) return null
        // A second code-sized number without another label is ambiguous too.
        val numbers = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])").findAll(body)
            .map { it.value }.filter { it != scope.accountMarker }.toList()
        if (numbers != candidates) return null
        return candidates.single().toCharArray()
    }

    /** No coordinates, focused-field fallback, split editors, WebViews or package ambiguity. */
    fun target(screen: ScreenSnapshot, pkg: String, resourceId: String, scope: SmsLoginScope? = null): Boolean {
        if (screen.packageName != pkg || screen.protected || screen.changedDuringRead || !screen.hasWindow) return false
        if (!resourceId.startsWith("$pkg:id/")) return false
        if (screen.windows.any { it.type == "application" && it.packageName != pkg }) return false
        val fields = screen.elements.filter { it.resourceId == resourceId }
        val field = fields.singleOrNull() ?: return false
        if (!field.editable || field.secret || "disabled" in field.states || field.role.contains("WebView", true)) return false
        if (!otpField.containsMatchIn(listOfNotNull(field.name, field.placeholder, resourceId.substringAfter(":id/")).joinToString(" "))) return false
        val text = screen.elements.joinToString(" ") { it.name }
        return scope == null || scope.valid && scope.accountMatches(text) && text.contains("登录")
    }
}

/** One new-message window, consumed at most once. Rejections do not consume the authorization. */
class SmsLoginWindow(private val scope: SmsLoginScope, private val openedAtMs: Long) : AutoCloseable {
    private var closed = false
    @Synchronized fun accept(sender: String, body: String, sentAtMs: Long, receivedAtMs: Long): CharArray? {
        if (closed || receivedAtMs < openedAtMs || receivedAtMs - openedAtMs > 90_000 ||
            sentAtMs < openedAtMs / 1000 * 1000 || sentAtMs > receivedAtMs + 1000) return null
        val code = SmsLoginMatcher.code(scope, sender, body) ?: return null
        closed = true
        return code
    }
    @Synchronized override fun close() { closed = true }
}

package com.wangzan101.mika.core.cap.phone.sms

import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** Explicit, device-local authorization; never a model argument or tool result. One account per app. */
@Serializable
class SmsLoginProfile(
    val app: String, val signer: String, val sender: String, val signature: String,
    val account: String, val accountMarker: String,
    val accountField: String, val codeField: String, val sendButton: String, val loginButton: String,
    val successView: String, val successText: String, val successAccountView: String,
) {
    val scope get() = SmsLoginScope(sender, signature, accountMarker)
    val valid: Boolean get() = app.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")) &&
        signer.matches(Regex("[a-f0-9]{64}")) && scope.valid && account.matches(Regex("[0-9]{4,11}")) &&
        account.endsWith(accountMarker) && successText.length in 2..80 &&
        listOf(accountField, codeField, sendButton, loginButton, successView, successAccountView).let { ids ->
            ids.distinct().size == ids.size && ids.all { it.startsWith("$app:id/") && it.length > app.length + 4 }
        }
}

enum class SmsLoginOutcome { Verified, Rejected, Pending, Unsafe }

/** All methods stay on device. Implementations check the same live authorization at each dispatch. */
interface SmsLoginDevice {
    suspend fun prepare(): Boolean
    suspend fun receive(request: suspend () -> Boolean): CharArray?
    suspend fun request(): Boolean
    suspend fun fill(code: CharArray): Boolean
    suspend fun submit(code: CharArray): Boolean
    suspend fun outcome(code: CharArray): SmsLoginOutcome
    fun resumeVerified(code: CharArray)
}

/** A single request, a single fill, at most one final login click, then affirmative account verification. */
class SmsLoginAutomation(private val device: SmsLoginDevice, private val authorized: () -> Boolean) {
    suspend fun run(): SmsLoginStatus {
        suspend fun allowed(): Boolean { currentCoroutineContext().ensureActive(); return authorized() }
        if (!allowed()) return SmsLoginStatus.REVOKED
        if (!device.prepare()) return SmsLoginStatus.TARGET_UNVERIFIED
        val code = device.receive { allowed() && device.request() } ?: return if (authorized()) SmsLoginStatus.NOT_FILLED else SmsLoginStatus.REVOKED
        try {
            if (!allowed()) return SmsLoginStatus.REVOKED
            if (!device.fill(code)) return SmsLoginStatus.NOT_FILLED
            var outcome = device.outcome(code)
            if (!allowed()) return SmsLoginStatus.REVOKED
            // Some apps submit automatically on input. Never click another control after that transition.
            if (outcome == SmsLoginOutcome.Pending) device.submit(code)
            var consecutive = 0
            repeat(40) {
                if (!allowed()) return SmsLoginStatus.REVOKED
                outcome = device.outcome(code)
                when (outcome) {
                    SmsLoginOutcome.Unsafe -> return SmsLoginStatus.LOGIN_UNVERIFIED
                    SmsLoginOutcome.Rejected -> return SmsLoginStatus.LOGIN_FAILED
                    SmsLoginOutcome.Pending -> consecutive = 0
                    SmsLoginOutcome.Verified -> {
                        consecutive++
                        if (consecutive >= 2) {
                            if (!allowed()) return SmsLoginStatus.REVOKED
                            device.resumeVerified(code)
                            return SmsLoginStatus.LOGGED_IN
                        }
                    }
                }
                delay(500)
            }
            return SmsLoginStatus.LOGIN_UNVERIFIED
        } finally { code.fill('\u0000') }
    }
}

/** Registered landing-page evidence, with exact account identity and no remaining login/code controls. */
object SmsLoginEvidence {
    fun outcome(profile: SmsLoginProfile, screen: ScreenSnapshot, code: CharArray): SmsLoginOutcome {
        if (!profile.valid || screen.packageName != profile.app || screen.protected || screen.changedDuringRead || !screen.hasWindow ||
            screen.windows.any { it.type == "application" && it.packageName != profile.app }) return SmsLoginOutcome.Unsafe
        val text = screen.elements.joinToString(" ") { it.name + " " + it.placeholder.orEmpty() } + screen.windows.toString()
        if (Regex("验证码错误|验证码已过期|验证码失效|登录失败|incorrect code|invalid code|expired code", RegexOption.IGNORE_CASE).containsMatchIn(text)) return SmsLoginOutcome.Rejected
        if (Regex("支付|付款|转账|改密|重置密码|找回密码|身份核验|安全验证|风险验证|payment|password reset", RegexOption.IGNORE_CASE).containsMatchIn(text)) return SmsLoginOutcome.Unsafe
        if (screen.elements.any { it.resourceId == profile.codeField || it.resourceId == profile.loginButton || it.secret } || containsCode(text, code)) return SmsLoginOutcome.Pending
        val success = screen.elements.filter { it.resourceId == profile.successView }.singleOrNull()
        val account = screen.elements.filter { it.resourceId == profile.successAccountView }.singleOrNull()
        return if (success?.name == profile.successText && account != null && profile.scope.accountMatches(account.name)) SmsLoginOutcome.Verified else SmsLoginOutcome.Pending
    }
    fun containsCode(text: String, code: CharArray): Boolean = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).filter(Char::isDigit).contains(String(code))
}

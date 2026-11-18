package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 短信登录:在已核验的登录页上填账号、请求/填写验证码、提交并核对结果。
 * 共享 [AndroidScreen] 的字段与方法经 [screen] 后向引用访问。
 */
internal class SmsLogin(private val screen: AndroidScreen) {

    /** Prepare the explicitly configured account locally; never overwrite a different visible account. */
    suspend fun prepareSmsLogin(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile,
        authorize: (() -> Boolean) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!smsPrimaryUser()) return@withContext false
        val s = screen.smsSnapshot(register = true)
        if (!com.wangzan101.mika.core.cap.phone.sms.SmsLoginMatcher.target(s, p.app, p.codeField)) return@withContext false
        val field = s.elements.filter { it.resourceId == p.accountField }.singleOrNull() ?: return@withContext false
        val found = screen.findField(field.ref, s.snapshotId, tapToFocus = false) as? AndroidScreen.Field.Found ?: return@withContext false
        val node = found.node
        if (found.relocatedTo != null || screen.inWebView(node) || node.isPassword || node.packageName?.toString() != p.app ||
            node.viewIdResourceName != p.accountField || !node.refresh() || !node.isEnabled) return@withContext false
        val rawAccount = if (field.hint) "" else node.text?.toString().orEmpty().trim()
        val current = rawAccount.filter(Char::isDigit)
        if (rawAccount.isNotEmpty() && current != p.account) return@withContext false
        if (current == p.account) true else authorize { screen.setText(node, p.account) }
    }

    suspend fun submitSmsLogin(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray,
        authorize: (() -> Boolean) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val scope = com.wangzan101.mika.core.cap.phone.sms.SmsLoginScope(p.sender, p.signature, p.account)
        val s = smsLoginScreen(p.app, p.codeField, scope) ?: return@withContext false
        val account = s.elements.filter { it.resourceId == p.accountField }.singleOrNull() ?: return@withContext false
        if (account.name.filter(Char::isDigit) != p.account) return@withContext false
        val editor = s.elements.single { it.resourceId == p.codeField }
        if (editor.name.filter(Char::isDigit) != String(code)) return@withContext false
        val button = s.elements.filter { it.resourceId == p.loginButton }.singleOrNull() ?: return@withContext false
        if (!Regex("^(?:登录|登 录|登入|sign in|log in)$", RegexOption.IGNORE_CASE).matches(button.name.trim())) return@withContext false
        val (node, relocated) = screen.resolve(button.ref, s.snapshotId) ?: return@withContext false
        if (relocated != null || screen.inWebView(node) || node.packageName?.toString() != p.app || node.viewIdResourceName != p.loginButton) return@withContext false
        authorize { node.refresh() && node.isEnabled && node.isVisibleToUser && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    suspend fun smsLoginOutcome(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray): com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome = withContext(Dispatchers.IO) {
        if (!smsPrimaryUser()) return@withContext com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome.Unsafe
        val s = screen.smsSnapshot(register = false)
        val outcome = com.wangzan101.mika.core.cap.phone.sms.SmsLoginEvidence.outcome(p, s, code)
        if (outcome != com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome.Verified) return@withContext outcome
        // OCR stays on device. Missing OCR or residual code pixels cannot authorize resuming observations.
        val lines = kotlinx.coroutines.withTimeoutOrNull(2000) { screen.readVisibleText() }
            ?: return@withContext com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome.Pending
        if (com.wangzan101.mika.core.cap.phone.sms.SmsLoginEvidence.containsCode(lines.joinToString(" ") { it.text }, code))
            com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome.Pending
        else com.wangzan101.mika.core.cap.phone.sms.SmsLoginEvidence.outcome(p, screen.smsSnapshot(register = false), code)
    }

    internal fun smsPrimaryUser(): Boolean {
        if (!screen.hasDisplay) return false
        val top = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        return top >= 0 && screen.userOf(top) == 0
    }

    /** SMS-only native input path. Never use the generic clipboard/input fallback or echo values. */
    suspend fun fillSmsLoginCode(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray,
        authorize: (() -> Boolean) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val pkg = p.app; val resourceId = p.codeField
        val current = profileLoginScreen(p) ?: return@withContext false
        val field = current.elements.single { it.resourceId == resourceId }
        val found = screen.findField(field.ref, current.snapshotId, tapToFocus = false) as? AndroidScreen.Field.Found ?: return@withContext false
        val node = found.node
        if (found.relocatedTo != null || screen.inWebView(node) || node.isPassword ||
            node.packageName?.toString() != pkg || node.viewIdResourceName != resourceId) return@withContext false
        authorize {
            // No focus/tap, no retry and no clipboard: SET_TEXT accepts only this exact native node.
            if (!node.refresh() || !screen.visibleInputTarget(node) || !node.isEnabled) false
            else screen.setText(node, String(code))
        }
    }

    /** Listen first, then request one code on the verified login page. */
    suspend fun requestSmsLoginCode(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile,
        authorize: (() -> Boolean) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val pkg = p.app; val sendButtonId = p.sendButton
        val current = profileLoginScreen(p) ?: return@withContext false
        if (!sendButtonId.startsWith("$pkg:id/")) return@withContext false
        val button = current.elements.filter { it.resourceId == sendButtonId }.singleOrNull() ?: return@withContext false
        if (!button.clickable || button.editable || !Regex("获取验证码|发送验证码|重新获取|重新发送|send.?code|resend", RegexOption.IGNORE_CASE)
                .containsMatchIn(button.name)) return@withContext false
        val (node, relocated) = screen.resolve(button.ref, current.snapshotId) ?: return@withContext false
        if (relocated != null || screen.inWebView(node) || node.packageName?.toString() != pkg || node.viewIdResourceName != sendButtonId) return@withContext false
        authorize {
            node.refresh() && node.isEnabled && node.isVisibleToUser && node.isClickable &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    internal suspend fun profileLoginScreen(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile): ScreenSnapshot? {
        val scope = com.wangzan101.mika.core.cap.phone.sms.SmsLoginScope(p.sender, p.signature, p.account)
        val s = smsLoginScreen(p.app, p.codeField, scope) ?: return null
        val account = s.elements.filter { it.resourceId == p.accountField }.singleOrNull() ?: return null
        return s.takeIf { !account.hint && account.name.filter(Char::isDigit) == p.account }
    }

    internal suspend fun smsLoginScreen(pkg: String, resourceId: String,
        scope: com.wangzan101.mika.core.cap.phone.sms.SmsLoginScope): ScreenSnapshot? {
        if (!screen.hasDisplay) return null
        // Do not guess which installation/account owns a copied or work-profile app.
        val top = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        if (top < 0 || screen.userOf(top) != 0) return null
        screen.appAccess.current().denial(pkg)?.let { return null }
        val current = screen.smsSnapshot(register = true)
        return current.takeIf { com.wangzan101.mika.core.cap.phone.sms.SmsLoginMatcher.target(it, pkg, resourceId, scope) }
    }
}

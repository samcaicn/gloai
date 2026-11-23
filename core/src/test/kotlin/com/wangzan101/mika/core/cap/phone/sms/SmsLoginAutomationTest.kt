package com.wangzan101.mika.core.cap.phone.sms

import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.screen.uistate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class SmsLoginAutomationTest {
    private val app = "com.example.login"
    private fun profile() = SmsLoginProfile(app, "a".repeat(64), "10690001", "示例服务", "00000007788", "7788",
        "$app:id/account", "$app:id/otp", "$app:id/send", "$app:id/login", "$app:id/logged", "欢迎回来", "$app:id/who")
    private class Device : SmsLoginDevice {
        val events = mutableListOf<String>()
        val secret = "246810".toCharArray()
        var allowed = true
        var received = true
        var prepared = true
        var afterFill: () -> Unit = {}
        var submitBlock: (suspend () -> Unit)? = null
        var outcome = SmsLoginOutcome.Pending
        var afterSubmit = SmsLoginOutcome.Verified
        var resumed = false
        override suspend fun prepare(): Boolean { events += "account"; return prepared }
        override suspend fun receive(request: suspend () -> Boolean): CharArray? {
            events += "listen"; if (!request() || !received) return null
            events += "receive"; return secret
        }
        override suspend fun request(): Boolean { events += "request"; return true }
        override suspend fun fill(code: CharArray): Boolean { events += "fill"; afterFill(); return true }
        override suspend fun submit(code: CharArray): Boolean { events += "submit"; submitBlock?.invoke(); outcome = afterSubmit; return true }
        override suspend fun outcome(code: CharArray): SmsLoginOutcome = outcome
        override fun resumeVerified(code: CharArray) { resumed = true; events += "verified" }
    }
    @Test fun configuredLoginRunsWithoutAnyConfirmationAndWipesCodeAfterVerifiedAccount() = runTest {
        val d = Device()
        assertEquals(SmsLoginStatus.LOGGED_IN, SmsLoginAutomation(d) { d.allowed }.run())
        assertEquals(listOf("account", "listen", "request", "receive", "fill", "submit", "verified"), d.events)
        assertTrue(d.secret.all { it == '\u0000' })
    }
    @Test fun appAutoSubmissionIsVerifiedWithoutASecondClick() = runTest {
        val d = Device().apply { afterFill = { outcome = SmsLoginOutcome.Verified } }
        assertEquals(SmsLoginStatus.LOGGED_IN, SmsLoginAutomation(d) { true }.run())
        assertFalse(d.events.contains("submit"))
    }
    @Test fun wrongMessageOrExpiredWindowNeverFillsOrSubmits() = runTest {
        val p = profile()
        val window = SmsLoginWindow(p.scope, 10_000)
        assertNull(window.accept(p.sender, "【其他服务】账户7788登录验证码246810", 11_000, 11_000))
        assertNull(window.accept(p.sender, "【示例服务】账户7799登录验证码246810", 11_000, 11_000))
        assertNull(window.accept(p.sender, "【示例服务】账户7788支付验证码246810", 11_000, 11_000))
        assertNull(window.accept(p.sender, "【示例服务】账户7788登录验证码246810", 9_000, 11_000))
        val d = Device().apply { received = false }
        assertEquals(SmsLoginStatus.NOT_FILLED, SmsLoginAutomation(d) { true }.run())
        assertFalse("fill" in d.events); assertFalse("submit" in d.events)
    }
    @Test fun disabledOrRevokedAfterFillCannotSubmitAndNeverResumesObservation() = runTest {
        val d = Device().apply { afterFill = { allowed = false } }
        assertEquals(SmsLoginStatus.REVOKED, SmsLoginAutomation(d) { d.allowed }.run())
        assertFalse("submit" in d.events); assertFalse(d.resumed)
        assertTrue(d.secret.all { it == '\u0000' })
        val disabled = Device()
        assertEquals(SmsLoginStatus.REVOKED, SmsLoginAutomation(disabled) { false }.run())
        assertTrue(disabled.events.isEmpty())
    }
    @Test fun cancellationDuringSubmissionWipesAndDoesNotClaimSuccess() = runTest {
        val d = Device().apply { submitBlock = { awaitCancellation() } }
        val job = launch { SmsLoginAutomation(d) { true }.run() }
        runCurrent(); job.cancelAndJoin()
        assertFalse(d.resumed); assertTrue(d.secret.all { it == '\u0000' })
        assertEquals(1, d.events.count { it == "submit" })
    }
    @Test fun failedAndUnverifiedLoginDoNotResumeOrRetry() = runTest {
        for ((outcome, expected) in listOf(SmsLoginOutcome.Rejected to SmsLoginStatus.LOGIN_FAILED,
            SmsLoginOutcome.Pending to SmsLoginStatus.LOGIN_UNVERIFIED, SmsLoginOutcome.Unsafe to SmsLoginStatus.LOGIN_UNVERIFIED)) {
            val d = Device().apply { afterSubmit = outcome }
            assertEquals(expected, SmsLoginAutomation(d) { true }.run())
            assertFalse(d.resumed); assertEquals(1, d.events.count { it == "submit" })
            assertTrue(d.secret.all { it == '\u0000' })
        }
    }
    private fun element(id: String, text: String) = UiElement("@e1", "TextView", text, 0, 0, emptyList(), "$app:id/$id", false, false, false)
    private fun landing() = ScreenSnapshot("p1", app, listOf(element("logged", "欢迎回来"), element("who", "尾号7788")), 1080, 2400)
    @Test fun successRequiresRegisteredLandingExactAccountAndNoResidualCode() {
        val p = profile(); val code = "246810".toCharArray(); val s = landing()
        assertTrue(p.valid)
        assertEquals(SmsLoginOutcome.Verified, SmsLoginEvidence.outcome(p, s, code))
        assertEquals(SmsLoginOutcome.Pending, SmsLoginEvidence.outcome(p, s.copy(elements = listOf(element("logged", "欢迎回来"), element("who", "尾号7799"))), code))
        for (text in listOf("246810", "2 4 6 8 1 0", "２４６８１０"))
            assertEquals(SmsLoginOutcome.Pending, SmsLoginEvidence.outcome(p, s.copy(elements = s.elements + element("toast", text)), code))
        assertEquals(SmsLoginOutcome.Pending, SmsLoginEvidence.outcome(p, s.copy(elements = s.elements + element("otp", "")), code))
        assertEquals(SmsLoginOutcome.Unsafe, SmsLoginEvidence.outcome(p, s.copy(packageName = "com.other"), code))
        assertEquals(SmsLoginOutcome.Unsafe, SmsLoginEvidence.outcome(p, s.copy(elements = s.elements + element("pay", "支付确认")), code))
    }
    @Test fun verifiedLoginRestoresTextButNeverExposesResidualCodeOrImages() = runTest {
        val raw = landing().copy(elements = landing().elements + element("toast", "7788 246810 2 4 6 8 1 0 ２４６８１０"))
        val screen = SmsPrivateScreen(object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = raw
            override suspend fun screenshotPng(): ByteArray = error("image must stay local")
        })
        screen.begin(); assertTrue(screen.snapshot().protected)
        screen.resumeVerified("246810".toCharArray())
        assertFalse(screen.privateLogin)
        val safe = screen.snapshot()
        assertEquals(app, safe.packageName)
        assertTrue(safe.elements.any { it.name == "欢迎回来" })
        assertFalse(safe.toString().contains("246810")); assertFalse(safe.toString().contains("2 4 6 8 1 0"))
        assertNull(screen.screenshotPng())
        screen.close()
    }
}

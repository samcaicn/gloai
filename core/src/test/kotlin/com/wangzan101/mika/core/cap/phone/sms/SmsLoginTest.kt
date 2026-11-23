package com.wangzan101.mika.core.cap.phone.sms

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.phone.PhoneSmsLogin
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.*
import com.wangzan101.mika.core.screen.uistate.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SmsLoginTest {
    private val pkg = "com.example.login"
    private val rid = "$pkg:id/otp"
    private val scope = SmsLoginScope("10690001", "示例服务", "7788")
    private val body = "【示例服务】尾号7788账户登录验证码：246810，五分钟内有效。"
    private fun screen() = ScreenSnapshot("p1", pkg, listOf(
        UiElement("@e1", "TextView", "账户 7788 登录", 0, 0, emptyList(), null, false, false, false),
        UiElement("@e2", "EditText", "验证码", 0, 0, emptyList(), rid, true, true, false, hint = true),
    ), 1080, 2400)

    @Test fun matchesExactServiceSenderAccountAndLoginCode() {
        val code = SmsLoginMatcher.code(scope, scope.sender, body)!!
        try { assertArrayEquals("246810".toCharArray(), code) } finally { code.fill('\u0000') }
        assertTrue(SmsLoginMatcher.target(screen(), pkg, rid, scope))
    }

    @Test fun rejectsUnrelatedAndAmbiguousMessages() {
        val rejected = listOf(
            body.replace("示例服务", "其他服务"), body.replace("7788", "7799"),
            body.replace("登录", "支付"), body.replace("登录", "注册"), body.replace("登录", "找回密码"),
            body + "转账请勿告知他人", body + "或135790", body + "校验码：135790", body.replace("246810", "123456789"),
            body.replace("登录", "安全验证"), "推广消息：$body", body.replace("7788", "17788"),
        )
        for (message in rejected) assertNull(message, SmsLoginMatcher.code(scope, scope.sender, message))
        assertNull(SmsLoginMatcher.code(scope, "10690002", body))
        assertNull(SmsLoginMatcher.code(SmsLoginScope("10690001", "示例服务", "246810"), scope.sender, body))
        assertNull(SmsLoginMatcher.code(SmsLoginScope("", "", ""), scope.sender, body))
    }

    @Test fun refusesAccountChangesWrongAppsStaleAndAmbiguousEditors() {
        val s = screen()
        assertFalse(SmsLoginMatcher.target(s.copy(packageName = "com.other.app"), pkg, rid, scope))
        assertFalse(SmsLoginMatcher.target(s.copy(changedDuringRead = true), pkg, rid, scope))
        assertFalse(SmsLoginMatcher.target(s.copy(protected = true), pkg, rid, scope))
        assertFalse(SmsLoginMatcher.target(s.copy(elements = s.elements + s.elements.last()), pkg, rid, scope))
        assertFalse(SmsLoginMatcher.target(s.copy(elements = listOf(s.elements.last())), pkg, rid, scope))
        assertFalse(SmsLoginMatcher.target(s, pkg, "com.other.app:id/otp", scope))
        assertFalse(SmsLoginMatcher.target(s.copy(elements = s.elements.map { it.copy(name = "搜索", placeholder = null) }), pkg, "$pkg:id/search", scope))
        assertFalse(SmsLoginMatcher.target(s.copy(elements = s.elements.map { it.copy(secret = true) }), pkg, rid, scope))
        assertFalse(SmsLoginMatcher.target(s.copy(windows = listOf(UiWindowTree(1, "application", 1, null, null, true, true, emptyList()))), pkg, rid, scope))
    }

    @Test fun newMessageWindowRejectsOldFutureExpiredAndReplayButNotUnrelatedFirst() {
        val start = 10_123L
        val window = SmsLoginWindow(scope, start)
        assertNull(window.accept(scope.sender, body, 9_000, 11_000))
        assertNull(window.accept(scope.sender, body, 20_000, 11_000))
        assertNull(window.accept("wrong", body, 11_000, 11_000))
        val code = window.accept(scope.sender, body, 10_000, 11_000)!! // SMS second precision
        code.fill('\u0000')
        assertNull(window.accept(scope.sender, body, 11_000, 11_000))
        val expired = SmsLoginWindow(scope, start)
        assertNull(expired.accept(scope.sender, body, 101_000, 101_000))
        val cancelled = SmsLoginWindow(scope, start)
        cancelled.close()
        assertNull(cancelled.accept(scope.sender, body, 11_000, 11_000))
    }

    @Test fun simultaneousMatchesConsumeOnlyOnce() {
        val window = SmsLoginWindow(scope, 10_000)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val jobs = List(8) {
            kotlin.concurrent.thread {
                window.accept(scope.sender, body, 11_000, 11_000)?.let {
                    accepted.incrementAndGet(); it.fill('\u0000')
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1, accepted.get())
    }

    @Test fun privacyCurtainDoesNotReadOrCaptureOrDispatchStaleReferences() = runTest {
        var reads = 0
        var writes = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot { reads++; return screen() }
            override suspend fun screenshotPng(): ByteArray { error("must not capture") }
            override suspend fun readVisibleText(): List<VisibleTextLine> { error("must not OCR") }
            override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent { error("must not read notifications") }
            override suspend fun systemCommand(command: String): SystemCommandResult { error("must not run shell") }
            override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { writes++; return ActionResult.Ok(hint = body) }
        }
        val private = SmsPrivateScreen(inner)
        assertEquals(pkg, private.snapshot().packageName)
        private.begin()
        assertTrue(private.snapshot().protected)
        assertTrue(private.peek().elements.isEmpty())
        assertNull(private.screenshotPng())
        assertNull(private.readVisibleText())
        assertNull(private.nextEvent("notification", null, 100))
        assertTrue(private.watchEvents("notification", null, 100, 10).isEmpty())
        assertNull(private.systemCommand("anything"))
        assertTrue(private.typeText("@e2", "p1", "anything") is ActionResult.Failed)
        assertEquals(1, reads)
        assertEquals(0, writes)
    }

    @Test fun toolSendsOnlyPublicTargetIdentifiersAndReturnsStatus() = runTest {
        var calls = 0
        val port = object : SmsLoginPort {
            override suspend fun login(packageName: String): SmsLoginStatus {
                assertEquals(pkg, packageName)
                calls++
                return SmsLoginStatus.LOGGED_IN
            }
        }
        val dispatcher = toolDispatcher(FakeScreen(), smsLogin = port)
        assertTrue(PhoneSmsLogin.available(dispatcher.ctx))
        assertFalse(PhoneSmsLogin.sandboxCallable)
        assertFalse(PhoneSmsLogin.batchable)
        val args = Json.parseToJsonElement("""{"app":"$pkg"}""") as kotlinx.serialization.json.JsonObject
        val result = PhoneSmsLogin.execute(Args(PhoneSmsLogin.def, args), dispatcher.ctx)
        assertEquals(1, calls)
        assertTrue(result.content.toString().contains("logged_in"))
        assertFalse(result.content.toString().contains("246810"))
        assertFalse(PhoneSmsLogin.available(toolDispatcher(FakeScreen()).ctx))
        val base = com.wangzan101.mika.core.run.RunSpec(com.wangzan101.mika.core.run.RunId("test"), "login", "test")
        for (origin in com.wangzan101.mika.core.run.RunOrigin.entries.filter { it != com.wangzan101.mika.core.run.RunOrigin.Ui }) {
            assertFalse(PhoneSmsLogin.available(toolDispatcher(FakeScreen(), smsLogin = port,
                web = com.wangzan101.mika.core.cap.web.FakeWebPort(),
                runSpec = base.copy(provenance = com.wangzan101.mika.core.run.RunProvenance(origin))).ctx))
        }
        assertFalse(PhoneSmsLogin.available(toolDispatcher(FakeScreen(), smsLogin = port, runSpec = base.copy(readOnly = true)).ctx))
        assertFalse(PhoneSmsLogin.available(toolDispatcher(FakeScreen(), smsLogin = port, runSpec = base.copy(parent = base.id)).ctx))
        assertFalse(PhoneSmsLogin.available(toolDispatcher(FakeScreen(), smsLogin = port,
            runSpec = base.copy(provenance = com.wangzan101.mika.core.run.RunProvenance(attendance = com.wangzan101.mika.core.run.Attendance.Unattended))).ctx))
    }
}

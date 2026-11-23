package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.chatgpt.SignInException
import com.wangzan101.mika.core.model.chatgpt.SignInFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatGptSignInsTest {
    private var kept = 0

    private fun TestScope.signIns(keepAlive: () -> Unit = { kept++ }) = ChatGptSignIns(backgroundScope, keepAlive, now = { 1_000L })

    @Test fun keepsAliveWhileRunningAndEndsOnSuccess() = runTest {
        val s = signIns()
        val gate = CompletableDeferred<Unit>()
        assertTrue(s.start { gate.await() })
        assertEquals(1, kept)
        assertTrue(s.running.value)
        assertFalse("同一时刻只有一次", s.start { })
        assertEquals(1, kept)
        gate.complete(Unit)
        runCurrent()
        assertFalse(s.running.value)
    }

    @Test fun endsOnFailureAndOnCancel() = runTest {
        val s = signIns()
        s.start { throw IllegalStateException("boom") }
        runCurrent()
        assertFalse("失败也要结束", s.running.value)
        s.start { awaitCancellation() }
        runCurrent()
        assertTrue(s.running.value)
        s.cancel()
        runCurrent()
        assertFalse("取消也要结束", s.running.value)
        assertTrue("结束后可以再开", s.start { })
        runCurrent()
        assertFalse(s.running.value)
    }

    @Test fun keepAliveThatCannotStartDoesNotStopTheSignIn() = runTest {
        var ran = false
        val s = signIns(keepAlive = { throw IllegalStateException("not allowed") })
        assertTrue(s.start { ran = true })
        runCurrent()
        assertTrue(ran)
        assertFalse(s.running.value)
    }

    @Test fun lastFailureKeepsCodeAndRedactsTokens() = runTest {
        val s = signIns()
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEyMzQ1Njc4OTAifQ.c2lnbmF0dXJlLWJ5dGVzLWhlcmUtMTIzNDU2Nzg5MA"
        s.recordFailure(SignInException(SignInFailure.Exchange, "invalid_request — id_token=$jwt access_token: sk-abcdefghijklmnop1234567890", code = "invalid_request"))
        val f = s.lastFailure!!
        assertEquals(1_000L, f.atMs)
        assertEquals("Exchange", f.kind)
        assertEquals("invalid_request", f.code)
        assertTrue(f.detail, f.detail.startsWith("Exchange: invalid_request"))
        assertFalse(f.detail, f.detail.contains("eyJ"))
        assertFalse(f.detail, f.detail.contains("abcdefghijklmnop"))
    }
}

package com.wangzan101.mika.settings

import com.wangzan101.mika.core.device.CredentialKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class DeviceAccessSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun settings() = DeviceAccessSettings(tmp.root, AesGcmKeyCipher { key })

    @Test fun defaultsClosedAndPersistsEncrypted() {
        val s = settings()
        assertFalse(s.enabled)
        s.setCredential(CredentialKind.Password, "Test pass!4")
        s.setEnabled(true)
        assertFalse(tmp.root.resolve("device-access.bin").readText(Charsets.ISO_8859_1).contains("Test pass!4"))
        val reloaded = settings()
        assertTrue(reloaded.enabled)
        reloaded.credential()!!.use { assertEquals("Test pass!4", String(it.value)) }
    }

    @Test fun failureAndProcessRestartCannotResetAttemptPermission() {
        val s = settings(); s.setCredential(CredentialKind.Pin, "123456"); s.setEnabled(true)
        s.credential()!!.use { assertTrue(s.beginAttempt(it)); assertFalse(s.beginAttempt(it)) }
        val next = settings()
        assertTrue(next.attemptBlocked)
        next.setEnabled(false); next.setEnabled(true)
        assertTrue(next.attemptBlocked)
        next.setCredential(CredentialKind.Pin, "123456")
        assertFalse(next.attemptBlocked)
    }

    @Test fun replacingCredentialInvalidatesAnInFlightCopy() {
        val s = settings(); s.setCredential(CredentialKind.Pin, "123456"); s.setEnabled(true)
        s.credential()!!.use { old ->
            s.setCredential(CredentialKind.Pin, "654321")
            assertFalse(s.beginAttempt(old))
        }
    }

    @Test fun completionDoesNotUndoConcurrentDisable() {
        val s = settings(); s.setCredential(CredentialKind.Pin, "123456"); s.setEnabled(true)
        s.credential()!!.use {
            assertTrue(s.beginAttempt(it)); s.setEnabled(false); s.completeAttempt(it)
            assertFalse(s.enabled); assertFalse(s.attemptBlocked)
        }
        s.clearCredential()
        assertNull(settings().credential())
    }

    @Test fun localSwitchPersistsIndependentlyWithItsOwnAttemptLatch() {
        val s = settings(); s.setCredential(CredentialKind.Pin, "123456")
        s.setLocalEnabled(true)
        assertFalse(s.enabled)
        s.setEnabled(true)
        assertTrue(s.localEnabled)
        // 两个开关独立持久化:重载后各自保持,谁也不带动谁
        val reloaded = settings()
        assertTrue(reloaded.enabled)
        assertTrue(reloaded.localEnabled)
        reloaded.setLocalEnabled(false)
        assertFalse(settings().localEnabled)
        assertTrue(settings().enabled)
        settings().setLocalEnabled(true)
        // blocked 占位互不通:远程占位不挡本机占位,本机占位也只放一次且随重启存活
        val latching = settings()
        latching.credential()!!.use { assertTrue(latching.beginAttempt(it)) }
        assertTrue(latching.attemptBlocked)
        assertTrue(latching.beginLocalAttempt())
        assertFalse(latching.beginLocalAttempt())
        val fresh = settings()
        assertTrue(fresh.attemptBlocked)
        assertFalse(fresh.beginLocalAttempt())
        fresh.credential()!!.use { assertFalse(fresh.beginAttempt(it)) }
    }

    @Test fun corruptCiphertextAndLostKeyFailClosed() {
        val s = settings(); s.setCredential(CredentialKind.Pin, "123456"); s.setEnabled(true)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertFalse(DeviceAccessSettings(tmp.root, AesGcmKeyCipher { other }).enabled)
        tmp.root.resolve("device-access.bin").writeText("broken")
        assertFalse(settings().enabled)
        assertNull(settings().credential())
    }
}

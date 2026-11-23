package com.wangzan101.mika.wechat

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.cap.wechat.WechatAccount
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WechatStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(data: ByteArray): ByteArray = data
    }

    @Test fun accountCursorAndPendingMessageSurviveReopen() {
        val dir = tmp.newFolder()
        val first = WechatStore(dir, cipher)
        first.saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        first.setEnabled(true)
        first.rememberContext("owner", "context")
        val peer = first.peer("owner")
        assertTrue(first.journal("message-1", peer, "任务"))
        assertFalse(first.journal("message-1", peer, "任务"))
        first.saveCursor("next")

        val reopened = WechatStore(dir, cipher)
        assertEquals("secret", reopened.account()?.token)
        assertTrue(reopened.enabled())
        assertEquals("next", reopened.cursor())
        assertEquals("context", reopened.contextToken("owner"))
        assertEquals(peer, reopened.peer("owner"))
        assertEquals(listOf(PendingWechatMessage("message-1", peer, "任务")), reopened.pending())

        reopened.acknowledge("message-1")
        assertEquals(emptyList<PendingWechatMessage>(), WechatStore(dir, cipher).pending())
        assertFalse(reopened.journal("message-1", peer, "任务"))
        assertFalse(WechatStore(dir, cipher).journal("message-1", peer, "任务"))
    }

    @Test fun acknowledgementHistoryPrunesOldestFirstAndSurvivesReopen() {
        val dir = tmp.newFolder()
        val store = WechatStore(dir, cipher)
        val peer = ChannelPeer(WechatChannel.ID, "owner", "thread")
        val count = WechatStore.MAX_ACKNOWLEDGED_MESSAGES + 2
        repeat(count) { index ->
            val id = "message-$index"
            assertTrue(store.journal(id, peer, "任务 $index"))
            store.acknowledge(id)
        }

        val reopened = WechatStore(dir, cipher)
        assertTrue(reopened.journal("message-0", peer, "旧消息"))
        assertTrue(reopened.journal("message-1", peer, "旧消息"))
        for (index in 2 until count) {
            assertFalse("message-$index should remain deduplicated", reopened.journal("message-$index", peer, "重复"))
        }
    }

    @Test fun accountSwitchClearsPollingStateButTokenRefreshPreservesIt() {
        val dir = tmp.newFolder()
        val store = WechatStore(dir, cipher)
        store.saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        store.setEnabled(true)
        store.saveCursor("next")
        store.rememberContext("owner", "context")
        val oldPeer = store.peer("owner")
        store.journal("old-pending", oldPeer, "待登记")
        store.journal("old-ack", oldPeer, "已登记")
        store.acknowledge("old-ack")
        store.setUnlockAllowed(true)

        store.saveAccount(WechatAccount("new-token", "https://example.test", "bot", "owner"))
        assertFalse(store.unlockAllowed())
        assertFalse(store.allowUnlock.value)
        assertFalse(store.canUnlock(oldPeer))
        assertFalse(WechatStore(dir, cipher).unlockAllowed())
        assertTrue(store.enabled())
        assertEquals("next", store.cursor())
        assertEquals("context", store.contextToken("owner"))
        assertEquals(oldPeer, store.peer("owner"))
        assertEquals(1, store.pending().size)
        assertFalse(store.journal("old-ack", oldPeer, "重复"))

        store.setUnlockAllowed(true)
        store.saveAccount(WechatAccount("secret", "https://example.test", "bot", "new-owner"))
        assertFalse(store.unlockAllowed())
        assertFalse(store.allowUnlock.value)
        assertFalse(store.enabled())
        assertEquals("", store.cursor())
        assertEquals(null, store.contextToken("owner"))
        assertEquals(emptyList<PendingWechatMessage>(), store.pending())
        val newPeer = store.peer("new-owner")
        assertTrue(store.journal("old-ack", newPeer, "新账号消息"))
        assertTrue(newPeer.threadId != oldPeer.threadId)
        store.setEnabled(true)
        store.setUnlockAllowed(true)
        assertTrue(store.canUnlock(newPeer))
        assertFalse(store.canUnlock(oldPeer))
    }

    @Test fun unbindClearsCredentialsAndChannelState() {
        val dir = tmp.newFolder()
        val store = WechatStore(dir, cipher)
        store.saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        store.setEnabled(true)
        val oldPeer = store.peer("owner")
        store.setUnlockAllowed(true)
        store.clear()
        assertEquals(null, store.account())
        assertFalse(store.enabled())
        assertFalse(store.unlockAllowed())
        assertFalse(store.allowUnlock.value)
        assertFalse(store.canUnlock(oldPeer))
        assertEquals("", store.cursor())
        val reopened = WechatStore(dir, cipher)
        assertEquals(null, reopened.account())
        assertFalse(reopened.unlockAllowed())
        assertFalse(reopened.canUnlock(oldPeer))

        reopened.saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        reopened.setEnabled(true)
        reopened.setUnlockAllowed(true)
        val newPeer = reopened.peer("owner")
        assertTrue(reopened.canUnlock(newPeer))
        assertFalse(reopened.canUnlock(oldPeer))
    }

    @Test fun unlockPermissionIsOptInAndPersistsGrantAndRevocation() {
        val dir = tmp.newFolder()
        val store = WechatStore(dir, cipher)
        assertFalse(store.unlockAllowed())
        assertFalse(store.allowUnlock.value)
        store.saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        store.setEnabled(true)
        val peer = store.peer("owner")
        assertFalse(store.canUnlock(peer))

        store.setUnlockAllowed(true)
        assertTrue(store.unlockAllowed())
        assertTrue(store.allowUnlock.value)
        assertTrue(store.canUnlock(peer))
        val reopened = WechatStore(dir, cipher)
        assertTrue(reopened.unlockAllowed())
        assertTrue(reopened.allowUnlock.value)
        assertTrue(reopened.canUnlock(peer))

        reopened.setUnlockAllowed(false)
        assertFalse(reopened.unlockAllowed())
        assertFalse(reopened.allowUnlock.value)
        assertFalse(reopened.canUnlock(peer))
        assertFalse(WechatStore(dir, cipher).canUnlock(peer))
    }

    @Test fun legacyStateCannotGrantUnlockWithoutTheSeparateRecord() {
        val dir = tmp.newFolder()
        boundStore(dir)
        File(dir, "state.json").writeText("""
            {"enabled":true,"allowUnlock":true,"cursor":"next","threads":{"owner":"legacy-thread"}}
        """.trimIndent())

        val reopened = WechatStore(dir, cipher)
        val peer = ChannelPeer(WechatChannel.ID, "owner", "legacy-thread")
        assertTrue(reopened.enabled())
        assertEquals("next", reopened.cursor())
        assertEquals(peer, reopened.peer("owner"))
        assertFalse(reopened.unlockAllowed())
        assertFalse(reopened.allowUnlock.value)
        assertFalse(reopened.canUnlock(peer))
    }

    @Test fun corruptMissingOrMismatchedUnlockRecordsFailClosed() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer("owner")
        store.setUnlockAllowed(true)
        val grantFile = File(dir, "unlock.json")
        val invalid = listOf(
            "not-json",
            "{}",
            """{"ownerId":"owner","botId":"bot"}""",
            """{"ownerId":"other-owner","botId":"bot","allowed":true}""",
            """{"ownerId":"owner","botId":"other-bot","allowed":true}""",
            """{"ownerId":"owner","botId":"bot","allowed":false}""",
        )
        for (record in invalid) {
            grantFile.writeText(record)
            val reopened = WechatStore(dir, cipher)
            assertFalse(record, reopened.unlockAllowed())
            assertFalse(record, reopened.allowUnlock.value)
            assertFalse(record, reopened.canUnlock(peer))
            assertEquals(peer, reopened.peer("owner"))
        }
        assertTrue(grantFile.delete())
        assertFalse(WechatStore(dir, cipher).canUnlock(peer))
    }

    @Test fun unlockRequiresTheBoundOwnerAndItsCurrentWechatThread() {
        val store = boundStore(tmp.newFolder())
        val peer = store.peer("owner")
        val otherPeer = store.peer("other-owner")
        store.setUnlockAllowed(true)

        assertTrue(store.canUnlock(peer))
        assertFalse(store.canUnlock(null))
        assertFalse(store.canUnlock(otherPeer))
        assertFalse(store.canUnlock(peer.copy(channelId = "mcp")))
        assertFalse(store.canUnlock(peer.copy(peerId = "other-owner")))
        assertFalse(store.canUnlock(peer.copy(threadId = "forged-thread")))
        assertFalse(store.canUnlock(peer.copy(threadId = otherPeer.threadId)))
    }

    @Test fun pausingReceptionBlocksUnlockUntilReceptionResumes() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer("owner")
        store.setUnlockAllowed(true)
        store.setEnabled(false)
        assertTrue(store.unlockAllowed())
        assertFalse(store.canUnlock(peer))

        val reopened = WechatStore(dir, cipher)
        assertTrue(reopened.unlockAllowed())
        assertFalse(reopened.canUnlock(peer))
        reopened.setEnabled(true)
        assertTrue(reopened.canUnlock(peer))
        reopened.setUnlockAllowed(false)
        reopened.setEnabled(false)
        reopened.setEnabled(true)
        assertFalse(reopened.canUnlock(peer))
    }

    @Test fun changingBotsForTheSameOwnerCannotReuseAnOldThread() {
        val store = boundStore(tmp.newFolder())
        val oldPeer = store.peer("owner")
        store.setUnlockAllowed(true)

        store.saveAccount(WechatAccount("other-token", "https://example.test", "other-bot", "owner"))
        assertFalse(store.unlockAllowed())
        assertFalse(store.allowUnlock.value)
        store.setEnabled(true)
        store.setUnlockAllowed(true)
        assertFalse(store.canUnlock(oldPeer))
        val newPeer = store.peer("owner")
        assertTrue(store.canUnlock(newPeer))
        assertFalse(store.canUnlock(oldPeer))
    }

    @Test fun unlockCannotBeGrantedWithoutBinding() {
        val store = WechatStore(tmp.newFolder(), cipher)
        assertThrows(IllegalStateException::class.java) { store.setUnlockAllowed(true) }
        assertFalse(store.unlockAllowed())
        assertFalse(store.allowUnlock.value)
        assertFalse(store.canUnlock(ChannelPeer(WechatChannel.ID, "owner", "thread")))
    }

    @Test fun missingCredentialsCannotRestoreAnUnlockGrant() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer("owner")
        store.setUnlockAllowed(true)
        assertTrue(File(dir, "account.enc").delete())

        val reopened = WechatStore(dir, cipher)
        assertEquals(null, reopened.account())
        assertFalse(reopened.unlockAllowed())
        assertFalse(reopened.allowUnlock.value)
        assertFalse(reopened.canUnlock(peer))
    }

    @Test fun failedGrantStaysOffAndDoesNotLeakIntoALaterSave() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer("owner")
        val blocker = blockWrites(dir, "unlock.json")

        assertThrows(IOException::class.java) { store.setUnlockAllowed(true) }
        assertFalse(store.unlockAllowed())
        assertFalse(store.allowUnlock.value)
        assertFalse(store.canUnlock(peer))
        assertFalse(WechatStore(dir, cipher).canUnlock(peer))
        assertTrue(blocker.delete())
        store.saveCursor("next")
        assertFalse(WechatStore(dir, cipher).canUnlock(peer))
    }

    @Test fun revocationSurvivesRestartWhenTheMessageLedgerCannotBeWritten() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer("owner")
        store.saveCursor("next")
        store.journal("pending", peer, "待登记")
        store.journal("acknowledged", peer, "已登记")
        store.acknowledge("acknowledged")
        store.setUnlockAllowed(true)
        val originalLedger = File(dir, "state.json").readText()
        blockWrites(dir, "state.json")

        store.setUnlockAllowed(false)
        assertFalse(store.unlockAllowed())
        assertFalse(store.allowUnlock.value)
        assertFalse(store.canUnlock(peer))
        val reopened = WechatStore(dir, cipher)
        assertFalse(reopened.unlockAllowed())
        assertFalse(reopened.canUnlock(peer))
        assertEquals(originalLedger, File(dir, "state.json").readText())
        assertEquals("next", reopened.cursor())
        assertEquals(listOf(PendingWechatMessage("pending", peer, "待登记")), reopened.pending())
        assertFalse(reopened.journal("acknowledged", peer, "不应重放"))
    }

    @Test fun revocationRemovesTheGrantWhenItsReplacementCannotBeWritten() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer("owner")
        store.setUnlockAllowed(true)
        blockWrites(dir, "unlock.json")

        store.setUnlockAllowed(false)

        assertFalse(store.allowUnlock.value)
        assertFalse(store.canUnlock(peer))
        assertFalse(WechatStore(dir, cipher).canUnlock(peer))
    }

    @Test fun failedRevocationBlocksUnlockWithoutReportingADurableChange() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer("owner")
        store.setUnlockAllowed(true)
        val grantFile = object : File(dir, "unlock.json") {
            override fun delete(): Boolean = false
        }
        val reopened = WechatStore(dir, cipher, grantFile)
        val blocker = blockWrites(dir, "unlock.json")

        assertThrows(IOException::class.java) { reopened.setUnlockAllowed(false) }

        assertTrue(reopened.allowUnlock.value)
        assertFalse(reopened.canUnlock(peer))
        assertTrue(blocker.delete())
        reopened.setUnlockAllowed(false)
        assertFalse(WechatStore(dir, cipher).canUnlock(peer))
    }

    @Test fun revocationReplacesARegularGrantThatCannotBeDeletedAndSurvivesRestart() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer("owner")
        store.setUnlockAllowed(true)
        val grantFile = object : File(dir, "unlock.json") {
            override fun delete(): Boolean = false
        }
        val reopened = WechatStore(dir, cipher, grantFile)
        assertTrue(reopened.canUnlock(peer))

        reopened.setUnlockAllowed(false)

        assertFalse(reopened.unlockAllowed())
        assertFalse(reopened.allowUnlock.value)
        assertFalse(reopened.canUnlock(peer))
        assertTrue(grantFile.isFile)
        assertFalse(WechatStore(dir, cipher).unlockAllowed())
        assertFalse(WechatStore(dir, cipher).canUnlock(peer))
    }

    @Test fun failedRebindRevokesUnlockBeforeUpdatingCredentials() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer("owner")
        store.setUnlockAllowed(true)
        blockWrites(dir, "account.enc")

        assertThrows(IOException::class.java) {
            store.saveAccount(WechatAccount("new-token", "https://example.test", "bot", "owner"))
        }
        assertEquals("secret", store.account()?.token)
        assertFalse(store.unlockAllowed())
        assertFalse(store.allowUnlock.value)
        assertFalse(store.canUnlock(peer))
        val reopened = WechatStore(dir, cipher)
        assertEquals("secret", reopened.account()?.token)
        assertFalse(reopened.unlockAllowed())
        assertFalse(reopened.canUnlock(peer))
    }

    @Test fun failedAccountSwitchCannotRestoreAnOldGrantOnReopen() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer("owner")
        store.journal("pending", peer, "待登记")
        store.setUnlockAllowed(true)
        blockWrites(dir, "state.json")

        assertThrows(IOException::class.java) {
            store.saveAccount(WechatAccount("new-token", "https://example.test", "bot", "new-owner"))
        }
        val reopened = WechatStore(dir, cipher)
        assertEquals("owner", reopened.account()?.ownerId)
        assertFalse(reopened.unlockAllowed())
        assertFalse(reopened.canUnlock(peer))
        assertEquals(listOf(PendingWechatMessage("pending", peer, "待登记")), reopened.pending())
    }

    @Test fun receptionWriteFailuresCannotAllowUnlock() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer("owner")
        store.saveCursor("next")
        store.journal("pending", peer, "待登记")
        store.setUnlockAllowed(true)
        store.setEnabled(false)
        val blocker = blockWrites(dir, "state.json")

        assertThrows(IOException::class.java) { store.setEnabled(true) }
        assertFalse(store.enabled())
        assertFalse(store.canUnlock(peer))
        assertFalse(WechatStore(dir, cipher).canUnlock(peer))
        assertTrue(blocker.delete())
        store.setEnabled(true)
        assertTrue(store.canUnlock(peer))
        blockWrites(dir, "state.json")
        assertThrows(IOException::class.java) { store.setEnabled(false) }
        assertFalse(store.enabled())
        assertFalse(store.unlockAllowed())
        assertFalse(store.allowUnlock.value)
        assertFalse(store.canUnlock(peer))
        val reopened = WechatStore(dir, cipher)
        assertFalse(reopened.unlockAllowed())
        assertFalse(reopened.canUnlock(peer))
        assertEquals("next", reopened.cursor())
        assertEquals(listOf(PendingWechatMessage("pending", peer, "待登记")), reopened.pending())
    }

    @Test fun boundOwnerPeerRequiresEnabledAccountContextTokenAndAnExistingThread() {
        val dir = tmp.newFolder()
        val store = WechatStore(dir, cipher)
        assertEquals(null, store.boundOwnerPeer())
        store.saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        assertEquals(null, store.boundOwnerPeer())
        store.setEnabled(true)
        assertEquals(null, store.boundOwnerPeer())
        store.rememberContext("owner", "context")
        assertEquals(null, store.boundOwnerPeer())
        val peer = store.peer("owner")
        assertEquals(peer, store.boundOwnerPeer())
        store.setEnabled(false)
        assertEquals(null, store.boundOwnerPeer())
    }

    private fun boundStore(dir: File) = WechatStore(dir, cipher).apply {
        saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        setEnabled(true)
    }

    private fun blockWrites(dir: File, name: String) = File(dir, "$name.tmp").apply { assertTrue(mkdir()) }
}

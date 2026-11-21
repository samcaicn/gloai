package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.wechat.WechatChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskUnlockPolicyTest {
    private val peer = ChannelPeer(WechatChannel.ID, "owner", "thread")

    @Test fun scheduledTasksKeepUnlockWithoutResolvingWechat() {
        val authorized = taskUnlockAuthorization(RunOrigin.Trigger, null) {
            error("A scheduled task must not initialize WeChat")
        }

        assertTrue(requireNotNull(authorized).invoke())
    }

    @Test fun otherTaskOriginsCannotUseWechatPermission() {
        for (origin in listOf(RunOrigin.Ui, RunOrigin.Mcp, RunOrigin.Child)) {
            assertNull(taskUnlockAuthorization(origin, peer) {
                error("$origin must not read WeChat permission")
            })
        }
    }

    @Test fun missingOrOtherChannelPeersNeverResolveWechat() {
        for (candidate in listOf(null, peer.copy(channelId = "local"), peer.copy(channelId = "mcp"))) {
            assertNull(taskUnlockAuthorization(RunOrigin.Channel, candidate) {
                error("A non-WeChat peer must not read WeChat permission")
            })
        }
    }

    @Test fun grantingPermissionLaterDoesNotChangeAManualWaitIntoAnUnlockAttempt() {
        var allowed = false
        var checks = 0
        val canUnlock: (ChannelPeer?) -> Boolean = { checks++; allowed }
        val waitingTask = taskUnlockAuthorization(RunOrigin.Channel, peer, canUnlockWechat = canUnlock)
        assertNull(waitingTask)
        assertEquals(1, checks)

        allowed = true
        assertNull(waitingTask)
        assertEquals(1, checks)
        val nextTask = taskUnlockAuthorization(RunOrigin.Channel, peer, canUnlockWechat = canUnlock)
        assertTrue(requireNotNull(nextTask).invoke())
    }

    /** Ui 任务跟着本机开关走:判定 lambda 每次调用都重查,不缓存开跑那一刻的值。 */
    @Test fun uiTasksFollowTheLocalUnlockSwitchInRealTime() {
        var on = true
        var checks = 0
        val authorized = requireNotNull(taskUnlockAuthorization(RunOrigin.Ui, null, localUnlockEnabled = {
            checks++
            on
        }) {
            error("Ui tasks must not read WeChat permission")
        })
        assertTrue(authorized())
        on = false
        assertFalse(authorized())
        on = true
        assertTrue(authorized())
        assertEquals(4, checks)
    }

    /** Mcp 来源在任何开关组合下都拿不到解锁授权。 */
    @Test fun mcpTasksStayUnauthorizedWhateverTheLocalSwitchSays() {
        for (on in listOf(false, true)) {
            assertNull(taskUnlockAuthorization(RunOrigin.Mcp, null, localUnlockEnabled = { on }) {
                error("Mcp tasks must not read WeChat permission")
            })
        }
    }

    @Test fun authorizedWechatTaskRechecksItsPeerAfterPermissionIsRevoked() {
        var allowed = true
        val checkedPeers = mutableListOf<ChannelPeer?>()
        val authorized = requireNotNull(taskUnlockAuthorization(RunOrigin.Channel, peer) {
            checkedPeers += it
            allowed
        })
        assertEquals(listOf(peer), checkedPeers)
        assertTrue(authorized())

        allowed = false
        assertFalse(authorized())
        assertEquals(listOf(peer, peer, peer), checkedPeers)
    }
}

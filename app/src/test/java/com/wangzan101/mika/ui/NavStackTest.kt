package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStackTest {
    @Test fun backReturnsToWhereYouCameFrom() {
        val nav = NavStack()
        assertEquals(Route.Chat, nav.current)
        assertFalse(nav.canGoBack)
        assertFalse(nav.back())

        // 侧栏进记忆,返回回到对话;从设置进记忆,返回回到设置
        nav.push(Route.Memory)
        assertTrue(nav.back())
        assertEquals(Route.Chat, nav.current)
        nav.push(Route.Settings)
        nav.push(Route.Memory)
        assertEquals(listOf(Route.Chat, Route.Settings, Route.Memory), nav.stack)
        nav.back()
        assertEquals(Route.Settings, nav.current)
    }

    @Test fun samePageIsNotPushedTwice() {
        val nav = NavStack()
        nav.push(Route.PhoneSetup)
        nav.push(Route.PhoneSetup)
        assertEquals(listOf(Route.Chat, Route.PhoneSetup), nav.stack)
    }

    @Test fun resetAndChatGoBackToTheRoot() {
        val nav = NavStack(listOf(Route.Settings, Route.Memory))
        assertEquals(listOf(Route.Chat, Route.Settings, Route.Memory), nav.stack)
        nav.push(Route.Chat)
        assertEquals(listOf(Route.Chat), nav.stack)
        nav.push(Route.Plans)
        nav.reset()
        assertEquals(listOf(Route.Chat), nav.stack)
    }

    @Test fun restoredStackAlwaysStartsWithChat() {
        assertEquals(listOf(Route.Chat, Route.Run), NavStack(listOf(Route.Chat, Route.Chat, Route.Run)).stack)
        assertEquals(listOf(Route.Chat), NavStack(emptyList()).stack)
    }

    @Test fun backFromAPageOpenedInTheDrawerReopensTheDrawer() {
        val nav = NavStack()
        nav.pushFromDrawer(Route.Settings)
        nav.push(Route.Memory)
        nav.back()
        assertFalse(nav.reopenDrawer)
        nav.back()
        assertTrue(nav.reopenDrawer)
        nav.drawerReopened()
        assertFalse(nav.reopenDrawer)
    }

    @Test fun pagesNotOpenedFromTheDrawerReturnToChatOnly() {
        val nav = NavStack()
        nav.push(Route.Settings)
        nav.back()
        assertFalse(nav.reopenDrawer)
    }

    @Test fun openingAConversationDoesNotReopenTheDrawer() {
        val nav = NavStack()
        val initialReset = nav.resetSerial
        nav.pushFromDrawer(Route.Plans)
        nav.reset()
        assertEquals(initialReset + 1, nav.resetSerial)
        assertFalse(nav.reopenDrawer)
        nav.push(Route.Settings)
        nav.back()
        assertFalse(nav.reopenDrawer)
    }

    // 通话页:正在通话时再点通话回到它(上面压着的页退掉);挂断时它从栈里抽走,压在下面也一样
    @Test fun callPageComesBackAndLeaves() {
        val nav = NavStack()
        nav.bringToTop(Route.VoiceCall)
        assertEquals(listOf(Route.Chat, Route.VoiceCall), nav.stack)
        nav.push(Route.Run)
        nav.bringToTop(Route.VoiceCall)
        assertEquals(listOf(Route.Chat, Route.VoiceCall), nav.stack)
        nav.leave(Route.VoiceCall)
        assertEquals(listOf(Route.Chat), nav.stack)
        nav.push(Route.VoiceCall)
        nav.push(Route.CallMode)
        nav.leave(Route.VoiceCall)
        assertEquals(listOf(Route.Chat, Route.CallMode), nav.stack)
        nav.leave(Route.VoiceCall)
        assertEquals(listOf(Route.Chat, Route.CallMode), nav.stack)
    }
}

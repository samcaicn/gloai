package com.wangzan101.mika.capsule

import com.wangzan101.mika.core.screen.user.CapturedScreen
import com.wangzan101.mika.core.screen.user.UserScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 胶囊卡片的输入框:收起时没发出的字留着,说的话接在后面。 */
class CardInputUiTest {
    private val screen = PendingScreen(CapturedScreen(null, UserScreen("", 0L, "com.android.settings", "设置")), null)

    @Test fun collapsingKeepsTheDraftButClosesTheFieldAndDropsTheScreen() {
        val ui = CardInputUi(open = true, draft = "帮我把这段", screen = screen).collapsed()
        assertEquals("帮我把这段", ui.draft)
        assertFalse(ui.open)
        assertNull(ui.screen)
    }

    @Test fun spokenTextFollowsTheDraft() {
        assertEquals("帮我把这段话发给小王", CardInputUi(draft = "帮我把这段话").withSpoken("发给小王").draft)
        assertEquals("send it to Tom", CardInputUi(draft = "send it").withSpoken("to Tom").draft)
        assertEquals("发给小王", CardInputUi().withSpoken("发给小王").draft)
    }

    @Test fun spokenTextKeepsTheAttachedScreen() {
        assertSame(screen, CardInputUi(draft = "", screen = screen).withSpoken("这个怎么回").screen)
    }
}

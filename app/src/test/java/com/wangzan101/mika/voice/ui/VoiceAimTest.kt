package com.wangzan101.mika.voice.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAimTest {
    // 「在听」卡片在根布局里 y 400..600,输入栏在它下面(手指从 y 680 按下)
    private val card = Rect(20f, 400f, 1060f, 600f)
    private val down = 680f
    private fun aim(y: Float, card: Rect = this.card) = voiceAim(Offset(500f, y), card, down - y, cancelLiftPx = 80f, sendLiftPx = 32f)

    @Test fun stayingOnTheBarFills() = assertEquals(VoiceAim.Fill, aim(670f))

    @Test fun betweenBarAndCardFills() = assertEquals(VoiceAim.Fill, aim(620f))

    @Test fun insideTheCardSends() {
        assertEquals(VoiceAim.Send, aim(590f))
        assertEquals(VoiceAim.Send, aim(500f))
        assertEquals(VoiceAim.Send, aim(400f))
    }

    @Test fun aboveTheCardCancels() = assertEquals(VoiceAim.Cancel, aim(399f))

    @Test fun cardRightAboveTheFingerNeedsALiftBeforeSending() {
        // 卡片贴着手指:稍微一动不算直接发送
        val close = Rect(20f, 400f, 1060f, 670f)
        assertEquals(VoiceAim.Fill, aim(660f, close))
        assertEquals(VoiceAim.Send, aim(640f, close))
    }

    @Test fun beforeTheCardIsPlacedOnlyTheLiftCounts() {
        assertEquals(VoiceAim.Fill, aim(620f, Rect.Zero))
        assertEquals(VoiceAim.Cancel, aim(590f, Rect.Zero))
    }
}

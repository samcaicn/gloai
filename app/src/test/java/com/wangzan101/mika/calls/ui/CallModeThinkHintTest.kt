package com.wangzan101.mika.calls.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.voice.model.ThinkModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallModeThinkHintTest {
    @Test
    fun choosingTheLightModelSaysItFallsBackToTheMainModel() {
        assertEquals(R.string.call_mode_think_light_note, thinkFallbackHint(ThinkModel.Light))
    }

    @Test
    fun theMainModelNeedsNoHint() {
        assertNull(thinkFallbackHint(ThinkModel.Main))
    }
}

package com.wangzan101.mika.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class McpLogosTest {
    private val github = """{"formatVersion":1,"logos":{"github":{"d":"M0 0 L24 0 L24 24 Z","color":"181717"}}}"""

    @Test fun parsesAndBuildsVector() {
        val logos = McpLogos.parse(github)
        val v = logos.logo("github")
        assertNotNull(v)
        assertEquals(24.dp, v!!.defaultWidth)
        assertEquals(24f, v.viewportWidth, 0f)
        assertNull(logos.logo("unknown"))
    }

    @Test fun skipsMalformedEntries() {
        val text = """{"formatVersion":1,"logos":{
            "emptyD":{"d":"","color":"181717"},
            "badColor":{"d":"M0 0","color":"zzz"},
            "ok":{"d":"M0 0","color":"181717"}
        }}"""
        val logos = McpLogos.parse(text)
        assertNull(logos.logo("emptyD"))
        assertNull(logos.logo("badColor"))
        assertNotNull(logos.logo("ok"))
    }

    @Test fun garbageOrEmptyIsEmpty() {
        assertNull(McpLogos.parse("not json").logo("x"))
        assertNull(McpLogos.parse("""{"formatVersion":1,"logos":{}}""").logo("x"))
    }
}

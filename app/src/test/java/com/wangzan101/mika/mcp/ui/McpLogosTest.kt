package com.wangzan101.mika.mcp.ui

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.VectorPath
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

    @Test fun multiPathEvenOddEntryKeepsEveryPathAndRule() {
        val text = """{"formatVersion":2,"logos":{"tc":{"paths":["M0 0 L24 0 L24 24 Z","M2 2 L4 2 L4 4 Z"],"color":"006EFF","evenOdd":true}}}"""
        val v = McpLogos.parse(text).logo("tc")!!
        val paths = (0 until v.root.size).map { v.root[it] as VectorPath }
        assertEquals(2, paths.size)
        paths.forEach { assertEquals(PathFillType.EvenOdd, it.pathFillType) }
    }

    @Test fun tileFallsBackToDefaultAndHonorsBrightBrandTile() {
        val text = """{"formatVersion":2,"logos":{
            "plain":{"paths":["M0 0"],"color":"181717"},
            "bright":{"paths":["M0 0"],"color":"1A1A1A","tile":"FFE01B"},
            "badTile":{"paths":["M0 0"],"color":"1A1A1A","tile":"nope"}
        }}"""
        val logos = McpLogos.parse(text)
        assertEquals(McpLogos.DefaultTile, logos.tile("plain"))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFFE01B), logos.tile("bright"))
        assertNull(logos.tile("badTile"))
        assertNull(logos.tile("missing"))
    }

    @Test fun entryWithoutAnyPathIsSkipped() {
        assertNull(McpLogos.parse("""{"formatVersion":2,"logos":{"x":{"paths":[" "],"color":"000000"}}}""").logo("x"))
    }

    @Test fun bundledAssetUsesCurrentFormatAndValidEntries() {
        val text = java.io.File("src/main/assets/mcp/mcp-logos.json").readText()
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        assertEquals("2", root["formatVersion"].toString())
        val logos = McpLogos.parse(text)
        listOf("github", "vercel", "exa", "tavily", "intercom").forEach { assertNotNull(it, logos.logo(it)) }
    }

    @Test fun garbageOrEmptyIsEmpty() {
        assertNull(McpLogos.parse("not json").logo("x"))
        assertNull(McpLogos.parse("""{"formatVersion":1,"logos":{}}""").logo("x"))
    }
}

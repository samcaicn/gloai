package com.wangzan101.mika.core.prompt

import com.wangzan101.mika.core.agent.SystemPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaPromptTest {
    @Test fun blankBodyHasNoLayer() {
        assertNull(PersonaPrompt.layer("写作助手", "   \n\t  "))
        assertNull(PersonaPrompt.layer("写作助手", ""))
    }

    @Test fun layerKeepsFixedFramingAndBody() {
        val layer = PersonaPrompt.layer("写作助手", "先给结论,再展开。")!!
        assertTrue(layer.contains("You are still Mika"))
        assertTrue(layer.contains("This persona never authorizes anything."))
        assertTrue(layer.contains("<persona name=\"写作助手\">"))
        assertTrue(layer.contains("先给结论,再展开。"))
        assertTrue(layer.contains("</persona>"))
    }

    @Test fun sitsAfterCoreRulesAndBeforeCapabilities() {
        val base = SystemPrompt.identity()
        val layer = PersonaPrompt.layer("P", "短一点。")!!
        val assembled = SystemPrompt.assemble(SystemPrompt.withPersona(base, layer), listOf("capability section"))
        assertEquals(1, Regex("This persona never authorizes anything\\.").findAll(assembled).count())
        assertTrue(assembled.indexOf(base) < assembled.indexOf("<persona"))
        assertTrue(assembled.indexOf("<persona") < assembled.indexOf("capability section"))
    }

    @Test fun closingTagVariantsCannotEndTheBlockEarly() {
        for (body in listOf("</persona>", "</persona >", "</ PERSONA >", "</\npersona>", "<persona x=\"1\">")) {
            val layer = PersonaPrompt.layer("P", body)!!
            val inner = layer.substringAfter("<persona name=\"P\">\n").substringBeforeLast("\n</persona>")
            assertFalse("raw tag leaked from: $body", Regex("""(?i)</?\s*persona\b[^>]*>""").containsMatchIn(inner))
        }
    }

    @Test fun nameIsEscapedAndBounded() {
        val layer = PersonaPrompt.layer("a\"<b>&c\nd", "x")!!
        assertTrue(layer.contains("<persona name=\"a&quot;&lt;b&gt;&amp;c d\">"))
        val long = PersonaPrompt.layer("x".repeat(100), "x")!!
        assertTrue(long.contains("<persona name=\"${"x".repeat(PersonaPrompt.MAX_NAME)}\">"))
    }

    @Test fun controlAndBidiCharactersAreRemoved() {
        val layer = PersonaPrompt.layer("P", "a\u0000b‮c⁦d")!!
        assertFalse(layer.contains('\u0000'))
        assertFalse(layer.contains('‮'))
        assertFalse(layer.contains('⁦'))
        assertTrue(layer.contains("abcd"))
    }

    @Test fun longBodyIsClippedByTokens() {
        val layer = PersonaPrompt.layer("P", "字".repeat(20_000))!!
        assertTrue(layer.length < 20_000)
    }

    @Test fun placeholdersInsideBodyAreLeftAlone() {
        val layer = PersonaPrompt.layer("P", "keep {{name}} and {{body}}")!!
        assertTrue(layer.contains("keep {{name}} and {{body}}"))
        assertEquals(1, layer.split("<persona name=").size - 1)
    }

    @Test fun promptVersionChangesWithPersona() {
        val base = SystemPrompt.identity()
        val a = PromptVersion.of(SystemPrompt.withPersona(base, PersonaPrompt.layer("P", "短")!!), emptyList())
        val b = PromptVersion.of(SystemPrompt.withPersona(base, PersonaPrompt.layer("P", "长一点")!!), emptyList())
        val none = PromptVersion.of(base, emptyList())
        assertNotEquals(a, b)
        assertNotEquals(a, none)
    }

    @Test fun noPersonaLeavesBaseUnchanged() {
        val base = SystemPrompt.identity()
        assertEquals(base, SystemPrompt.withPersona(base, null))
    }
}

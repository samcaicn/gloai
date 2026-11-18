package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonaStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(dir: File = tmp.root) = PersonaStore(dir)

    @Test fun addPersistsAcrossInstances() {
        val dir = tmp.newFolder("p")
        val a = store(dir)
        val p = a.add("写作助手", "先列提纲", "先给结论，再展开。").getOrThrow()
        assertEquals(listOf(p), a.personas.value)
        assertEquals(listOf(p), store(dir).personas.value)
        assertTrue(File(dir, "personas.json").readText().contains("\"version\": 1"))
    }

    @Test fun updateBumpsRevisionAndKeepsCreatedAt() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val p = s.add("P", "", "a").getOrThrow()
        val next = s.update(p.copy(name = "Q", prompt = "b")).getOrThrow()
        assertEquals(p.createdAt, next.createdAt)
        assertEquals(p.revision + 1, next.revision)
        assertEquals("Q", store(dir).get(p.id)?.name)
    }

    @Test fun duplicateGetsNewIdAndRevision() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val p = s.add("P", "", "a").getOrThrow()
        val copy = s.duplicate(p.id).getOrThrow()
        assertNotEquals(p.id, copy.id)
        assertEquals(1, copy.revision)
        assertFalse(copy.builtin)
        assertEquals(2, store(dir).personas.value.size)
    }

    @Test fun deleteRemovesOnlyThatPersona() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val a = s.add("A", "", "a").getOrThrow()
        val b = s.add("B", "", "b").getOrThrow()
        assertTrue(s.delete(a.id))
        assertNull(s.get(a.id))
        assertEquals(listOf(b), store(dir).personas.value)
        assertFalse(s.delete("missing"))
    }

    @Test fun invalidInputIsRejected() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        assertTrue(s.add("", "", "a").isFailure)
        assertTrue(s.add("x".repeat(PersonaPromptLimit.NAME + 1), "", "a").isFailure)
        assertTrue(s.add("P", "x".repeat(PersonaStore.MAX_DESCRIPTION + 1), "a").isFailure)
        assertTrue(s.add("P", "", "x".repeat(PersonaPromptLimit.CHARS + 1)).isFailure)
        assertTrue(s.personas.value.isEmpty())
    }

    @Test fun corruptFileIsSetAside() {
        val dir = tmp.newFolder("p")
        File(dir, "personas.json").writeText("{not json")
        val s = store(dir)
        assertTrue(s.personas.value.isEmpty())
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("personas.json.bad-") })
    }

    @Test fun emptyPromptIsAllowed() {
        val dir = tmp.newFolder("p")
        val p = store(dir).add("P", "", "").getOrThrow()
        assertEquals("", p.prompt)
    }

    @Test fun idsAreValidated() {
        assertTrue(PersonaStore.isValidId("abc-123_X"))
        assertFalse(PersonaStore.isValidId("../escape"))
        assertFalse(PersonaStore.isValidId(""))
        assertFalse(PersonaStore.isValidId(null))
    }

    /** 测试里用到的上限,和 PersonaPrompt 的常量对上;改名时只改这里一处。 */
    private object PersonaPromptLimit {
        const val NAME = com.wangzan101.mika.core.prompt.PersonaPrompt.MAX_NAME
        const val CHARS = com.wangzan101.mika.core.prompt.PersonaPrompt.MAX_PROMPT_CHARS
    }
}

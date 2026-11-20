package com.wangzan101.mika.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun createRenameDeleteAndReload() {
        val dir = tmp.newFolder("projects")
        var now = 100L
        val store = ProjectStore(dir, clock = { now })

        val a = store.create("  数据整理  ").getOrThrow()
        assertEquals("数据整理", a.name)
        assertEquals(100L, a.createdAt)
        assertEquals(100L, a.updatedAt)

        now = 250L
        val b = store.create("周报生成").getOrThrow()
        assertNotEquals(a.id, b.id)

        now = 400L
        val renamed = store.rename(a.id, "  清洗流水线  ").getOrThrow()
        assertEquals("清洗流水线", renamed.name)
        assertEquals(100L, renamed.createdAt)
        assertEquals(400L, renamed.updatedAt)

        val reloaded = ProjectStore(dir)
        assertEquals(listOf(renamed, b), reloaded.projects.value)
        assertEquals(renamed, reloaded.get(a.id))
        assertTrue(File(dir, "projects-v1.json").readText().contains("\"version\": 1"))

        assertTrue(reloaded.delete(a.id))
        assertNull(reloaded.get(a.id))
        assertEquals(listOf(b), ProjectStore(dir).projects.value)
        assertFalse(reloaded.delete("missing"))
    }

    @Test fun invalidInputsAreRejected() {
        val dir = tmp.newFolder("projects")
        val store = ProjectStore(dir)
        assertTrue(store.create("").isFailure)
        assertTrue(store.create("   ").isFailure)
        assertTrue(store.create("x".repeat(ProjectStore.MAX_NAME + 1)).isFailure)
        assertTrue(store.projects.value.isEmpty())

        val p = store.create("有效").getOrThrow()
        assertTrue(store.rename(p.id, "   ").isFailure)
        assertTrue(store.rename(p.id, "x".repeat(ProjectStore.MAX_NAME + 1)).isFailure)
        assertTrue(store.rename("missing", "新名字").isFailure)
        assertTrue(store.rename("../bad", "新名字").isFailure)
        assertEquals("有效", store.get(p.id)?.name)
    }

    @Test fun corruptFileIsSetAsideNotOverwritten() {
        val dir = tmp.newFolder("projects")
        File(dir, "projects-v1.json").writeText("{not json")
        val store = ProjectStore(dir, clock = { 777L })
        assertTrue(store.projects.value.isEmpty())
        assertTrue(File(dir, "projects-v1.json.bad-777").isFile)
    }

    @Test fun idsAreValidated() {
        assertTrue(ProjectStore.isValidId("abc-123_X"))
        assertFalse(ProjectStore.isValidId("../escape"))
        assertFalse(ProjectStore.isValidId("p:colon"))
        assertFalse(ProjectStore.isValidId(""))
        assertFalse(ProjectStore.isValidId(null))
    }
}

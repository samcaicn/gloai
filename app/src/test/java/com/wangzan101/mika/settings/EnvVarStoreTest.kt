package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnvVarStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundtripPersists() {
        val s = EnvVarStore(tmp.root)
        s.set("MIKA_TRANSLATE_BASE", "https://x/chat/completions")
        val s2 = EnvVarStore(tmp.root)
        assertEquals("https://x/chat/completions", s2.all()["MIKA_TRANSLATE_BASE"])
    }

    @Test fun removeAndClear() {
        val s = EnvVarStore(tmp.root)
        s.set("A", "1")
        s.set("B", "2")
        s.remove("A")
        assertFalse(s.all().containsKey("A"))
        assertTrue(s.all().containsKey("B"))
        s.clear()
        assertTrue(s.all().isEmpty())
    }

    @Test fun emptyValueRemovesKey() {
        val s = EnvVarStore(tmp.root)
        s.set("A", "1")
        s.set("A", "")
        assertFalse(s.all().containsKey("A"))
    }
}

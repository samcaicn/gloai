package com.wangzan101.mika.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateMirrorStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, UpdateMirrorStore.FILE)

    private fun store(): UpdateMirrorStore {
        var i = 0
        return UpdateMirrorStore(file()) { "id-${i++}" }
    }

    private fun loaded(store: UpdateMirrorStore) = store.apply { ensureLoaded() }

    @Test fun missingFileIsSeededWithPresetsOnce() {
        val store = loaded(store())
        assertEquals(UpdateMirrors.PRESETS.map { it.prefix }, store.mirrors.value.map { it.prefix })
        assertFalse(store.enabled.value)
        assertTrue(file().isFile)

        // 用户改过之后再次启动,不会被预设覆盖。
        store.delete(store.mirrors.value.first().id)
        val again = loaded(UpdateMirrorStore(file()))
        assertEquals(UpdateMirrors.PRESETS.size - 1, again.mirrors.value.size)
    }

    @Test fun addNormalizesPrefixAndFallsBackToHostForName() {
        val store = loaded(store())
        assertNull(store.add("", "https://mirror.example.test"))
        val added = store.mirrors.value.last()
        assertEquals("https://mirror.example.test/", added.prefix)
        assertEquals("mirror.example.test", added.name)

        assertNull(store.add("  My mirror  ", "https://mirror.example.test/sub/"))
        assertEquals("My mirror", store.mirrors.value.last().name)
        assertEquals("https://mirror.example.test/sub/", store.mirrors.value.last().prefix)
    }

    @Test fun rejectsUnsafeOrDuplicateMirrors() {
        val store = loaded(store())
        for (bad in listOf(
            "http://mirror.example.test/",
            "https://user:pass@mirror.example.test/",
            "https://mirror.example.test/?token=1",
            "https://mirror.example.test/#x",
            "not a url",
            "",
        )) {
            assertEquals(bad, UpdateMirrorError.InvalidPrefix, store.add("", bad))
        }
        assertEquals(UpdateMirrorError.InvalidName, store.add("x".repeat(UpdateMirrors.NAME_MAX + 1), "https://m.example.test/"))
        assertEquals(UpdateMirrorError.Duplicate, store.add("", "https://ghfast.top"))
        assertEquals(UpdateMirrors.PRESETS.size, store.mirrors.value.size)
        assertFalse(file().readText().contains("m.example.test"))
    }

    @Test fun stopsAtTheMaximum() {
        var i = 0
        val store = loaded(UpdateMirrorStore(file(), { "id-${i++}" }))
        var n = 0
        while (store.mirrors.value.size < UpdateMirrors.MAX) {
            assertNull(store.add("", "https://m${n++}.example.test/"))
        }
        assertEquals(UpdateMirrorError.TooMany, store.add("", "https://extra.example.test/"))
        assertEquals(UpdateMirrors.MAX, store.mirrors.value.size)
    }

    @Test fun updateDeleteAndReorderArePersisted() {
        val store = loaded(store())
        val first = store.mirrors.value[1]
        assertNull(store.update(first.id, "Renamed", "https://changed.example.test/"))
        assertEquals("Renamed", store.mirrors.value[1].name)
        assertEquals("https://changed.example.test/", store.mirrors.value[1].prefix)
        assertEquals(UpdateMirrorError.Duplicate, store.update(first.id, "x", "https://gh-proxy.com/"))

        val reordered = store.mirrors.value.map { it.id }.reversed()
        assertNull(store.reorder(reordered))
        assertEquals(reordered, store.mirrors.value.map { it.id })
        assertEquals(UpdateMirrorError.SaveFailed, store.reorder(reordered.dropLast(1)))

        assertNull(store.delete(reordered.first()))
        assertNull(store.delete("missing"))
        assertEquals(reordered.drop(1), store.mirrors.value.map { it.id })

        val again = loaded(UpdateMirrorStore(file()))
        assertEquals(store.mirrors.value, again.mirrors.value)
    }

    @Test fun enabledSwitchIsPersistedAndInSnapshot() {
        val store = loaded(store())
        assertNull(store.setEnabled(true))
        assertTrue(store.snapshot().enabled)
        assertEquals(store.mirrors.value, store.snapshot().mirrors)
        assertTrue(loaded(UpdateMirrorStore(file())).enabled.value)
    }

    @Test fun readsExistingFilesWithInvalidEntriesAndPersistsEmptyList() {
        file().writeText(
            """{"enabled":true,"mirrors":[
                {"id":"1","name":"One","prefix":"https://one.example.test"},
                {"id":"1","name":"Duplicate","prefix":"https://two.example.test/"},
                {"id":"2","name":"Bad","prefix":"http://bad.example.test/"},
                {"id":3,"name":"Bad","prefix":"https://bad.example.test/"},
                null
            ]}""".trimIndent(),
        )
        val store = loaded(store())
        assertEquals(listOf(UpdateMirror("1", "One", "https://one.example.test/")), store.mirrors.value)
        assertTrue(store.enabled.value)
        assertNull(store.delete("1"))
        val again = loaded(UpdateMirrorStore(file()))
        assertTrue(again.enabled.value)
        assertTrue(again.mirrors.value.isEmpty())
    }

    @Test fun corruptFileIsNotOverwrittenUntilUserResets() {
        file().parentFile.mkdirs()
        file().writeText("{not json")
        val store = loaded(store())
        assertTrue(store.unreadable.value)
        assertTrue(store.mirrors.value.isEmpty())
        assertEquals(UpdateMirrorError.Unreadable, store.add("", "https://m.example.test/"))
        assertEquals(UpdateMirrorError.Unreadable, store.setEnabled(true))
        assertEquals("{not json", file().readText())

        assertNull(store.resetDefaults())
        assertFalse(store.unreadable.value)
        assertEquals(UpdateMirrors.PRESETS.map { it.prefix }, store.mirrors.value.map { it.prefix })
        assertFalse(file().readText().contains("not json"))
    }

    @Test fun candidatesKeepUserOrderAndAlwaysEndWithOfficial() {
        val official = "https://github.com/wangzan101/Mika/releases/download/v0.2.0/Mika.apk"
        val mirrors = listOf(
            UpdateMirror("1", "One", "https://one.example.test/"),
            UpdateMirror("2", "Two", "https://two.example.test/"),
        )
        assertEquals(listOf(official), UpdateMirrors.candidates(official, MirrorSettings()))
        assertEquals(listOf(official), UpdateMirrors.candidates(official, MirrorSettings(enabled = true)))
        assertEquals(
            listOf("https://one.example.test/$official", "https://two.example.test/$official", official),
            UpdateMirrors.candidates(official, MirrorSettings(enabled = true, mirrors = mirrors)),
        )
    }

    @Test fun prefixNormalization() {
        assertEquals("https://m.example.test/", UpdateMirrors.normalizePrefix("  https://m.example.test  "))
        assertEquals("https://m.example.test/sub/", UpdateMirrors.normalizePrefix("https://m.example.test/sub"))
        assertEquals("https://m.example.test:8443/", UpdateMirrors.normalizePrefix("https://m.example.test:8443"))
        assertNull(UpdateMirrors.normalizePrefix("http://m.example.test/"))
        assertNull(UpdateMirrors.normalizePrefix("https://m.example.test/?q=1"))
        assertEquals("m.example.test", UpdateMirrors.host("https://m.example.test/"))
    }
}

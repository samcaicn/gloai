package com.wangzan101.mika.settings

import com.wangzan101.mika.task.DeviceImportRecord
import com.wangzan101.mika.task.DeviceImportStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceImportStoreTest {
    @Test fun recordsSurviveStoreRecreationAndAreScopedByConversation() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val store = DeviceImportStore(prefs)
        val first = DeviceImportRecord("one", "chat", "/sdcard/a", "/workspace/inbox/a", 3, 100, 42, 12)
        val second = DeviceImportRecord("two", "other", "/sdcard/b", "/workspace/inbox/b", 0, 101, null, null)
        store.add(first)
        DeviceImportStore(prefs).add(second)
        assertEquals(listOf(first), DeviceImportStore(prefs).list("chat"))
        assertEquals(listOf(second), DeviceImportStore(prefs).list("other"))
        store.removeConversation("chat")
        assertEquals(listOf(second), DeviceImportStore(prefs).allRecords())
    }

    @Test fun listByWorkspaceGroupsSharedProjectImportsWhilePreservingLegacyDefault() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val store = DeviceImportStore(prefs)
        val c1 = DeviceImportRecord("one", "c1", "/sdcard/a", "/workspace/inbox/a", 3, 100, 1, 2, workspaceId = "p:proj-1")
        val c2 = DeviceImportRecord("two", "c2", "/sdcard/b", "/workspace/inbox/b", 4, 101, 3, 4, workspaceId = "p:proj-1")
        val solo = DeviceImportRecord("three", "c3", "/sdcard/c", "/workspace/inbox/c", 5, 102, 5, 6)
        store.add(c1)
        store.add(c2)
        store.add(solo)

        val reloaded = DeviceImportStore(prefs)
        assertEquals(listOf(c1, c2), reloaded.listByWorkspace("p:proj-1"))
        assertEquals(listOf(solo), reloaded.listByWorkspace("c3"))
        assertEquals("c3", reloaded.list("c3").single().workspaceId)

        reloaded.removeWorkspace("p:proj-1")
        assertEquals(listOf(solo), DeviceImportStore(prefs).allRecords())
    }

    @Test fun flowEmitsInitialAndCommittedRecords() = runBlocking {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val store = DeviceImportStore(prefs)
        val snapshots = async(start = CoroutineStart.UNDISPATCHED) { store.recordsFlow().take(2).toList() }
        val record = DeviceImportRecord("id", "chat", "/source", "/workspace/inbox/a", 1, 1, null, null)
        DeviceImportStore(prefs).add(record)
        assertEquals(listOf(emptyList<DeviceImportRecord>(), listOf(record)), snapshots.await())
        assertEquals(listOf(record), store.allRecords())
    }

    @Test fun failedCommitFailsClosed() {
        val base = FeatureFlagsCapsuleTest.MemoryPrefs()
        val prefs = object : android.content.SharedPreferences by base {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = base.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean { editor.commit(); return false }
                }
            }
        }
        val store = DeviceImportStore(prefs)
        val record = DeviceImportRecord("id", "chat", "/source", "/workspace/inbox/a", 1, 1, null, null)
        assertThrows(IllegalStateException::class.java) { store.add(record) }
        assertThrows(IllegalStateException::class.java) { DeviceImportStore(prefs).list("chat") }
    }
}

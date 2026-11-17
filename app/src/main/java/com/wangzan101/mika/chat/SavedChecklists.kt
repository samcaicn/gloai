package com.wangzan101.mika.chat

import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class ChecklistSource(val conversationId: String, val messageKey: String, val text: String)

@Serializable
data class ChecklistRevision(
    val version: Int, val title: String, val body: String, val savedAt: Long,
    val source: ChecklistSource? = null,
)

@Serializable
data class SavedChecklist(val id: String, val revisions: List<ChecklistRevision>) {
    val current: ChecklistRevision get() = revisions.first()
}

enum class ChecklistError { Blank, TooLong, Full, ReadFailed, SaveFailed, Changed, Missing }

/** Local snapshots, encrypted with the app keystore in production. Disk commits precede publication. */
class SavedChecklistStore(
    private val file: File,
    private val cipher: KeyCipher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow<List<SavedChecklist>>(emptyList())
    private val _readError = MutableStateFlow(false)
    private val _loaded = MutableStateFlow(false)
    val items = _items.asStateFlow()
    val readError = _readError.asStateFlow()
    val loaded = _loaded.asStateFlow()

    @Synchronized fun load(): Boolean {
        if (_loaded.value) return true
        return try {
            val items = if (!file.exists()) emptyList() else
                json.decodeFromString<List<SavedChecklist>>(cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8))
            require(items.size <= MAX_LISTS && items.map { it.id }.distinct().size == items.size)
            require(items.all { it.id.isNotBlank() && it.revisions.isNotEmpty() && it.revisions.size <= MAX_REVISIONS &&
                it.revisions.all { r -> r.version > 0 && valid(r.title, r.body) == null &&
                    (r.source == null || r.source.text.length <= CONTENT_MAX) } &&
                it.revisions.zipWithNext().all { (a, b) -> a.version > b.version } })
            _items.value = items
            _readError.value = false
            _loaded.value = true
            true
        } catch (_: Exception) {
            _readError.value = true
            false // Never overwrite unreadable or temporarily unavailable storage.
        }
    }

    /** expectedVersion=0 creates a new list. Existing lists require the exact revision the editor opened. */
    @Synchronized fun save(
        id: String, expectedVersion: Int, title: String, body: String, source: ChecklistSource? = null,
    ): ChecklistError? {
        if (!load()) return ChecklistError.ReadFailed
        val t = title.trim(); val b = body.trim()
        valid(t, b)?.let { return it }
        if (id.isBlank() || (source != null && source.text.length > CONTENT_MAX)) return ChecklistError.TooLong
        val old = _items.value.find { it.id == id }
        if (old == null && expectedVersion != 0) return ChecklistError.Missing
        if ((old?.current?.version ?: 0) != expectedVersion) return ChecklistError.Changed
        if (old == null && _items.value.size >= MAX_LISTS) return ChecklistError.Full
        if (old?.current?.let { it.title == t && it.body == b && it.source == source } == true) return null
        val revision = ChecklistRevision(expectedVersion + 1, t, b, clock(), source)
        val item = SavedChecklist(id, (listOf(revision) + old?.revisions.orEmpty()).take(MAX_REVISIONS))
        return commit(listOf(item) + _items.value.filterNot { it.id == id })
    }

    @Synchronized fun delete(id: String, expectedVersion: Int): ChecklistError? {
        if (!load()) return ChecklistError.ReadFailed
        val item = _items.value.find { it.id == id } ?: return ChecklistError.Missing
        if (item.current.version != expectedVersion) return ChecklistError.Changed
        return commit(_items.value.filterNot { it.id == id })
    }

    private fun commit(items: List<SavedChecklist>): ChecklistError? {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return try {
            require(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            val bytes = cipher.encrypt(json.encodeToString(items).toByteArray(Charsets.UTF_8))
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            _items.value = items
            null
        } catch (_: Exception) {
            ChecklistError.SaveFailed
        } finally {
            tmp.delete()
        }
    }

    companion object {
        const val FILE = "saved-checklists-v1.bin"
        const val MAX_LISTS = 30
        const val MAX_REVISIONS = 10
        const val CONTENT_MAX = 12_000
        const val TITLE_MAX = 100
        private fun valid(title: String, body: String): ChecklistError? = when {
            title.isBlank() || body.isBlank() -> ChecklistError.Blank
            title.length > TITLE_MAX || body.length > CONTENT_MAX -> ChecklistError.TooLong
            else -> null
        }
    }
}

/** Exact line changes, not inferred semantic changes; preserves duplicate-line counts. */
data class ChecklistChanges(val added: List<String>, val removed: List<String>, val reordered: Boolean)
fun checklistChanges(before: String, after: String): ChecklistChanges {
    fun lines(text: String) = text.lines().filter { it.isNotBlank() }
    fun excess(first: List<String>, second: List<String>): List<String> {
        val counts = second.groupingBy { it }.eachCount().toMutableMap()
        return first.filter { line ->
            val count = counts[line] ?: 0
            if (count > 0) { counts[line] = count - 1; false } else true
        }
    }
    val a = lines(before); val b = lines(after)
    val added = excess(b, a); val removed = excess(a, b)
    return ChecklistChanges(added, removed, a != b && added.isEmpty() && removed.isEmpty())
}

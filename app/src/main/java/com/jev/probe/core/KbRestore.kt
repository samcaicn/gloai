package com.jev.probe.core

import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.kb.Note
import org.json.JSONArray
import org.json.JSONObject

/**
 * Merges snapshot knowledge-base data into the local store, gap-fill only.
 *
 * Match key is the business key rather than the random id: notes dedupe on
 * `title + content`, contacts on their name/alias set. IDs are regenerated per
 * install, so keying on them would silently duplicate every record. Filling gaps
 * rather than overwriting also means a restore after the user already edited
 * those notes locally cannot undo their work.
 */
object KbRestore {

    private const val TAG = "JEVASSIST"

    fun apply(context: android.content.Context, kb: JSONObject): Int {
        val store = KbStore.get(context.applicationContext)
        var written = 0

        val existingNotes = store.notes()
        val noteKeys = existingNotes
            .map { it.title.trim() + "\u0000" + it.content.trim() }.toHashSet()
        val seenNotes = HashSet<String>()
        kb.optJSONArray("notes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val note = o.toNote() ?: continue
                val k = note.title.trim() + "\u0000" + note.content.trim()
                if (!noteKeys.add(k) || !seenNotes.add(k)) continue
                // Fresh id: belongs to this install's key space.
                if (store.saveNote(note.copy(id = KbStore.newId()))) written++
            }
        }

        val existingContacts = store.contacts()
        val contactKeys = existingContacts
            .flatMap { listOf(it.name.trim()) + it.aliases.map { a -> a.trim() } }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
            .toHashSet()
        val seenContacts = HashSet<String>()
        kb.optJSONArray("contacts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val c = o.toContact() ?: continue
                val names = listOf(c.name.trim()) + c.aliases.map { it.trim() }
                val primary = names.firstOrNull { it.isNotEmpty() }?.lowercase() ?: continue
                if (!contactKeys.add(primary) || !seenContacts.add(primary)) continue
                if (store.saveContact(c.copy(id = KbStore.newId()))) written++
            }
        }

        android.util.Log.i(TAG, "kb restore wrote=$written")
        return written
    }

    private fun JSONObject.toNote(): Note? {
        val title = optString("title", "")
        val content = optString("content", "")
        if (title.isBlank() && content.isBlank()) return null
        return Note(
            id = optString("id", ""),
            title = title,
            content = content,
            tags = optJsonStringList("tags"),
            alwaysOn = optBoolean("alwaysOn", false),
            enabled = optBoolean("enabled", true),
            updatedAt = optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun JSONObject.toContact(): Contact? {
        val name = optString("name", "")
        if (name.isBlank()) return null
        return Contact(
            id = optString("id", ""),
            name = name,
            aliases = optJsonStringList("aliases"),
            apps = optJsonStringList("apps"),
            relationship = optString("relationship", ""),
            notes = optString("notes", ""),
            autoSummary = optString("autoSummary", ""),
            updatedAt = optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun JSONObject.optJsonStringList(key: String): List<String> {
        val arr = optJSONArray(key) as? JSONArray ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }
}

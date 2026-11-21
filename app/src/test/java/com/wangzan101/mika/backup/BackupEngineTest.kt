package com.wangzan101.mika.backup

import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator

/**
 * Export/import round-trip with two different keys: the export key stands for the old
 * install's KeyStore keys, the import key for the fresh install's. A real restore must
 * survive the keys changing, which is exactly what an uninstall does.
 */
class BackupEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val exportKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val importKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val exportCipher: KeyCipher = AesGcmKeyCipher { exportKey }
    private val importCipher: KeyCipher = AesGcmKeyCipher { importKey }

    private fun engine(filesDir: File, prefsDir: File, cipher: KeyCipher) =
        BackupEngine(filesDir, prefsDir, { cipher }, appVersionName = "0.1.43", appVersionCode = 48)

    private fun File.write(rel: String, bytes: ByteArray): File =
        File(this, rel).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }

    private fun File.write(rel: String, text: String): File = write(rel, text.toByteArray(Charsets.UTF_8))

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** A realistic source install: plain files, encrypted files, and encrypted prefs values. */
    private fun buildSource(filesDir: File, prefsDir: File) {
        filesDir.write("settings/providers.json", """{"services":[],"providers":[{"id":"c1"}]}""")
        filesDir.write("settings/personas.json", """{"version":1,"personas":[{"id":"p1","name":"Reviewer"}]}""")
        val keysJson = """{"p1":"${b64(exportCipher.encrypt("sk-secret-1".toByteArray()))}"}"""
        filesDir.write("settings/provider-keys.json", keysJson)
        filesDir.write("settings/mcp.json", """{"enabled":false}""")
        filesDir.write("settings/decision-model.bin", exportCipher.encrypt("""{"enabled":true,"apiKey":"jev-key"}""".toByteArray()))
        filesDir.write("conversations/abc.json", """{"messages":[]}""")
        filesDir.write("conversations/abc.title", "Hello")
        filesDir.write("runs/r1/run.json", """{"id":"r1"}""")
        filesDir.write("runs/r1/events.jsonl", """{"e":1}""" + "\n")
        filesDir.write("skills/personal/notes/SKILL.md", "# Notes\nDo things.")
        filesDir.write("skills/personal/notes/meta.json", """{"name":"notes"}""")
        filesDir.write("memory/memory.bin", exportCipher.encrypt("memory-plain".toByteArray()))
        filesDir.write("memory/profile.bin", exportCipher.encrypt("profile-plain".toByteArray()))
        filesDir.write("memory/task_memory/task_memory.bin", exportCipher.encrypt("task-plain".toByteArray()))
        filesDir.write("proactive/topics.bin", exportCipher.encrypt("topics-plain".toByteArray()))
        filesDir.write("proactive/contacts.bin", exportCipher.encrypt("contacts-plain".toByteArray()))
        filesDir.write("proactive/digest.bin", exportCipher.encrypt("digest-plain".toByteArray()))
        filesDir.write("proactive/journal.bin", exportCipher.encrypt("journal-plain".toByteArray()))
        filesDir.write("workflows-v1.bin", exportCipher.encrypt("""{"templates":[],"runs":[]}""".toByteArray()))
        filesDir.write("saved-checklists-v1.bin", exportCipher.encrypt("""[]""".toByteArray()))
        filesDir.write("sms-login-consent.txt", exportCipher.encrypt("""{"version":2,"enabled":false}""".toByteArray()))
        prefsDir.write(
            "web_search.xml",
            """<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><boolean name="enabled" value="true" /><string name="api_key_encrypted_you">${b64(exportCipher.encrypt("you-key".toByteArray()))}</string><string name="api_key_encrypted">${b64(exportCipher.encrypt("legacy-search-key".toByteArray()))}</string><string name="endpoint_you">https://you.example</string></map>""",
        )
        prefsDir.write(
            "web_fetch.xml",
            """<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><boolean name="enabled" value="true" /><string name="api_key_encrypted_tinyfish">${b64(exportCipher.encrypt("tf-key".toByteArray()))}</string></map>""",
        )
        prefsDir.write(
            "feature_flags.xml",
            """<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name="speech_doubao_credentials_encrypted">${b64(exportCipher.encrypt("""{"apiKey":"db-key"}""".toByteArray()))}</string><string name="voice_settings">{"enabled":true}</string><boolean name="personal_start" value="false" /></map>""",
        )
        prefsDir.write(
            "mika_call_history.xml",
            """<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name="encrypted_calls_v1">${b64(exportCipher.encrypt("""[{"id":"c1"}]""".toByteArray()))}</string></map>""",
        )
        prefsDir.write(
            "device_directory_hints.xml",
            """<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name="directories_v1">[]</string></map>""",
        )
        prefsDir.write(
            "plain.xml",
            """<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name="nickname">Vagif</string></map>""",
        )
    }

    private fun exportBytes(filesDir: File, prefsDir: File): ByteArray {
        val out = ByteArrayOutputStream()
        engine(filesDir, prefsDir, exportCipher).export(out)
        return out.toByteArray()
    }

    @Test fun roundTripRestoresProvidersChatsSkillsAndMemory() {
        val srcFiles = tmp.newFolder("src-files")
        val srcPrefs = tmp.newFolder("src-prefs")
        buildSource(srcFiles, srcPrefs)
        val zip = exportBytes(srcFiles, srcPrefs)

        val dstFiles = tmp.newFolder("dst-files")
        val dstPrefs = tmp.newFolder("dst-prefs")
        val dst = engine(dstFiles, dstPrefs, importCipher)
        val manifest = dst.import(ByteArrayInputStream(zip), replace = false)

        assertEquals(1, manifest.formatVersion)
        assertEquals("0.1.43", manifest.appVersionName)
        assertEquals(48L, manifest.appVersionCode)
        assertTrue(manifest.entries.any { it.path == "files/settings/providers.json" && !it.encrypted })
        assertTrue(manifest.entries.any { it.path == "files/settings/personas.json" && !it.encrypted })
        assertTrue(manifest.entries.any { it.path == "files/settings/provider-keys.json" && it.encrypted })
        assertTrue(manifest.entries.any { it.path == "files/settings/decision-model.bin" && it.encrypted && it.alias == "mika-decision-key" })
        assertTrue(manifest.entries.any { it.path == "files/memory/memory.bin" && it.encrypted && it.alias == "mika-memory" })
        assertTrue(manifest.entries.any { it.path == "files/proactive/topics.bin" && it.alias == "mika-proactive-topics" })
        assertTrue(manifest.entries.any { it.path == "files/workflows-v1.bin" && it.encrypted && it.alias == "mika-workflows" })
        assertTrue(manifest.entries.any { it.path == "files/saved-checklists-v1.bin" && it.encrypted && it.alias == "mika-saved-checklists" })
        assertTrue(manifest.entries.any { it.path == "files/sms-login-consent.txt" && it.encrypted && it.alias == "mika-sms-login-profiles" })
        assertTrue(manifest.skippedProviderKeys.isEmpty())
        assertTrue(manifest.skippedPrefs.isEmpty())

        assertEquals("""{"services":[],"providers":[{"id":"c1"}]}""", File(dstFiles, "settings/providers.json").readText())
        assertEquals("""{"version":1,"personas":[{"id":"p1","name":"Reviewer"}]}""", File(dstFiles, "settings/personas.json").readText())
        // Provider keys were decrypted on export and re-encrypted with the new install's key.
        val keysText = File(dstFiles, "settings/provider-keys.json").readText()
        val encoded = keysText.substringAfter("\"p1\":\"").substringBefore("\"")
        assertEquals("sk-secret-1", String(importCipher.decrypt(Base64.getDecoder().decode(encoded))))
        assertNotEquals(exportCipher, importCipher)

        assertEquals("""{"messages":[]}""", File(dstFiles, "conversations/abc.json").readText())
        assertEquals("Hello", File(dstFiles, "conversations/abc.title").readText())
        assertEquals("""{"id":"r1"}""", File(dstFiles, "runs/r1/run.json").readText())
        assertEquals("# Notes\nDo things.", File(dstFiles, "skills/personal/notes/SKILL.md").readText())
        assertEquals("""{"enabled":true,"apiKey":"jev-key"}""", String(importCipher.decrypt(File(dstFiles, "settings/decision-model.bin").readBytes())))
        assertEquals("memory-plain", String(importCipher.decrypt(File(dstFiles, "memory/memory.bin").readBytes())))
        assertEquals("profile-plain", String(importCipher.decrypt(File(dstFiles, "memory/profile.bin").readBytes())))
        assertEquals("task-plain", String(importCipher.decrypt(File(dstFiles, "memory/task_memory/task_memory.bin").readBytes())))
        assertEquals("topics-plain", String(importCipher.decrypt(File(dstFiles, "proactive/topics.bin").readBytes())))
        assertEquals("contacts-plain", String(importCipher.decrypt(File(dstFiles, "proactive/contacts.bin").readBytes())))
        assertEquals("""{"templates":[],"runs":[]}""", String(importCipher.decrypt(File(dstFiles, "workflows-v1.bin").readBytes())))
        assertEquals("""[]""", String(importCipher.decrypt(File(dstFiles, "saved-checklists-v1.bin").readBytes())))
        assertEquals("""{"version":2,"enabled":false}""", String(importCipher.decrypt(File(dstFiles, "sms-login-consent.txt").readBytes())))

        // Prefs: encrypted values were decrypted on export and re-encrypted on import;
        // plain values survive untouched.
        val webSearch = File(dstPrefs, "web_search.xml").readText()
        assertTrue(webSearch.contains("api_key_encrypted_you"))
        val webKey = webSearch.substringAfter("api_key_encrypted_you\">").substringBefore("</string>")
        assertEquals("you-key", String(importCipher.decrypt(Base64.getDecoder().decode(webKey))))
        val legacyWebKey = webSearch.substringAfter("name=\"api_key_encrypted\">").substringBefore("</string>")
        assertEquals("legacy-search-key", String(importCipher.decrypt(Base64.getDecoder().decode(legacyWebKey))))
        assertTrue(webSearch.contains("endpoint_you\">https://you.example</string>"))
        assertTrue(webSearch.contains("name=\"enabled\" value=\"true\""))

        val webFetch = File(dstPrefs, "web_fetch.xml").readText()
        val fetchKey = webFetch.substringAfter("api_key_encrypted_tinyfish\">").substringBefore("</string>")
        assertEquals("tf-key", String(importCipher.decrypt(Base64.getDecoder().decode(fetchKey))))

        val flags = File(dstPrefs, "feature_flags.xml").readText()
        val doubao = flags.substringAfter("speech_doubao_credentials_encrypted\">").substringBefore("</string>")
        assertEquals("""{"apiKey":"db-key"}""", String(importCipher.decrypt(Base64.getDecoder().decode(doubao))))
        assertTrue(flags.contains("""voice_settings">{"enabled":true}</string>"""))

        val callHistoryPrefs = File(dstPrefs, "mika_call_history.xml").readText()
        val callHistoryEnc = callHistoryPrefs.substringAfter("encrypted_calls_v1\">").substringBefore("</string>")
        assertEquals("""[{"id":"c1"}]""", String(importCipher.decrypt(Base64.getDecoder().decode(callHistoryEnc))))

        assertTrue(File(dstPrefs, "device_directory_hints.xml").readText().contains("directories_v1"))
        assertTrue(File(dstPrefs, "plain.xml").readText().contains("nickname\">Vagif</string>"))
    }

    @Test fun exportRefusesABackupTheImportCouldNotReadBack() {
        val srcFiles = tmp.newFolder("src-files")
        val srcPrefs = tmp.newFolder("src-prefs")
        buildSource(srcFiles, srcPrefs)
        val huge = File(srcFiles, "conversations/huge.json").also { it.parentFile.mkdirs() }
        RandomAccessFile(huge, "rw").use { it.setLength(BackupEngine.MAX_BACKUP_BYTES + 1) }

        val out = ByteArrayOutputStream()
        try {
            engine(srcFiles, srcPrefs, exportCipher).export(out)
            fail("an oversized export must be refused")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("too large"))
        }
        assertEquals(0, out.size())
    }

    @Test fun inspectDoesNotWriteAnything() {
        val srcFiles = tmp.newFolder("src-files")
        val srcPrefs = tmp.newFolder("src-prefs")
        buildSource(srcFiles, srcPrefs)
        val zip = exportBytes(srcFiles, srcPrefs)

        val dstFiles = tmp.newFolder("dst-files")
        val dstPrefs = tmp.newFolder("dst-prefs")
        val manifest = engine(dstFiles, dstPrefs, importCipher).inspect(ByteArrayInputStream(zip))
        assertEquals(1, manifest.formatVersion)
        assertFalse(dstFiles.exists() && dstFiles.listFiles()?.isNotEmpty() == true)
        assertTrue(dstPrefs.listFiles().isNullOrEmpty())
    }

    @Test fun corruptZipFailsAndChangesNothing() {
        val srcFiles = tmp.newFolder("src-files")
        val srcPrefs = tmp.newFolder("src-prefs")
        buildSource(srcFiles, srcPrefs)
        val zip = exportBytes(srcFiles, srcPrefs)
        val truncated = zip.copyOf(zip.size / 2)

        val dstFiles = tmp.newFolder("dst-files")
        val dstPrefs = tmp.newFolder("dst-prefs")
        try {
            engine(dstFiles, dstPrefs, importCipher).import(ByteArrayInputStream(truncated), replace = true)
            fail("truncated backup must be refused")
        } catch (_: BackupException) {
            // expected
        }
        assertTrue(dstFiles.listFiles().isNullOrEmpty())
        assertTrue(dstPrefs.listFiles().isNullOrEmpty())
    }

    @Test fun aFailedImportLeavesExistingFilesUntouched() {
        val srcFiles = tmp.newFolder("src-files")
        val srcPrefs = tmp.newFolder("src-prefs")
        buildSource(srcFiles, srcPrefs)
        val entries = readZip(exportBytes(srcFiles, srcPrefs)).toMutableMap()
        // Valid ZIP, valid manifest entry list, but the payload cannot be imported.
        entries["files/settings/provider-keys.json"] = "not-json".toByteArray(Charsets.UTF_8)
        val broken = writeZip(entries)

        val dstFiles = tmp.newFolder("dst-files")
        val dstPrefs = tmp.newFolder("dst-prefs")
        dstFiles.write("settings/mcp.json", "OLD")
        dstFiles.write("conversations/local-only.json", "OLD CHAT")
        dstPrefs.write("local-only.xml", "<map/>")
        try {
            engine(dstFiles, dstPrefs, importCipher).import(ByteArrayInputStream(broken), replace = true)
            fail("a corrupt payload must fail the import")
        } catch (_: BackupException) {
            // expected
        }

        assertEquals("OLD", File(dstFiles, "settings/mcp.json").readText())
        assertEquals("OLD CHAT", File(dstFiles, "conversations/local-only.json").readText())
        assertEquals("<map/>", File(dstPrefs, "local-only.xml").readText())
        assertTrue(
            (dstFiles.walkTopDown().filter { it.isFile && it.name.endsWith(".import-tmp") }.toList()).isEmpty(),
        )
    }

    @Test fun unknownFutureFormatVersionIsRefused() {
        val srcFiles = tmp.newFolder("src-files")
        val srcPrefs = tmp.newFolder("src-prefs")
        buildSource(srcFiles, srcPrefs)
        val zip = exportBytes(srcFiles, srcPrefs)

        // Rebuild the ZIP with the manifest's formatVersion bumped to 99.
        val entries = readZip(zip).toMutableMap()
        val manifest = entries.getValue("manifest.json").toString(Charsets.UTF_8)
            .replace("\"formatVersion\":1", "\"formatVersion\":99")
        entries["manifest.json"] = manifest.toByteArray(Charsets.UTF_8)
        val future = writeZip(entries)

        val dstFiles = tmp.newFolder("dst-files")
        val dstPrefs = tmp.newFolder("dst-prefs")
        try {
            engine(dstFiles, dstPrefs, importCipher).import(ByteArrayInputStream(future), replace = true)
            fail("future backup format must be refused")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("99"))
        }
        assertTrue(dstFiles.listFiles().isNullOrEmpty())
    }

    @Test fun existingDataIsRefusedWithoutReplace() {
        val srcFiles = tmp.newFolder("src-files")
        val srcPrefs = tmp.newFolder("src-prefs")
        buildSource(srcFiles, srcPrefs)
        val zip = exportBytes(srcFiles, srcPrefs)

        val dstFiles = tmp.newFolder("dst-files")
        val dstPrefs = tmp.newFolder("dst-prefs")
        dstFiles.write("conversations/old.json", "{}")
        try {
            engine(dstFiles, dstPrefs, importCipher).import(ByteArrayInputStream(zip), replace = false)
            fail("import over existing data must be refused without replace")
        } catch (_: BackupException) {
            // expected
        }
        assertEquals("{}", File(dstFiles, "conversations/old.json").readText())

        val manifest = engine(dstFiles, dstPrefs, importCipher).import(ByteArrayInputStream(zip), replace = true)
        assertEquals(1, manifest.formatVersion)
        assertEquals("""{"messages":[]}""", File(dstFiles, "conversations/abc.json").readText())
        assertFalse(File(dstFiles, "conversations/old.json").exists())
    }

    @Test fun replacingRemovesStaleSettingsButPreservesExcludedData() {
        val srcFiles = tmp.newFolder("replacement-source")
        val srcPrefs = tmp.newFolder("replacement-source-prefs")
        buildSource(srcFiles, srcPrefs)
        val zip = exportBytes(srcFiles, srcPrefs)
        val dstFiles = tmp.newFolder("replacement-target")
        val dstPrefs = tmp.newFolder("replacement-target-prefs")
        dstFiles.write("settings/stale.json", "old")
        dstFiles.write("conversations/old.json.title", "old title")
        dstFiles.write("sandbox/work/keep.txt", "workspace")
        dstFiles.write("library-attachments/keep.txt", "attachment")
        dstPrefs.write("stale.xml", "<map/>")

        engine(dstFiles, dstPrefs, importCipher).import(ByteArrayInputStream(zip), replace = true)

        assertFalse(File(dstFiles, "settings/stale.json").exists())
        assertFalse(File(dstFiles, "conversations/old.json.title").exists())
        assertFalse(File(dstPrefs, "stale.xml").exists())
        assertEquals("workspace", File(dstFiles, "sandbox/work/keep.txt").readText())
        assertEquals("attachment", File(dstFiles, "library-attachments/keep.txt").readText())
    }

    @Test fun undecryptableFileFailsExportLoudly() {
        val srcFiles = tmp.newFolder("src-files")
        val srcPrefs = tmp.newFolder("src-prefs")
        buildSource(srcFiles, srcPrefs)
        // memory.bin encrypted with a key the export cipher does not have: GCM auth must fail.
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val otherCipher: KeyCipher = AesGcmKeyCipher { otherKey }
        srcFiles.write("memory/memory.bin", otherCipher.encrypt("memory-plain".toByteArray()))
        try {
            exportBytes(srcFiles, srcPrefs)
            fail("export with an undecryptable file must fail")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("memory.bin"))
        }
    }

    @Test fun undecryptableProviderKeyIsSkippedNotFatal() {
        val srcFiles = tmp.newFolder("src-files")
        val srcPrefs = tmp.newFolder("src-prefs")
        buildSource(srcFiles, srcPrefs)
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val otherCipher: KeyCipher = AesGcmKeyCipher { otherKey }
        val keysJson = """{"p1":"${b64(otherCipher.encrypt("sk-secret-1".toByteArray()))}"}"""
        srcFiles.write("settings/provider-keys.json", keysJson)

        val out = ByteArrayOutputStream()
        val manifest = engine(srcFiles, srcPrefs, exportCipher).export(out)
        assertEquals(listOf("p1"), manifest.skippedProviderKeys)
        assertTrue(manifest.entries.any { it.path == "files/settings/provider-keys.json" })
    }

    private fun readZip(zip: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes()
                input.closeEntry()
            }
        }
        return entries
    }

    private fun writeZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

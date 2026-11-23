package com.wangzan101.mika.backup

import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.voice.model.VoiceCredentialBackend
import com.wangzan101.mika.voice.model.VoiceCredentialStore
import com.wangzan101.mika.voice.model.VoiceCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.KeyGenerator
import javax.xml.parsers.DocumentBuilderFactory

class VoiceCredentialBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Backend : VoiceCredentialBackend {
        val values = mutableMapOf<String, String>()
        override fun read(secretId: String) = values[secretId]
        override fun write(secretId: String, value: String): Boolean {
            values[secretId] = value
            return true
        }
        override fun delete(secretId: String) { values.remove(secretId) }
    }

    @Test fun ownedVoiceAccountsSurviveRestoreWithNewKeys() {
        val oldKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val newKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val oldCipher = AesGcmKeyCipher { oldKey }
        val newCipher = AesGcmKeyCipher { newKey }
        val source = Backend()
        val store = VoiceCredentialStore(source, oldCipher)
        store.save("api-account", VoiceCredentials(apiKey = "test-api-key"))
        store.save("app-account", VoiceCredentials(appId = "test-app-id", accessKey = "test-access-key"))
        val sourcePrefs = tmp.newFolder("source-prefs")
        val fileName = "mika_voice_model_accounts.xml"
        File(sourcePrefs, fileName).writeText(source.values.entries.joinToString("", "<map>", "</map>") {
            "<string name=\"voice_owned_${it.key}\">${it.value}</string>"
        })
        val bytes = ByteArrayOutputStream()
        val exported = BackupEngine(tmp.newFolder("source-files"), sourcePrefs, { alias ->
            assertEquals("mika-voice-model-accounts", alias)
            oldCipher
        }).export(bytes)
        val destinationPrefs = tmp.newFolder("destination-prefs")
        BackupEngine(tmp.newFolder("destination-files"), destinationPrefs, { alias ->
            assertEquals("mika-voice-model-accounts", alias)
            newCipher
        }).import(ByteArrayInputStream(bytes.toByteArray()), replace = false)

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(destinationPrefs, fileName))
        val entries = document.getElementsByTagName("string")
        val restored = Backend()
        for (i in 0 until entries.length) {
            val entry = entries.item(i)
            val id = entry.attributes.getNamedItem("name").nodeValue.removePrefix("voice_owned_")
            restored.write(id, entry.textContent)
        }
        val restoredStore = VoiceCredentialStore(restored, newCipher)
        assertEquals("test-api-key", restoredStore.read("api-account")?.apiKey)
        assertEquals("test-app-id", restoredStore.read("app-account")?.appId)
        assertEquals("test-access-key", restoredStore.read("app-account")?.accessKey)
        assertNotEquals(source.read("api-account"), restored.read("api-account"))
        assertEquals(mapOf(
            "voice_owned_api-account" to "mika-voice-model-accounts",
            "voice_owned_app-account" to "mika-voice-model-accounts",
        ), exported.decryptedPrefs[fileName])
    }
}

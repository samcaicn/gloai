package com.wangzan101.mika.backup

import com.wangzan101.mika.settings.KeyCipher
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * SharedPreferences values that are stored as base64 ciphertext.
 *
 * Everything else in shared_prefs is copied as-is. The keys here are the only ones whose
 * values are encrypted with a KeyStore key; copying them raw would leave the new install
 * unable to read them (the old KeyStore keys die with the old install).
 *
 * Values are base64 of [KeyCipher] output (12-byte IV + ciphertext + GCM tag), written with
 * either java.util.Base64 or android.util.Base64.NO_WRAP, which share the same alphabet.
 */
object BackupPrefs {
    sealed interface EncryptedPrefKey {
        val alias: String
        fun matches(key: String): Boolean

        data class Exact(val key: String, override val alias: String) : EncryptedPrefKey {
            override fun matches(key: String): Boolean = key == this.key
        }

        data class Prefix(val prefix: String, override val alias: String) : EncryptedPrefKey {
            override fun matches(key: String): Boolean = key.startsWith(prefix)
        }
    }

    /** prefs file name -> encrypted string keys in it. */
    val encryptedKeys: Map<String, List<EncryptedPrefKey>> = mapOf(
        "feature_flags.xml" to listOf(
            EncryptedPrefKey.Exact("speech_doubao_credentials_encrypted", "mika-speech-keys"),
            EncryptedPrefKey.Prefix("speech_bailian_key_encrypted_", "mika-speech-keys"),
        ),
        "web_search.xml" to listOf(
            EncryptedPrefKey.Prefix("api_key_encrypted_", "mika-web-search-key"),
            EncryptedPrefKey.Exact("api_key_encrypted", "mika-web-search-key"),
        ),
        "web_fetch.xml" to listOf(
            EncryptedPrefKey.Prefix("api_key_encrypted_", "mika-web-fetch-key"),
        ),
        "mika_call_settings.xml" to listOf(
            EncryptedPrefKey.Exact("volc_credentials_v1", "mika-volc-realtime-calls"),
        ),
        "mika_voice_model_accounts.xml" to listOf(
            EncryptedPrefKey.Prefix("voice_owned_", "mika-voice-model-accounts"),
        ),
        "mika_call_voice_profiles.xml" to listOf(
            EncryptedPrefKey.Exact("encrypted_profiles_v1", "mika-call-voice-profiles"),
        ),
        "mika_call_instructions.xml" to listOf(
            EncryptedPrefKey.Exact("encrypted_rules_v1", "mika-call-instructions"),
        ),
        "mika_call_history.xml" to listOf(
            EncryptedPrefKey.Exact("encrypted_calls_v1", "mika-call-history"),
        ),
        "task_recovery.xml" to listOf(
            EncryptedPrefKey.Exact("clip_backup", "mika-clip-backup"),
        ),
    )

    private fun aliasFor(fileName: String, key: String): String? =
        encryptedKeys[fileName]?.firstOrNull { it.matches(key) }?.alias

    /**
     * Export: decrypt every encrypted string value in [xml] (a SharedPreferences file).
     * Returns (transformed XML, key -> alias for values that were decrypted, keys that
     * could not be decrypted and were dropped). Non-string entries and unknown keys are
     * copied unchanged.
     */
    fun transformForExport(
        xml: ByteArray,
        fileName: String,
        cipherFor: (String) -> KeyCipher,
    ): Triple<ByteArray, Map<String, String>, List<String>> {
        val transformed = mutableMapOf<String, String>()
        val skipped = mutableListOf<String>()
        val out = transform(xml) { key, value ->
            val alias = aliasFor(fileName, key) ?: return@transform value
            val plain = runCatching {
                String(cipherFor(alias).decrypt(Base64.getDecoder().decode(value)), Charsets.UTF_8)
            }.getOrNull()
            if (plain == null) {
                skipped += key
                null // drop the entry: its ciphertext is useless on a new install
            } else {
                transformed[key] = alias
                plain
            }
        }
        return Triple(out, transformed, skipped)
    }

    /**
     * Import: re-encrypt every string value listed in [aliases] (key -> alias) with this
     * install's fresh KeyStore keys. Values are stored as base64 ciphertext, as before.
     */
    fun transformForImport(
        xml: ByteArray,
        fileName: String,
        aliases: Map<String, String>,
        cipherFor: (String) -> KeyCipher,
    ): ByteArray = transform(xml) { key, value ->
        val alias = aliases[key] ?: return@transform value
        Base64.getEncoder().encodeToString(cipherFor(alias).encrypt(value.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Parse a SharedPreferences XML document, apply [transform] to each direct `<string>`
     * child of `<map>`, and serialize it back. [transform] gets (key, current text) and
     * returns the new text, or null to drop the entry. Throws [BackupException] on bad XML.
     */
    private fun transform(xml: ByteArray, transform: (key: String, value: String) -> String?): ByteArray {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The file comes from the user's own backup, but never parse external entities.
            // Best effort: some Android parsers do not support this feature flag.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        val doc = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        } catch (e: Exception) {
            throw BackupException("SharedPreferences file is not valid XML: ${e.message}")
        }
        val root = doc.documentElement
        if (root == null || root.tagName != "map") throw BackupException("SharedPreferences file has no <map> root")
        val children = root.childNodes
        val toRemove = mutableListOf<Node>()
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE || node.nodeName != "string") continue
            val element = node as Element
            val key = element.getAttribute("name")
            val value = element.textContent ?: ""
            val replacement = transform(key, value)
            if (replacement == null) toRemove += node else element.textContent = replacement
        }
        toRemove.forEach { root.removeChild(it) }
        val out = ByteArrayOutputStream()
        try {
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
                setOutputProperty(OutputKeys.STANDALONE, "yes")
                setOutputProperty(OutputKeys.INDENT, "no")
            }.transform(DOMSource(doc), StreamResult(out))
        } catch (e: Exception) {
            throw BackupException("Could not write SharedPreferences file: ${e.message}")
        }
        return out.toByteArray()
    }
}

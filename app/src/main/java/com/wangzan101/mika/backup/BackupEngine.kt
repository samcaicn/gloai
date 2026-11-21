package com.wangzan101.mika.backup

import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A whole-data backup of Mika: a plain ZIP file, no password.
 *
 * Why this exists: secrets are AES-GCM encrypted with AndroidKeyStore keys, and uninstall
 * invalidates those keys, so raw files cannot be carried to a differently signed install.
 * Export runs while the old install is still installed, decrypts everything with its
 * KeyStore keys, and packs the plaintext into one ZIP. Import on the fresh install
 * re-encrypts every secret under the new install's fresh KeyStore keys.
 *
 * The ZIP contains all the user's secrets in plaintext. It must never be uploaded anywhere;
 * the UI warns the user to keep it private and delete it after importing.
 *
 * This class is pure JVM (no Android APIs) so unit tests can run it with a fake [KeyCipher].
 * The only Android-specific part is [create], which wires the real KeyStore ciphers.
 */
class BackupException(message: String) : Exception(message)

@Serializable
data class BackupEntryInfo(
    /** Path inside the ZIP: `files/<relative path>` or `shared_prefs/<name>`. */
    val path: String,
    /** True when the file was decrypted on export and must be re-encrypted on import. */
    val encrypted: Boolean,
    /** KeyStore alias used for the file, when [encrypted]. */
    val alias: String? = null,
)

@Serializable
data class BackupManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val appVersionName: String,
    val appVersionCode: Long,
    val exportedAtEpochMs: Long,
    val entries: List<BackupEntryInfo>,
    /** prefs file -> key -> KeyStore alias, for values decrypted on export and re-encrypted on import. */
    val decryptedPrefs: Map<String, Map<String, String>> = emptyMap(),
    /** Provider keys that could not be decrypted on export and were left out. */
    val skippedProviderKeys: List<String> = emptyList(),
    /** Prefs keys that could not be decrypted on export and were dropped. */
    val skippedPrefs: Map<String, List<String>> = emptyMap(),
    /** Directories and files deliberately not included (large or not transferable). */
    val excluded: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

class BackupEngine(
    private val filesDir: File,
    private val sharedPrefsDir: File,
    private val cipherFor: (String) -> KeyCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val appVersionName: String = "",
    private val appVersionCode: Long = 0,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Files under [filesDir] whose whole content is one [KeyCipher] ciphertext (IV + data). */
    private val encryptedFiles: Map<String, String> = mapOf(
        "settings/net-proxy.bin" to "mika-net-proxy",
        "settings/container-proxy.bin" to "mika-container-proxy",
        "settings/mcp-token.bin" to "mika-mcp-token",
        "settings/remote-relay.bin" to "mika-remote-relay",
        "settings/device-access.bin" to "mika-device-access",
        "settings/mcp-server-secrets.bin" to "mika-mcp-servers",
        "settings/decision-model.bin" to "mika-decision-key",
        "memory/memory.bin" to "mika-memory",
        "memory/profile.bin" to "mika-memory",
        "memory/task_memory/task_memory.bin" to "mika-memory",
        "proactive/topics.bin" to "mika-proactive-topics",
        "proactive/contacts.bin" to "mika-proactive-contacts",
        "proactive/digest.bin" to "mika-proactive-digest",
        "proactive/journal.bin" to "mika-proactive-journal",
        "call-history-v2.enc" to "mika-call-history",
        "wechat/account.enc" to "mika-wechat-account",
        "workflows-v1.bin" to "mika-workflows",
        "saved-checklists-v1.bin" to "mika-saved-checklists",
        "sms-login-consent.txt" to "mika-sms-login-profiles",
    )

    /** Directories under [filesDir] that are deliberately not backed up (large, regenerable). */
    private val excludedDirs = listOf("sandbox", "library-attachments")

    companion object {
        /** Largest backup the import will read into memory. */
        const val MAX_BACKUP_BYTES: Long = 128L * 1024 * 1024
        private const val MANIFEST_PATH = "manifest.json"
        private const val PROVIDER_KEYS_PATH = "settings/provider-keys.json"
        private const val PROVIDER_KEYS_ALIAS = "mika-provider-keys"

        fun create(context: android.content.Context): BackupEngine {
            val app = context.applicationContext
            return BackupEngine(
                filesDir = app.filesDir,
                sharedPrefsDir = File(app.applicationInfo.dataDir, "shared_prefs"),
                cipherFor = { alias -> com.wangzan101.mika.settings.KeystoreKeyCipher(alias) },
                appVersionName = com.wangzan101.mika.BuildConfig.VERSION_NAME,
                appVersionCode = com.wangzan101.mika.BuildConfig.VERSION_CODE.toLong(),
            )
        }
    }

    /** True when this install already has chats or provider settings (import would replace them). */
    fun hasExistingData(): Boolean {
        val conversations = File(filesDir, "conversations")
        if (conversations.isDirectory && conversations.listFiles()?.isNotEmpty() == true) return true
        return File(filesDir, "settings/providers.json").isFile
    }

    /**
     * Write a backup ZIP to [output] and return its manifest. The ZIP holds the decrypted
     * plaintext of every file; callers must not upload or log it. A file that cannot be
     * decrypted fails the whole export, except provider keys (see [BackupManifest.skippedProviderKeys]).
     */
    fun export(output: OutputStream): BackupManifest {
        val entries = mutableListOf<BackupEntryInfo>()
        val decryptedPrefs = mutableMapOf<String, Map<String, String>>()
        val skippedProviderKeys = mutableListOf<String>()
        val skippedPrefs = mutableMapOf<String, List<String>>()
        val payloads = mutableListOf<Pair<String, ByteArray>>()
        var totalBytes = 0L
        fun charge(bytes: Long, label: String) {
            totalBytes += bytes
            if (totalBytes > MAX_BACKUP_BYTES) {
                throw BackupException(
                    "Backup would be too large (over ${MAX_BACKUP_BYTES / (1024 * 1024)} MB): $label",
                )
            }
        }
        fun refuseOversize(file: File, label: String) {
            if (totalBytes + file.length() > MAX_BACKUP_BYTES) {
                throw BackupException(
                    "Backup would be too large (over ${MAX_BACKUP_BYTES / (1024 * 1024)} MB): $label",
                )
            }
        }

        collectFiles().forEach { (zipPath, file) ->
            val rel = zipPath.removePrefix("files/")
            refuseOversize(file, zipPath)
            val bytes = try {
                file.readBytes()
            } catch (e: Exception) {
                throw BackupException("Could not read ${file.name}: ${e.message}")
            }
            when {
                rel == PROVIDER_KEYS_PATH -> {
                    val (plain, skipped) = decryptProviderKeys(bytes)
                    skippedProviderKeys += skipped
                    payloads += zipPath to plain
                    charge(plain.size.toLong(), zipPath)
                    entries += BackupEntryInfo(zipPath, encrypted = true, alias = PROVIDER_KEYS_ALIAS)
                }
                encryptedFiles.containsKey(rel) -> {
                    val alias = encryptedFiles.getValue(rel)
                    val plain = try {
                        cipherFor(alias).decrypt(bytes)
                    } catch (_: Exception) {
                        throw BackupException("Could not decrypt $rel: the backup cannot be completed")
                    }
                    payloads += zipPath to plain
                    charge(plain.size.toLong(), zipPath)
                    entries += BackupEntryInfo(zipPath, encrypted = true, alias = alias)
                }
                else -> {
                    payloads += zipPath to bytes
                    charge(bytes.size.toLong(), zipPath)
                    entries += BackupEntryInfo(zipPath, encrypted = false)
                }
            }
        }

        sharedPrefsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val zipPath = "shared_prefs/${file.name}"
                refuseOversize(file, zipPath)
                val bytes = try {
                    file.readBytes()
                } catch (e: Exception) {
                    throw BackupException("Could not read ${file.name}: ${e.message}")
                }
                val (plain, transformed, skipped) = BackupPrefs.transformForExport(bytes, file.name, cipherFor)
                if (transformed.isNotEmpty()) decryptedPrefs[file.name] = transformed
                if (skipped.isNotEmpty()) skippedPrefs[file.name] = skipped
                payloads += zipPath to plain
                charge(plain.size.toLong(), zipPath)
                entries += BackupEntryInfo(zipPath, encrypted = transformed.isNotEmpty())
            }

        val manifest = BackupManifest(
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            exportedAtEpochMs = clock(),
            entries = entries,
            decryptedPrefs = decryptedPrefs,
            skippedProviderKeys = skippedProviderKeys,
            skippedPrefs = skippedPrefs,
            excluded = excludedDirs.map { "$it/ (large, not included)" } +
                listOf("noBackupFilesDir/adb/ (wireless-debugging key, must re-pair)"),
        )
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_PATH))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            payloads.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return manifest
    }

    /** Read and validate a backup without writing anything. Throws [BackupException] when invalid. */
    fun inspect(input: InputStream): BackupManifest = readBackup(input).first

    /**
     * Restore a backup. Validates the whole ZIP (manifest, version, entry list, paths) before
     * writing anything. When [replace] is false and this install already has data, refuses.
     * Returns the manifest of the restored backup.
     */
    fun import(input: InputStream, replace: Boolean): BackupManifest {
        val (manifest, payloads) = readBackup(input)
        if (!replace && hasExistingData()) {
            throw BackupException("This install already has chats and providers; import needs Replace all")
        }
        val byPath = payloads.toMap()
        // Validate all destination paths before staging, and snapshot obsolete files
        // before temporary imports appear in the tree. Excluded data stays outside
        // the replacement scope, just as it is outside the export scope.
        val targets = manifest.entries.associate { it.path to targetFile(it.path) }
        val obsolete = if (replace) {
            val retained = targets.values.toSet()
            val prefs = sharedPrefsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }.orEmpty()
            (collectFiles().map { it.second } + prefs).filter { it.canonicalFile !in retained }
        } else emptyList()
        // Stage every entry before replacing anything: a corrupt payload or a full
        // disk then fails with the install's existing data untouched.
        val staged = mutableListOf<Pair<File, File>>()
        try {
            manifest.entries.forEach { info ->
                val bytes = byPath[info.path]
                    ?: throw BackupException("Backup is missing ${info.path}")
                val target = targets.getValue(info.path)
                val out: ByteArray = when {
                    info.path.removePrefix("files/") == PROVIDER_KEYS_PATH -> {
                        val alias = info.alias ?: PROVIDER_KEYS_ALIAS
                        encryptProviderKeys(bytes, alias)
                    }
                    // Prefs files go before the generic encrypted branch: their entries carry
                    // no single alias (each decrypted key has its own, in manifest.decryptedPrefs).
                    info.path.startsWith("shared_prefs/") -> {
                        val fileName = info.path.removePrefix("shared_prefs/")
                        val aliases = manifest.decryptedPrefs[fileName].orEmpty()
                        if (aliases.isEmpty()) bytes
                        else BackupPrefs.transformForImport(bytes, fileName, aliases, cipherFor)
                    }
                    info.encrypted -> {
                        val alias = info.alias
                            ?: throw BackupException("Backup entry ${info.path} has no KeyStore alias")
                        try {
                            cipherFor(alias).encrypt(bytes)
                        } catch (e: Exception) {
                            throw BackupException("Could not encrypt ${info.path}: ${e.message}")
                        }
                    }
                    else -> bytes
                }
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.import-tmp")
                tmp.writeBytes(out)
                staged += tmp to target
            }
            staged.forEach { (tmp, target) ->
                if (target.exists() && !target.delete()) {
                    throw BackupException("Could not replace ${target.name}")
                }
                if (!tmp.renameTo(target)) throw BackupException("Could not write ${target.name}")
            }
            // Do not delete old-only data if validation, encryption or staging fails.
            obsolete.forEach { old ->
                if (old.exists() && !old.delete()) throw BackupException("Could not remove ${old.name}")
            }
        } catch (e: Exception) {
            staged.forEach { (tmp, _) -> tmp.delete() }
            if (e is BackupException) throw e
            throw BackupException("Import failed: ${e.message}")
        }
        return manifest
    }

    /** Files to include, as (ZIP path, file), in deterministic order. */
    private fun collectFiles(): List<Pair<String, File>> {
        if (!filesDir.isDirectory) return emptyList()
        return filesDir.walkTopDown()
            .onEnter { dir -> dir == filesDir || dir.name !in excludedDirs }
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { file -> "files/${file.relativeTo(filesDir).invariantSeparatorsPath}" to file }
            .sortedBy { it.first }
            .toList()
    }

    private fun targetFile(zipPath: String): File {
        val root = when {
            zipPath.startsWith("files/") -> filesDir
            zipPath.startsWith("shared_prefs/") -> sharedPrefsDir
            else -> throw BackupException("Backup entry has an unexpected path: $zipPath")
        }
        val rel = zipPath.substringAfter('/')
        val target = File(root, rel)
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
            throw BackupException("Backup entry escapes its directory: $zipPath")
        }
        return canonicalTarget
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "cannot write ${file.name}" }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw BackupException("Could not write ${file.name}: ${e.message}")
        }
    }

    /** provider-keys.json: JSON map providerId -> base64(ciphertext). Returns plaintext JSON + skipped ids. */
    private fun decryptProviderKeys(bytes: ByteArray): Pair<ByteArray, List<String>> {
        val root = try {
            json.decodeFromString<JsonObject>(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("provider-keys.json is not valid JSON: ${e.message}")
        }
        val cipher = cipherFor(PROVIDER_KEYS_ALIAS)
        val skipped = mutableListOf<String>()
        val plain = buildJsonObject {
            root.entries.forEach { (id, value) ->
                val decrypted = runCatching {
                    String(cipher.decrypt(Base64.getDecoder().decode(value.jsonPrimitive.content)), Charsets.UTF_8)
                }.getOrNull()
                if (decrypted == null) skipped += id else put(id, decrypted)
            }
        }
        return json.encodeToString(plain).toByteArray(Charsets.UTF_8) to skipped
    }

    /** Re-encrypt a plaintext provider-keys.json (as written by [decryptProviderKeys]) for this install. */
    private fun encryptProviderKeys(bytes: ByteArray, alias: String): ByteArray {
        val root = try {
            json.decodeFromString<JsonObject>(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("provider-keys.json in the backup is not valid JSON: ${e.message}")
        }
        val cipher = cipherFor(alias)
        val encrypted = buildJsonObject {
            root.entries.forEach { (id, value) ->
                val plain = value.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
                put(id, Base64.getEncoder().encodeToString(cipher.encrypt(plain)))
            }
        }
        return json.encodeToString(encrypted).toByteArray(Charsets.UTF_8)
    }

    /** Read a whole backup into memory and validate it. Returns (manifest, payloads). */
    private fun readBackup(input: InputStream): Pair<BackupManifest, List<Pair<String, ByteArray>>> {
        val payloads = mutableListOf<Pair<String, ByteArray>>()
        var total = 0L
        var manifestBytes: ByteArray? = null
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name.isBlank() || name.contains("..") || name.contains('\\') || name.startsWith("/") || name.endsWith("/")) {
                        throw BackupException("Backup has an unsafe entry name: $name")
                    }
                    val bytes = ByteArrayOutputStream().also { out -> zip.copyTo(out) }.toByteArray()
                    total += bytes.size
                    if (total > MAX_BACKUP_BYTES) throw BackupException("Backup is too large (over 128 MB)")
                    if (name == MANIFEST_PATH) manifestBytes = bytes else payloads += name to bytes
                    zip.closeEntry()
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("Could not read the backup file: ${e.message}")
        }
        val manifest = try {
            json.decodeFromString<BackupManifest>(String(manifestBytes ?: throw BackupException("Backup has no manifest.json"), Charsets.UTF_8))
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("Backup manifest is not valid: ${e.message}")
        }
        if (manifest.formatVersion != BackupManifest.CURRENT_FORMAT_VERSION) {
            throw BackupException(
                "This backup is format version ${manifest.formatVersion}, this build only understands version ${BackupManifest.CURRENT_FORMAT_VERSION}",
            )
        }
        if (manifest.entries.any { it.path == MANIFEST_PATH }) {
            throw BackupException("Backup manifest lists manifest.json as data")
        }
        val seen = mutableSetOf<String>()
        manifest.entries.forEach { info ->
            if (!seen.add(info.path)) throw BackupException("Backup lists ${info.path} twice")
            if (!info.path.startsWith("files/") && !info.path.startsWith("shared_prefs/")) {
                throw BackupException("Backup entry has an unexpected path: ${info.path}")
            }
            if (info.encrypted && info.alias == null && !info.path.startsWith("shared_prefs/")) {
                throw BackupException("Backup entry ${info.path} is marked encrypted but has no KeyStore alias")
            }
        }
        val payloadPaths = payloads.map { it.first }.toSet()
        val manifestPaths = manifest.entries.map { it.path }.toSet()
        if (payloadPaths != manifestPaths) {
            val missing = (manifestPaths - payloadPaths).firstOrNull()
            val extra = (payloadPaths - manifestPaths).firstOrNull()
            throw BackupException(
                "Backup file list does not match its manifest" +
                    (missing?.let { ": missing $it" } ?: extra?.let { ": unexpected $it" } ?: ""),
            )
        }
        return manifest to payloads
    }
}

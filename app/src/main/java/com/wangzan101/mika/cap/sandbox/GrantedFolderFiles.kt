package com.wangzan101.mika.cap.sandbox

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.OperationCanceledException
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.DocumentsContract as Docs
import com.wangzan101.mika.core.workspace.DeviceFileEntry
import com.wangzan101.mika.core.workspace.DeviceFileSearchQuery
import com.wangzan101.mika.core.workspace.DeviceFileSearchResult
import com.wangzan101.mika.core.cap.sandbox.FolderEntry
import com.wangzan101.mika.core.cap.sandbox.FolderFiles
import com.wangzan101.mika.core.cap.sandbox.FolderPage
import com.wangzan101.mika.core.workspace.SandboxError
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A display label from a known system provider. File operations always use the SAF grant. */
internal fun grantedFolderLocation(authority: String?, documentId: String, volumeRoot: (String) -> String?): String? {
    val location = when (authority) {
        "com.android.externalstorage.documents" -> {
            val split = documentId.indexOf(':')
            if (split < 1) return null
            val root = volumeRoot(documentId.substring(0, split)) ?: return null
            val relative = documentId.substring(split + 1)
            if (relative.isEmpty()) root else "$root/$relative"
        }
        "com.android.providers.downloads.documents" -> {
            if (!documentId.startsWith("raw:/")) return null
            documentId.removePrefix("raw:")
        }
        else -> return null
    }
    if (!location.startsWith('/') || location.any { it.isISOControl() || it == '\\' }) return null
    return location.takeUnless { it.drop(1).split('/').any { part -> part.isEmpty() || part == "." || part == ".." } }
}

/** SAF grants are the authority. Display locations are never used for access. */
class GrantedFolderFiles(private val context: Context) : FolderFiles {
    private val resolver get() = context.contentResolver
    data class Grant(val uri: Uri, val path: String, val name: String, val writable: Boolean, val available: Boolean,
                     val canCreate: Boolean, val displayLocation: String?)
    private data class Doc(val id: String, val name: String, val mime: String, val flags: Int, val size: Long?, val modified: Long?) {
        val directory get() = mime == Docs.Document.MIME_TYPE_DIR
        val writable get() = flags and (if (directory) Docs.Document.FLAG_DIR_SUPPORTS_CREATE else Docs.Document.FLAG_SUPPORTS_WRITE) != 0
    }

    fun grants(): List<Grant> = synchronized(lock) {
        permissions().map { permission ->
            val uri = permission.uri
            val doc = try { metadata(document(uri, Docs.getTreeDocumentId(uri))) } catch (_: Exception) { null }
            val available = doc?.directory == true
            Grant(uri, rootPath(uri), doc?.name ?: uri.lastPathSegment.orEmpty(), permission.isWritePermission, available,
                available && permission.isWritePermission && doc?.writable == true,
                doc?.takeIf { it.directory }?.let { grantedFolderLocation(uri.authority, it.id, ::volumeRoot) })
        }
    }

    private fun volumeRoot(id: String): String? {
        if (id == "primary") return Environment.getExternalStorageDirectory().absolutePath
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            context.getSystemService(StorageManager::class.java)?.storageVolumes
                ?.firstOrNull { it.uuid?.equals(id, ignoreCase = true) == true }?.directory?.absolutePath
        } catch (_: SecurityException) { null }
    }

    fun authorize(uri: Uri, flags: Int) = guarded {
        if (!Docs.isTreeUri(uri)) fail("invalid_folder", "Choose a folder using the system picker.")
        val modes = flags and accessFlags
        if (modes and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) fail("folder_access_denied", "The provider did not grant read access.")
        if (!metadata(document(uri, Docs.getTreeDocumentId(uri))).directory) fail("invalid_folder", "Choose a folder.")
        resolver.takePersistableUriPermission(uri, modes)
        // Taking a narrower grant does not remove a previously persisted write permission.
        if (modes and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 && permissions().any { it.uri == uri && it.isWritePermission })
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun revoke(uri: Uri) = synchronized(lock) {
        val permission = permissions().firstOrNull { it.uri == uri } ?: return@synchronized
        val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        resolver.releasePersistableUriPermission(uri, flags)
    }

    override fun list(path: String?): FolderPage = guarded {
        if (path == null || path == "/folders") return@guarded FolderPage(grants().map {
            FolderEntry(it.path, it.name, true, it.canCreate, displayLocation = it.displayLocation)
        })
        val (tree, parts) = resolveGrant(path)
        val parent = descend(tree, parts)
        if (!parent.directory) fail("not_a_directory", "The selected path is not a folder.")
        val canWrite = permissions().any { it.uri == tree && it.isWritePermission }
        val uri = Docs.buildChildDocumentsUriUsingTree(tree, parent.id)
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            val entries = buildList { while (size <= LIST_LIMIT && cursor.moveToNext()) add(row(cursor)) }
            val counts = entries.groupingBy { it.name }.eachCount()
            val listed = entries.take(LIST_LIMIT).mapNotNull { doc ->
                val child = childPath(path, doc.name) ?: return@mapNotNull null
                if (counts[doc.name] != 1) return@mapNotNull null
                FolderEntry(child, doc.name, doc.directory, canWrite && doc.writable, doc.size)
            }
            FolderPage(listed, cursor.extras.getBoolean(Docs.EXTRA_LOADING) || entries.size > listed.size)
        } ?: throw IOException("Cannot list folder")
    }

    override fun read(path: String): ByteArray = guarded {
        val (tree, parts) = resolveGrant(path)
        val doc = descend(tree, parts)
        readText(document(tree, doc.id), doc)
    }

    /** Open under the live grant lock. The importer owns the stream and may copy binary files of any supported MIME type. */
    fun openForImport(path: String): InputStream = guarded {
        val (tree, parts) = resolveGrant(path)
        val doc = descend(tree, parts)
        if (doc.directory) fail("not_a_file", "Choose a file inside the granted folder.")
        if (doc.flags and Docs.Document.FLAG_VIRTUAL_DOCUMENT != 0)
            fail("virtual_document", "This document has no original byte stream. Export it as a file first.")
        resolver.openInputStream(document(tree, doc.id)) ?: throw IOException("Cannot open document")
    }

    override suspend fun search(query: DeviceFileSearchQuery): DeviceFileSearchResult = withContext(Dispatchers.IO) {
        val after = query.modifiedAfter
        val before = query.modifiedBefore
        if (query.maxDepth !in 0..10 || query.maxEntries !in 1..5000 || query.timeoutMs !in 1..5000 ||
            (after != null && after < 0) || (before != null && before < 0) ||
            (after != null && before != null && after > before))
            fail("invalid", "Invalid search limits or modification time range.")
        val signal = CancellationSignal()
        val deadline = SystemClock.elapsedRealtime() + query.timeoutMs
        val timer = launch(Dispatchers.Default) { delay(query.timeoutMs.toLong()); signal.cancel() }
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { signal.cancel() }
                continuation.resumeWith(runCatching { guarded { search(query, signal, deadline) } })
            }
        } finally { timer.cancel() }
    }

    private data class SearchDirectory(val id: String, val path: String, val level: Int)

    private fun search(query: DeviceFileSearchQuery, signal: CancellationSignal, deadline: Long): DeviceFileSearchResult {
        val entries = mutableListOf<DeviceFileEntry>()
        val from = query.modifiedAfter ?: Long.MIN_VALUE
        val until = query.modifiedBefore ?: Long.MAX_VALUE
        var visited = 0
        var reason: String? = null
        fun result(limit: String? = reason) = DeviceFileSearchResult(entries, visited, limit != null, limit)
        fun checkTime() {
            if (SystemClock.elapsedRealtime() >= deadline) throw OperationCanceledException()
            signal.throwIfCanceled()
        }
        try {
            checkTime()
            val (tree, parts) = resolveGrant(query.path)
            val base = descend(tree, parts, signal)
            if (!base.directory) fail("not_a_directory", "Choose a directory to search.")
            val pending = ArrayDeque<SearchDirectory>()
            pending.add(SearchDirectory(base.id, query.path, 0))
            val discovered = mutableSetOf(base.id)
            while (pending.isNotEmpty()) {
                checkTime()
                val dir = pending.removeFirst()
                val uri = Docs.buildChildDocumentsUriUsingTree(tree, dir.id)
                resolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
                    if (cursor.extras.getBoolean(Docs.EXTRA_LOADING)) reason = "provider_loading"
                    val siblingNames = mutableSetOf<String>()
                    while (cursor.moveToNext()) {
                        checkTime()
                        if (visited >= query.maxEntries) return result("max_entries")
                        visited++
                        val child = row(cursor)
                        val path = childPath(dir.path, child.name)
                        if (path == null) {
                            reason = "invalid_entry_name"
                            continue
                        }
                        if (!siblingNames.add(child.name)) {
                            entries.removeAll { it.path == path }
                            pending.removeAll { it.path == path }
                            reason = "ambiguous_file"
                            continue
                        }
                        if (matchesNameAndType(child, query)) {
                            if (child.modified == null && (query.modifiedAfter != null || query.modifiedBefore != null)) {
                                reason = "modified_time_unavailable"
                            } else if (child.modified == null || child.modified in from..until) {
                                entries += DeviceFileEntry(child.name, path, child.directory, child.size, child.modified)
                                if (entries.size >= 100) return result("max_results")
                            }
                        }
                        if (child.directory && discovered.add(child.id)) {
                            if (dir.level < query.maxDepth) pending.addLast(SearchDirectory(child.id, path, dir.level + 1))
                            else if (reason == null) reason = "max_depth"
                        }
                    }
                } ?: throw IOException("Cannot search folder")
            }
            checkTime()
        } catch (_: OperationCanceledException) {
            return result("timeout")
        }
        return result()
    }

    /** Display names are a single path component. A provider-supplied slash must never address another document. */
    private fun childPath(parent: String, name: String): String? {
        if ('/' in name) return null
        val path = "$parent/$name"
        try { FolderFiles.parts(path) } catch (_: SandboxError) { return null }
        return path
    }

    private fun matchesNameAndType(doc: Doc, query: DeviceFileSearchQuery): Boolean {
        if (doc.directory && !query.includeDirectories) return false
        if (query.name?.let { !doc.name.contains(it, ignoreCase = true) } == true) return false
        val extension = query.extension
        if (!extension.isNullOrEmpty() && !doc.name.endsWith("." + extension.trimStart('.'), ignoreCase = true)) return false
        return true
    }

    override fun write(path: String, content: ByteArray, expectedHash: String?, append: Boolean): ByteArray = guarded {
        checkText(content)
        val (tree, parts) = resolveGrant(path, write = true)
        if (parts.isEmpty()) fail("not_a_file", "Choose a file inside the granted folder.")
        val parent = descend(tree, parts.dropLast(1))
        if (!parent.directory) fail("not_a_directory", "The parent must be an existing folder.")
        val name = parts.last()
        val existing = find(tree, parent.id, name)
        val old = existing?.let {
            if (!it.writable || it.directory) fail("folder_access_denied", "This file cannot be changed.")
            readText(document(tree, it.id), it)
        }
        if (old != null && (expectedHash == null || FolderFiles.hash(old) != expectedHash))
            fail("file_changed", "Read the current file and use its sha256 before updating it. Nothing was written.")
        if (old == null && expectedHash != null)
            fail("file_changed", "The previously read file no longer exists. Nothing was written.")
        val bytes = if (append && old != null) old + content else content
        checkText(bytes)
        if (existing == null && !parent.writable) fail("folder_access_denied", "This folder cannot create files.")
        // Resolve the live grant again immediately before mutation. Revocation and Mika writes share this lock.
        resolveGrant(path, write = true)
        val uri = existing?.let { document(tree, it.id) } ?: Docs.createDocument(resolver,
            document(tree, parent.id), if (name.endsWith(".md", true)) "text/markdown" else "text/plain", name)
            ?: fail("folder_write_failed", "The file could not be created.")
        try {
            if (metadata(uri).name != name) fail("folder_name_changed", "The provider changed the requested file name.")
            writeBytes(uri, bytes)
            if (!readText(uri, metadata(uri)).contentEquals(bytes)) throw IOException("Read-back mismatch")
        } catch (error: Exception) {
            // SAF has no cross-provider atomic replace. Best-effort restore, and never report a failed write as success.
            val restored = try {
                if (old == null) Docs.deleteDocument(resolver, uri)
                else { writeBytes(uri, old); readText(uri, metadata(uri)).contentEquals(old) }
            } catch (_: Exception) { false }
            fail("folder_write_failed", if (restored) "Saving failed; the previous contents were preserved."
                else "Saving failed and the file may have changed. Read it before trying again.")
        }
        bytes
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
        stream.use { it.write(bytes); it.flush() }
    }

    private fun readText(uri: Uri, doc: Doc): ByteArray {
        if (doc.directory) fail("not_a_file", "List this folder instead of reading it as a file.")
        if ((doc.size ?: 0) > FolderFiles.MAX_BYTES) fail("file_too_large", "Granted-folder text files are limited to 1 MiB.")
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > FolderFiles.MAX_BYTES) fail("file_too_large", "Granted-folder text files are limited to 1 MiB.")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("No input stream")
        checkText(bytes)
        return bytes
    }

    private fun checkText(bytes: ByteArray) {
        if (bytes.size > FolderFiles.MAX_BYTES) fail("file_too_large", "Granted-folder text files are limited to 1 MiB.")
        if (bytes.any { it == 0.toByte() }) fail("not_text", "Only UTF-8 text files are supported in granted folders.")
        try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)) }
        catch (_: java.nio.charset.CharacterCodingException) { fail("not_text", "Only UTF-8 text files are supported in granted folders.") }
    }

    private fun resolveGrant(path: String, write: Boolean = false): Pair<Uri, List<String>> {
        val parts = FolderFiles.parts(path)
        val permission = permissions().firstOrNull { rootPath(it.uri) == "/folders/${parts.first()}" }
            ?: fail("folder_access_denied", "Folder access was removed. Ask the user to choose the folder again in Mika settings.")
        if (!permission.isReadPermission || (write && !permission.isWritePermission))
            fail("folder_access_denied", "The folder is not authorized for this operation.")
        return permission.uri to parts.drop(1)
    }

    private fun descend(tree: Uri, parts: List<String>, signal: CancellationSignal? = null): Doc {
        var doc = metadata(document(tree, Docs.getTreeDocumentId(tree)), signal)
        for (part in parts) {
            if (!doc.directory) fail("not_a_directory", "A path segment is not a folder.")
            doc = find(tree, doc.id, part, signal) ?: fail("file_not_found", "The file or folder does not exist.")
        }
        return doc
    }

    private fun find(tree: Uri, parent: String, name: String, signal: CancellationSignal? = null): Doc? {
        val uri = Docs.buildChildDocumentsUriUsingTree(tree, parent)
        // Providers may ignore the display-name filter. Check every returned row and fail closed on ambiguity.
        val args = Bundle().apply { putString(Docs.QUERY_ARG_DISPLAY_NAME, name) }
        val cursor = resolver.query(uri, columns, args, signal) ?: throw IOException("Cannot list folder")
        cursor.use {
            var match: Doc? = null
            var visited = 0
            while (cursor.moveToNext()) {
                signal?.throwIfCanceled()
                if (++visited > LOOKUP_LIMIT) fail("folder_too_large", "This folder has too many entries; choose a smaller subfolder.")
                val child = row(cursor)
                if (child.name != name) continue
                if (match != null) fail("ambiguous_file", "Several files have this name; rename them in the file manager first.")
                match = child
            }
            if (cursor.extras.getBoolean(Docs.EXTRA_LOADING)) fail("provider_loading", "The folder is still loading. Try again.")
            return match
        }
    }

    private fun metadata(uri: Uri, signal: CancellationSignal? = null): Doc = resolver.query(uri, columns, null, null, null, signal)?.use {
        if (!it.moveToFirst()) throw IOException("Missing document")
        row(it)
    } ?: throw IOException("Cannot read metadata")

    private fun row(cursor: Cursor) = Doc(
        cursor.getString(cursor.getColumnIndexOrThrow(Docs.Document.COLUMN_DOCUMENT_ID)),
        cursor.getString(cursor.getColumnIndexOrThrow(Docs.Document.COLUMN_DISPLAY_NAME)),
        cursor.getString(cursor.getColumnIndexOrThrow(Docs.Document.COLUMN_MIME_TYPE)),
        cursor.getInt(cursor.getColumnIndexOrThrow(Docs.Document.COLUMN_FLAGS)),
        cursor.optionalLong(Docs.Document.COLUMN_SIZE),
        cursor.optionalLong(Docs.Document.COLUMN_LAST_MODIFIED),
    )
    private fun Cursor.optionalLong(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index).takeIf { it >= 0 }
    }
    private fun permissions() = resolver.persistedUriPermissions.filter { Docs.isTreeUri(it.uri) && it.isReadPermission }
    private fun document(tree: Uri, id: String) = Docs.buildDocumentUriUsingTree(tree, id)
    private fun rootPath(uri: Uri) = "/folders/" + FolderFiles.hash(uri.toString().toByteArray()).take(24)
    private fun <T> guarded(block: () -> T): T = synchronized(lock) {
        try { block() }
        catch (e: SandboxError) { throw e }
        catch (_: SecurityException) { fail("folder_access_denied", "The folder permission was removed or the provider denied access.") }
        catch (_: IOException) { fail("folder_io", "The folder could not be accessed. Check that it is still available.") }
    }
    private fun fail(code: String, message: String): Nothing = throw SandboxError(code, message, true)

    companion object {
        private val lock = Any()
        private const val LIST_LIMIT = 200
        private const val LOOKUP_LIMIT = 2000
        private val accessFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME,
            Docs.Document.COLUMN_MIME_TYPE, Docs.Document.COLUMN_FLAGS, Docs.Document.COLUMN_SIZE, Docs.Document.COLUMN_LAST_MODIFIED)
    }
}

package com.wangzan101.mika.cap.sandbox

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as Docs
import com.wangzan101.mika.core.cap.sandbox.FolderEntry
import com.wangzan101.mika.core.cap.sandbox.FolderFiles
import com.wangzan101.mika.core.cap.sandbox.FolderPage
import com.wangzan101.mika.core.workspace.SandboxError
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** SAF grants are the authority. No raw storage paths, helper privileges or Linux mounts. */
class GrantedFolderFiles(private val resolver: ContentResolver) : FolderFiles {
    data class Grant(val uri: Uri, val path: String, val name: String, val writable: Boolean, val available: Boolean)
    private data class Doc(val id: String, val name: String, val mime: String, val flags: Int, val size: Long?) {
        val directory get() = mime == Docs.Document.MIME_TYPE_DIR
        val writable get() = flags and (if (directory) Docs.Document.FLAG_DIR_SUPPORTS_CREATE else Docs.Document.FLAG_SUPPORTS_WRITE) != 0
    }

    fun grants(): List<Grant> = synchronized(lock) {
        permissions().map { permission ->
            val uri = permission.uri
            val doc = try { metadata(document(uri, Docs.getTreeDocumentId(uri))) } catch (_: Exception) { null }
            Grant(uri, rootPath(uri), doc?.name ?: uri.lastPathSegment.orEmpty(),
                permission.isWritePermission && doc?.writable == true, doc?.directory == true)
        }
    }

    fun authorize(uri: Uri) = synchronized(lock) {
        if (!Docs.isTreeUri(uri)) fail("invalid_folder", "Choose a folder using the system picker.")
        resolver.takePersistableUriPermission(uri, accessFlags)
        val grant = grants().singleOrNull { it.uri == uri }
        if (grant?.available != true || !grant.writable) {
            resolver.releasePersistableUriPermission(uri, accessFlags)
            fail("folder_access_denied", "This folder does not allow reading and writing. Choose another folder.")
        }
    }

    fun revoke(uri: Uri) = synchronized(lock) {
        val permission = permissions().firstOrNull { it.uri == uri } ?: return@synchronized
        val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        resolver.releasePersistableUriPermission(uri, flags)
    }

    override fun list(path: String?): FolderPage = guarded {
        if (path == null || path == "/folders") return@guarded FolderPage(grants().map {
            FolderEntry(it.path, it.name, true, it.writable && it.available)
        })
        val (tree, parts) = resolveGrant(path)
        val parent = descend(tree, parts)
        if (!parent.directory) fail("not_a_directory", "The selected path is not a folder.")
        val entries = children(tree, parent.id, LIST_LIMIT + 1)
        val canWrite = permissions().any { it.uri == tree && it.isWritePermission }
        FolderPage(entries.take(LIST_LIMIT).map {
            FolderEntry("$path/${it.name}", it.name, it.directory, canWrite && it.writable, it.size)
        }, entries.size > LIST_LIMIT)
    }

    override fun read(path: String): ByteArray = guarded {
        val (tree, parts) = resolveGrant(path)
        val doc = descend(tree, parts)
        readText(document(tree, doc.id), doc)
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

    private fun descend(tree: Uri, parts: List<String>): Doc {
        var doc = metadata(document(tree, Docs.getTreeDocumentId(tree)))
        for (part in parts) {
            if (!doc.directory) fail("not_a_directory", "A path segment is not a folder.")
            doc = find(tree, doc.id, part) ?: fail("file_not_found", "The file or folder does not exist.")
        }
        return doc
    }

    private fun find(tree: Uri, parent: String, name: String): Doc? {
        val rows = children(tree, parent, LOOKUP_LIMIT + 1)
        if (rows.size > LOOKUP_LIMIT) fail("folder_too_large", "This folder has too many entries; choose a smaller subfolder.")
        val matches = rows.filter { it.name == name }
        if (matches.size > 1) fail("ambiguous_file", "Several files have this name; rename them in the file manager first.")
        return matches.singleOrNull()
    }

    private fun children(tree: Uri, parent: String, limit: Int): List<Doc> {
        val result = mutableListOf<Doc>()
        resolver.query(Docs.buildChildDocumentsUriUsingTree(tree, parent), columns, null, null, null)?.use { cursor ->
            while (result.size < limit && cursor.moveToNext()) result += row(cursor)
        } ?: throw IOException("Cannot list folder")
        return result
    }

    private fun metadata(uri: Uri): Doc = resolver.query(uri, columns, null, null, null)?.use {
        if (!it.moveToFirst()) throw IOException("Missing document")
        row(it)
    } ?: throw IOException("Cannot read metadata")

    private fun row(cursor: android.database.Cursor) = Doc(cursor.getString(0), cursor.getString(1), cursor.getString(2),
        cursor.getInt(3), if (cursor.isNull(4)) null else cursor.getLong(4))
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
            Docs.Document.COLUMN_MIME_TYPE, Docs.Document.COLUMN_FLAGS, Docs.Document.COLUMN_SIZE)
    }
}

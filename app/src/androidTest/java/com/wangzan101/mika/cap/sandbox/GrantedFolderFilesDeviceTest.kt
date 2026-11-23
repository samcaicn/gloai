package com.wangzan101.mika.cap.sandbox

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract as Docs
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.workspace.DeviceFileSearchQuery
import com.wangzan101.mika.core.cap.sandbox.FolderFiles
import com.wangzan101.mika.core.cap.sandbox.FolderFileImporter
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.runAction
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.files.ui.DeviceDirectoriesScreen
import com.wangzan101.mika.files.ui.FolderGrantPicker
import com.wangzan101.mika.settings.DeviceDirectoryStore
import com.wangzan101.mika.task.DeviceImportStore
import com.wangzan101.mika.task.DeviceWorkspaceImports
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FilterInputStream
import java.lang.reflect.Proxy

/** Real system picker + persisted SAF grant. Only uses a dedicated folder created by the test runner. */
@RunWith(AndroidJUnit4::class)
class GrantedFolderFilesDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val folders = GrantedFolderFiles(context)
    private val testName = "MikaFolderTest645"

    @Test fun pickerIntersectsRequestedAndReturnedAccess() {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val both = Intent().setData(Uri.parse("content://provider/tree/root")).addFlags(read or write)
        val readPicker = FolderGrantPicker(write = false)
        assertEquals(read, readPicker.createIntent(context, Unit).flags and (read or write))
        assertEquals(read, readPicker.parseResult(Activity.RESULT_OK, both)!!.second)
        assertEquals(read or write, FolderGrantPicker(true).parseResult(Activity.RESULT_OK, both)!!.second)
        both.flags = read
        assertEquals(read, FolderGrantPicker(true).parseResult(Activity.RESULT_OK, both)!!.second)
        assertNull(readPicker.parseResult(Activity.RESULT_CANCELED, both))
    }

    @Test fun authorizeSaveUpdateConflictAndRevoke() = runBlocking {
        // Never reuse a user folder or silently grant one. Runner selects the dedicated folder in the picker.
        assertFalse(folders.grants().any { it.name == testName })
        val preferences = context.getSharedPreferences("folder-device-test", 0)
        preferences.edit().clear().commit()
        val store = DeviceDirectoryStore(preferences)
        compose.setContent { DeviceDirectoriesScreen(store, PhoneServices.of(context).hub) {} }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("选择可读写的文件夹") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("选择可读写的文件夹").performClick()
        // The system picker grants the real target application's UID; shell appops are not used.
        val deadline = System.currentTimeMillis() + 120_000
        while (folders.grants().none { it.name == testName } && System.currentTimeMillis() < deadline) android.os.SystemClock.sleep(250)
        val grant = folders.grants().single { it.name == testName }
        try {
            assertTrue(grant.available && grant.writable)
            assertEquals(grant.path, GrantedFolderFiles(context).grants().single { it.name == testName }.path)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(testName).fetchSemanticsNodes().isNotEmpty() }
            snapshot("authorized")
            val screen = Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, m, _ ->
                when (m.name) {
                    "getAppAccess" -> com.wangzan101.mika.core.device.access.AppAccessPolicy.ALLOW
                    "getCanShareAudio", "getCanShareImage" -> false
                    else -> throw AssertionError("Unexpected screen call: ${m.name}")
                }
            } as ScreenPort
            val dirs = SandboxDirs(context)
            val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() }, folderFiles = folders)
            val workspace = WorkspaceKey.Conversation("folder-device-test")
            val history = DeviceImportStore(preferences)
            val imports = DeviceWorkspaceImports(dirs, sb::freeBytes, history, WorkspaceKey::parse, { workspace.id })
            val spec = RunSpec(RunId("folder-test"), "folder-test", "folder-test", provenance = RunProvenance(RunOrigin.Ui, Attendance.Present))
            val tools = toolDispatcher(screen, sandbox = sb, workspace = { workspace.wire }, runSpec = spec,
                folderFileImporter = FolderFileImporter { path, key -> imports.fromFolder(path, key, folders::openForImport) })
            suspend fun tool(name: String, args: JsonObject) = tools.runAction(Part.ToolCall("folder", name, args.toString())).content
            val path = "${grant.path}/五十音学习.md"
            val text = "# 五十音学习\n每天练十个假名，配单词朗读。\n"
            val created = tool("write_file", buildJsonObject { put("path", path); put("content", text) })
            assertEquals(created.toString(), JsonPrimitive(true), created["ok"])
            val read = tool("read_file", buildJsonObject { put("path", path) })
            assertEquals(JsonPrimitive(text), read["content"])
            val hash = read["sha256"]!!.jsonPrimitive.content
            val updated = tool("write_file", buildJsonObject {
                put("path", path); put("content", "第二天先复习昨日内容。\n"); put("append", true); put("expectedSha256", hash)
            })
            assertEquals(updated.toString(), JsonPrimitive(true), updated["ok"])
            val stale = tool("write_file", buildJsonObject { put("path", path); put("content", "错误旧稿"); put("expectedSha256", hash) })
            assertEquals(JsonPrimitive("file_changed"), stale["error"])
            assertEquals(text + "第二天先复习昨日内容。\n", folders.read(path).toString(Charsets.UTF_8))
            val missingHash = tool("write_file", buildJsonObject { put("path", path); put("content", "不能覆盖") })
            assertEquals(JsonPrimitive("file_changed"), missingHash["error"])
            expectError("invalid_folder_path") { folders.write("${grant.path}/../outside.md", byteArrayOf(1), null, false) }
            expectError("folder_access_denied") { folders.write("/folders/unknown/a.md", "x".toByteArray(), null, false) }
            expectError("file_too_large") { folders.write("${grant.path}/large.md", ByteArray(FolderFiles.MAX_BYTES + 1) { 65 }, null, false) }
            val ws = dirs.workspace(workspace).apply { mkdirs() }
            File(ws, "summary.md").writeText("# 本周复习\n交替听写和朗读。")
            val exported = tool("share_file", buildJsonObject { put("path", "/workspace/summary.md"); put("folderPath", "${grant.path}/复习总结.md") })
            assertEquals(exported.toString(), JsonPrimitive(true), exported["ok"])
            assertEquals("# 本周复习\n交替听写和朗读。", folders.read("${grant.path}/复习总结.md").toString(Charsets.UTF_8))
            assertEquals(2, folders.list(grant.path).entries.size)
            val query = DeviceFileSearchQuery(grant.path, "五十", ".MD", null, null, 0, 100, 5000)
            assertEquals(listOf(path), folders.search(query).entries.map { it.path })
            assertEquals("max_entries", folders.search(query.copy(name = null, maxEntries = 1)).reason)

            val rootUri = Docs.buildDocumentUriUsingTree(grant.uri, Docs.getTreeDocumentId(grant.uri))
            val subdir = requireNotNull(Docs.createDocument(context.contentResolver, rootUri, Docs.Document.MIME_TYPE_DIR, "资料"))
            val image = requireNotNull(Docs.createDocument(context.contentResolver, subdir, "application/octet-stream", "sample.bin"))
            val payload = ByteArray(FolderFiles.MAX_BYTES + 3) { it.toByte() }
            requireNotNull(context.contentResolver.openOutputStream(image)).use { it.write(payload) }
            val binarySearch = query.copy(name = null, extension = "bin", maxDepth = 1)
            val hits = tool("search_device_files", buildJsonObject {
                put("path", grant.path); put("extension", "bin"); put("max_depth", 1); put("timeout_ms", 5000)
            })
            val binaryPath = hits.getValue("entries").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content
            val shallow = folders.search(binarySearch.copy(maxDepth = 0))
            assertTrue(shallow.entries.isEmpty())
            assertEquals("max_depth", shallow.reason)
            expectError("file_too_large") { folders.read(binaryPath) }
            expectError("not_a_file") { folders.openForImport(grant.path) }
            val many = requireNotNull(Docs.createDocument(context.contentResolver, rootUri, Docs.Document.MIME_TYPE_DIR, "分页"))
            try {
                val names = (1..201).map { "page-$it.txt" }
                names.forEach { requireNotNull(Docs.createDocument(context.contentResolver, many, "text/plain", it)) }
                val manyPath = "${grant.path}/分页"
                val firstPage = folders.list(manyPath)
                assertTrue(firstPage.truncated)
                val remaining = (names - firstPage.entries.map { it.name }.toSet()).single()
                val beyondList = folders.search(query.copy(path = manyPath, name = remaining, extension = null, maxEntries = 500))
                assertEquals(listOf("$manyPath/$remaining"), beyondList.entries.map { it.path })
                assertFalse(beyondList.truncated)
            } finally {
                Docs.deleteDocument(context.contentResolver, many)
            }
            // Simulate choosing the same tree with the read-only picker; only the persisted read bit remains.
            folders.authorize(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertFalse(GrantedFolderFiles(context).grants().single { it.uri == grant.uri }.writable)
            val copied = tool("copy_device_file", buildJsonObject { put("path", binaryPath) })
            assertEquals(copied.toString(), JsonPrimitive(true), copied["ok"])
            val target = copied.getValue("path").jsonPrimitive.content
            assertTrue(target.startsWith("/workspace/inbox/"))
            assertArrayEquals(payload, File(ws, target.removePrefix("/workspace/")).readBytes())
            assertEquals(binaryPath, history.list(workspace.id).single().sourcePath)
            val interrupted = launch {
                val job = currentCoroutineContext().job
                imports.fromFolder(binaryPath, workspace.wire) { source ->
                    object : FilterInputStream(folders.openForImport(source)) {
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                            super.read(bytes, offset, length).also { if (it > 0) job.cancel() }
                    }
                }
            }
            interrupted.join()
            assertTrue(interrupted.isCancelled)
            assertEquals(listOf(File(target).name), File(ws, "inbox").list()!!.toList())
            assertEquals(1, history.list(workspace.id).size)
            assertTrue(folders.read(path).isNotEmpty())
            expectError("folder_access_denied") { folders.write(path, "x".toByteArray(), null, false) }
            snapshot("saved")
            compose.onNodeWithText("取消授权").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(testName).fetchSemanticsNodes().isEmpty() }
            expectError("folder_access_denied") { folders.read(path) }
            expectError("folder_access_denied") { folders.openForImport(binaryPath) }
            expectError("folder_access_denied") { folders.search(binarySearch) }
            assertEquals(JsonPrimitive("folder_access_denied"), tool("copy_device_file", buildJsonObject { put("path", binaryPath) })["error"])
            expectError("folder_access_denied") { folders.write(path, "拒绝".toByteArray(), updated["sha256"]!!.jsonPrimitive.content, false) }
            val revoked = tool("write_file", buildJsonObject { put("path", path); put("content", "拒绝") })
            assertEquals(JsonPrimitive("folder_access_denied"), revoked["error"])
            snapshot("revoked")
            ws.deleteRecursively()
        } finally {
            folders.revoke(grant.uri)
            preferences.edit().clear().commit()
        }
        Unit
    }

    private inline fun expectError(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: SandboxError) { assertEquals(code, e.code) }
    }
    private fun snapshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "folder-$name-${bitmap.width}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

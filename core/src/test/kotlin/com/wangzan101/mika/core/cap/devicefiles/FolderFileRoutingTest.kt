package com.wangzan101.mika.core.cap.devicefiles

import com.wangzan101.mika.core.workspace.DeviceFileEntry
import com.wangzan101.mika.core.workspace.DeviceFileImport
import com.wangzan101.mika.core.workspace.DeviceFileSearchQuery
import com.wangzan101.mika.core.workspace.DeviceFileSearchResult
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.sandbox.FolderEntry
import com.wangzan101.mika.core.cap.sandbox.FolderFileImporter
import com.wangzan101.mika.core.cap.sandbox.FolderFiles
import com.wangzan101.mika.core.cap.sandbox.FolderPage
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class FolderFileRoutingTest {
    private val fake = FakeSandbox()
    private val folderRoot = "/folders/a1b2"
    private val helperRoot = "/sdcard/Download"
    private var revoked = false
    private var folderQuery: DeviceFileSearchQuery? = null
    private var folderImport: Pair<String, String>? = null

    private fun requireGrant() {
        if (revoked) throw SandboxError("folder_access_denied", "Grant revoked", true)
    }

    private val folders = object : FolderFiles {
        override fun list(path: String?): FolderPage {
            requireGrant()
            return if (path == null) FolderPage(listOf(FolderEntry(folderRoot, "Notes", true, true)))
            else FolderPage(listOf(FolderEntry("$folderRoot/note.txt", "note.txt", false, true)))
        }

        override suspend fun search(query: DeviceFileSearchQuery): DeviceFileSearchResult {
            requireGrant()
            folderQuery = query
            return DeviceFileSearchResult(listOf(DeviceFileEntry("scan.pdf", "$folderRoot/scan.pdf", false)), 6, true, "max_entries")
        }

        override fun read(path: String): ByteArray = error("Import must not use the text reader")
        override fun write(path: String, content: ByteArray, expectedHash: String?, append: Boolean): ByteArray =
            error("Device imports never write back to the grant")
    }

    private val importer = FolderFileImporter { path, workspace ->
        requireGrant()
        folderImport = path to workspace
        val file = fake.workspaceDir(workspace).resolve("inbox/note.txt")
        file.parentFile.mkdirs()
        file.writeText("Notes from SAF")
        DeviceFileImport("/workspace/inbox/note.txt", file.length())
    }
    private val sandbox = object : SandboxPort by fake { override val folderFiles = folders }

    private val helper = object : DeviceFilesPort {
        var query: DeviceFileSearchQuery? = null
        var imported: Pair<String, String>? = null
        override suspend fun list(path: String?, cursor: String?, limit: Int) =
            DeviceFilePage(listOf(DeviceFileEntry("Download", helperRoot, true)))
        override suspend fun search(query: DeviceFileSearchQuery): DeviceFileSearchResult {
            this.query = query
            return DeviceFileSearchResult(listOf(DeviceFileEntry("scan.pdf", "$helperRoot/scan.pdf", false)), 1, false, null)
        }
        override suspend fun importFile(path: String, workspace: String): DeviceFileImport {
            imported = path to workspace
            return DeviceFileImport("/workspace/inbox/scan.pdf", 12)
        }
    }

    private fun dispatcher(
        saf: Boolean = true,
        deviceHelper: Boolean = true,
        capabilities: Set<String>? = null,
        origin: RunOrigin = RunOrigin.Ui,
        attendance: Attendance = Attendance.Present,
        readOnly: Boolean = false,
    ) = toolDispatcher(
        FakeScreen(), web = FakeWebPort(), sandbox = if (saf) sandbox else null,
        deviceFiles = if (deviceHelper) helper else null, folderFileImporter = if (saf) importer else null,
        workspace = { "notes" },
        runSpec = RunSpec(RunId("files"), "files", "notes", provenance = RunProvenance(origin, attendance),
            capabilities = capabilities, readOnly = readOnly),
    )

    private suspend fun ToolDispatcher.call(name: String, args: String = "{}") =
        execute(Part.ToolCall("1", name, args), ActionPass()).content

    private fun JsonObject.paths() = getValue("entries").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }

    @Test fun safOnlyOpensSearchAndImportThroughTheFolderEntry() = runTest {
        val tools = dispatcher(deviceHelper = false)
        val before = tools.offeredNow().map { it.name }
        assertTrue("list_folder_files" in before)
        assertFalse("list_device_files" in before)
        assertFalse("search_device_files" in before)
        assertFalse("copy_device_file" in before)
        assertEquals(listOf(folderRoot), tools.call("list_folder_files").paths())
        val after = tools.offeredNow().map { it.name }
        assertTrue("search_device_files" in after)
        assertTrue("copy_device_file" in after)
        assertTrue("read_file" in after)

        val copied = tools.call("copy_device_file", """{"path":"$folderRoot/note.txt"}""")
        assertEquals("$folderRoot/note.txt" to "notes", folderImport)
        assertEquals(JsonPrimitive(14L), copied["bytesCopied"])
        assertEquals(JsonPrimitive("/workspace/inbox/note.txt"), copied["path"])
        assertEquals("Notes from SAF", fake.workspaceDir("notes").resolve("inbox/note.txt").readText())
    }

    @Test fun helperRetainsItsDiscoveryToolsAndEachFileOperationIsRegisteredOnce() = runTest {
        for (withSaf in listOf(false, true)) {
            val tools = dispatcher(saf = withSaf)
            val offered = tools.offeredNow().map { it.name }
            assertTrue("list_device_directory_hints" in offered)
            assertTrue("list_device_files" in offered)
            assertEquals(withSaf, "list_folder_files" in offered)
            for (name in listOf("search_device_files", "copy_device_file")) {
                assertEquals(1, offered.count { it == name })
                assertEquals(1, tools.definitions().count { it.name == name })
            }
            assertEquals(listOf(helperRoot), tools.call("list_device_files").paths())
            if (withSaf) assertEquals(listOf(folderRoot), tools.call("list_folder_files").paths())
        }
    }

    @Test fun sharedOperationsDispatchByIdentifierAndPreserveSearchBounds() = runTest {
        val tools = dispatcher()
        assertFalse(tools.offeredNow().any { it.name == "read_file" })
        for (root in listOf(folderRoot, helperRoot)) {
            val result = tools.call("search_device_files", """{"path":"$root","name":"scan","extension":"pdf","modified_after":"123","max_depth":2,"max_entries":6,"timeout_ms":250}""")
            val expected = DeviceFileSearchQuery(root, "scan", "pdf", 123, null, 2, 6, 250)
            assertEquals(expected, if (root == folderRoot) folderQuery else helper.query)
            assertEquals(listOf("$root/scan.pdf"), result.paths())
            assertEquals(JsonPrimitive(root == folderRoot), result["truncated"])
            if (root == folderRoot) {
                assertEquals(JsonPrimitive("max_entries"), result["reason"])
                assertEquals(JsonPrimitive(6), result["visited"])
                assertNull(helper.query)
            }
        }
        val result = tools.call("copy_device_file", """{"path":"$helperRoot/scan.pdf"}""")
        assertEquals(JsonPrimitive("/workspace/inbox/scan.pdf"), result["path"])
        assertEquals("$helperRoot/scan.pdf" to "notes", helper.imported)
        assertNull(folderImport)
        assertTrue(tools.offeredNow().any { it.name == "read_file" })
    }

    @Test fun revocationAfterListingNeverFallsBackToTheHelper() = runTest {
        val tools = dispatcher()
        assertEquals(listOf(folderRoot), tools.call("list_folder_files").paths())
        revoked = true
        for (name in listOf("search_device_files", "copy_device_file")) {
            val reply = tools.call(name, """{"path":"$folderRoot/note.txt"}""")
            assertEquals(JsonPrimitive("folder_access_denied"), reply["error"])
        }
        assertNull(helper.query)
        assertNull(helper.imported)
        assertNull(folderImport)
    }

    @Test fun allowlistLimitsTheSourceEvenWhenBothPortsWereSupplied() = runTest {
        val safOnly = dispatcher(capabilities = setOf("web", "sandbox"))
        assertTrue(safOnly.definitions().any { it.name == "list_folder_files" })
        assertFalse(safOnly.definitions().any { it.name == "list_device_files" })
        assertEquals(listOf("$folderRoot/scan.pdf"), safOnly.call("search_device_files", """{"path":"$folderRoot"}""").paths())
        assertEquals(JsonPrimitive(false), safOnly.call("copy_device_file", """{"path":"$helperRoot/scan.pdf"}""")["ok"])
        assertNull(helper.imported)

        val helperOnly = dispatcher(capabilities = setOf("web", "device_files"))
        assertTrue(helperOnly.definitions().any { it.name == "list_device_files" })
        assertFalse(helperOnly.definitions().any { it.name == "list_folder_files" })
        assertEquals(listOf("$helperRoot/scan.pdf"), helperOnly.call("search_device_files", """{"path":"$helperRoot"}""").paths())
        assertEquals(JsonPrimitive(false), helperOnly.call("copy_device_file", """{"path":"$folderRoot/note.txt"}""")["ok"])
        assertNull(folderImport)
        assertNull(helper.imported)
    }

    @Test fun restrictedRunsCannotReachFilesByCallingAKnownToolDirectly() = runTest {
        val denied = listOf(RunOrigin.Channel, RunOrigin.Trigger, RunOrigin.Mcp, RunOrigin.Child).map { dispatcher(origin = it) } +
            dispatcher(attendance = Attendance.Unattended)
        for (tools in denied) {
            val names = tools.definitions().map { it.name }
            for (name in listOf("list_folder_files", "list_device_files", "search_device_files", "copy_device_file")) {
                assertFalse(name in names)
                assertEquals(JsonPrimitive(false), tools.call(name, """{"path":"$folderRoot"}""")["ok"])
            }
        }
        val readOnly = dispatcher(readOnly = true)
        assertFalse(readOnly.definitions().any { it.name == "copy_device_file" || it.name == "list_folder_files" })
        assertEquals(listOf("$helperRoot/scan.pdf"), readOnly.call("search_device_files", """{"path":"$helperRoot"}""").paths())
        assertEquals(JsonPrimitive(false), readOnly.call("search_device_files", """{"path":"$folderRoot"}""")["ok"])
        assertEquals(JsonPrimitive(false), readOnly.call("copy_device_file", """{"path":"$helperRoot/scan.pdf"}""")["ok"])
        assertNull(folderQuery)
        assertNull(folderImport)
        assertNull(helper.imported)
    }

    @Test fun unsupportedSandboxDoesNotDisableTheHelperOrExposeSaf() = runTest {
        fake.status = SandboxStatus.Unsupported("unsupported")
        val tools = dispatcher()
        assertFalse(tools.definitions().any { it.name == "list_folder_files" })
        assertEquals(listOf("$helperRoot/scan.pdf"), tools.call("search_device_files", """{"path":"$helperRoot"}""").paths())
        assertEquals(JsonPrimitive(false), tools.call("copy_device_file", """{"path":"$folderRoot/note.txt"}""")["ok"])
        assertNull(helper.imported)
        assertNull(folderImport)
    }
}

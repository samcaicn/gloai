package com.wangzan101.mika.core.cap.sandbox

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FolderFilesTest {
    private val fake = FakeSandbox()
    private val port = object : FolderFiles {
        var bytes = "日语学习：每天练习十分钟。".toByteArray()
        var expected: String? = null
        var writes = 0
        override fun list(path: String?) = FolderPage(listOf(
            FolderEntry("/folders/test", "Mika", true, true, displayLocation = "/storage/emulated/0/Download/Mika"),
            FolderEntry("/folders/other", "Mika", true, false),
        ))
        override fun read(path: String) = bytes
        override fun write(path: String, content: ByteArray, expectedHash: String?, append: Boolean): ByteArray {
            writes++; expected = expectedHash; bytes = if (append) bytes + content else content; return bytes
        }
    }
    private val sandbox = object : SandboxPort by fake { override val folderFiles = port }
    private fun spec(origin: RunOrigin = RunOrigin.Ui, attendance: Attendance = Attendance.Present, readOnly: Boolean = false) =
        RunSpec(RunId("test"), "test", "test", provenance = RunProvenance(origin, attendance), readOnly = readOnly)
    private fun dispatcher(spec: RunSpec = spec()) = toolDispatcher(FakeScreen(), web = com.wangzan101.mika.core.cap.web.FakeWebPort(), sandbox = sandbox, runSpec = spec, workspace = { "notes" })
    private fun call(name: String, args: JsonObject) = Part.ToolCall("test", name, args.toString())

    @Test fun locationIdentifiesTheGrantWhileItsToolPathRemainsOpaque() = runTest {
        val result = dispatcher().execute(call("list_folder_files", buildJsonObject {}), ActionPass()).content
        val (local, other) = result.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(local["name"], other["name"])
        assertEquals(JsonPrimitive("/storage/emulated/0/Download/Mika"), local["displayLocation"])
        assertEquals(JsonPrimitive("/folders/test"), local["path"])
        assertNull(other["displayLocation"])
        assertEquals(JsonPrimitive("/folders/other"), other["path"])
    }

    @Test fun folderGuidanceOnlyAppearsWhenItsToolIsOffered() {
        val text = com.wangzan101.mika.core.prompt.Prompts.capability("sandbox")
        val absent = com.wangzan101.mika.core.capability.PromptEnv(emptySet(), false).gate(text)
        val present = com.wangzan101.mika.core.capability.PromptEnv(setOf("list_folder_files"), false).gate(text)
        assertFalse(absent.contains("list_folder_files"))
        assertTrue(present.contains("list_folder_files"))
        assertFalse(present.contains("<!-- needs:"))
    }

    @Test fun readsUnicodeAndPassesRevisionToWrite() = runTest {
        val d = dispatcher()
        val read = d.execute(call("read_file", buildJsonObject { put("path", "/folders/test/study.md"); put("limit", 4) }), ActionPass()).content
        assertEquals("日", read["content"]!!.jsonPrimitive.content)
        assertEquals(3, read["nextOffset"]!!.jsonPrimitive.int)
        val hash = read["sha256"]!!.jsonPrimitive.content
        assertEquals(FolderFiles.hash(port.bytes), hash)
        val result = d.execute(call("write_file", buildJsonObject {
            put("path", "/folders/test/study.md"); put("content", "复习あいうえお"); put("expectedSha256", hash)
        }), ActionPass()).content
        assertEquals(JsonPrimitive(true), result["ok"])
        assertEquals(hash, port.expected)
        assertEquals("复习あいうえお", port.bytes.toString(Charsets.UTF_8))
    }

    @Test fun folderAccessCannotBeReachedFromUnattendedOrRemoteRuns() = runTest {
        for (s in listOf(spec(RunOrigin.Channel), spec(RunOrigin.Trigger), spec(RunOrigin.Mcp), spec(RunOrigin.Child), spec(attendance = Attendance.Unattended))) {
            val d = dispatcher(s)
            assertFalse(d.definitions().any { it.name == "list_folder_files" })
            val r = d.execute(call("write_file", buildJsonObject { put("path", "/folders/test/a.md"); put("content", "x") }), ActionPass()).content
            assertNotEquals(JsonPrimitive(true), r["ok"])
        }
        assertEquals(0, port.writes)
        assertFalse(dispatcher(spec(readOnly = true)).definitions().any { it.name == "write_file" })
        assertTrue(dispatcher().definitions().any { it.name == "list_folder_files" })
    }

    @Test fun exportsGeneratedTextWithoutSendingItToDownloads() = runTest {
        fake.workspaceDir("notes").resolve("summary.md").writeText("# 学习计划\n先练发音，再练书写。")
        val r = dispatcher().execute(call("share_file", buildJsonObject {
            put("path", "/workspace/summary.md"); put("folderPath", "/folders/test/summary.md")
        }), ActionPass()).content
        assertEquals(r.toString(), JsonPrimitive(true), r["ok"])
        assertEquals(JsonPrimitive("/folders/test/summary.md"), r["savedAs"])
        assertTrue(fake.shared.isEmpty())
        assertEquals(1, port.writes)
        fake.workspaceDir("notes").resolve("binary.bin").writeBytes(byteArrayOf(0, 1))
        val refused = dispatcher().execute(call("share_file", buildJsonObject {
            put("path", "/workspace/binary.bin"); put("folderPath", "/folders/test/binary.bin")
        }), ActionPass()).content
        assertEquals(JsonPrimitive("not_text"), refused["error"])
        assertNull(refused["savedAs"])
        assertEquals(1, port.writes)
    }

    @Test fun rejectsTraversalAndOversizeBeforeWriting() = runTest {
        for (path in listOf("/sdcard/x", "/folders/a/../b", "/folders/a//b", "/folders/a/.", "/folders/a/a\\b", "/folders/a/x\u0000")) {
            try { FolderFiles.parts(path); fail(path) } catch (_: SandboxError) { }
        }
        val result = dispatcher().execute(call("write_file", buildJsonObject {
            put("path", "/folders/test/large.md"); put("content", "中".repeat(FolderFiles.MAX_BYTES / 3 + 1))
        }), ActionPass()).content
        assertEquals(JsonPrimitive("file_too_large"), result["error"])
        assertEquals(0, port.writes)
    }
}

package com.wangzan101.mika.cap.sandbox

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.FolderFiles
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.runAction
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.ui.DeviceDirectoriesScreen
import com.wangzan101.mika.settings.DeviceDirectoryStore
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy

/** Real system picker + persisted SAF grant. Only uses a dedicated folder created by the test runner. */
@RunWith(AndroidJUnit4::class)
class GrantedFolderFilesDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val folders = GrantedFolderFiles(context.contentResolver)
    private val testName = "MikaFolderTest645"

    @Test fun authorizeSaveUpdateConflictAndRevoke() = runBlocking {
        // Never reuse a user folder or silently grant one. Runner selects the dedicated folder in the picker.
        assertFalse(folders.grants().any { it.name == testName })
        val preferences = context.getSharedPreferences("folder-device-test", 0)
        preferences.edit().clear().commit()
        val store = DeviceDirectoryStore(preferences)
        compose.setContent { DeviceDirectoriesScreen(store, PhoneServices.of(context).hub) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("选择可读写的文件夹").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("选择可读写的文件夹").performClick()
        // The system picker grants the real target application's UID; shell appops are not used.
        val deadline = System.currentTimeMillis() + 120_000
        while (folders.grants().none { it.name == testName } && System.currentTimeMillis() < deadline) android.os.SystemClock.sleep(250)
        val grant = folders.grants().single { it.name == testName }
        try {
            assertTrue(grant.available && grant.writable)
            assertEquals(grant.path, GrantedFolderFiles(context.contentResolver).grants().single { it.name == testName }.path)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(testName).fetchSemanticsNodes().isNotEmpty() }
            snapshot("authorized")
            val screen = Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, m, _ ->
                if (m.name == "getAppAccess") com.wangzan101.mika.core.access.AppAccessPolicy.ALLOW
                else throw AssertionError("Unexpected screen call: ${m.name}")
            } as ScreenPort
            val sb = AndroidSandbox(SandboxDirs(context), { SandboxStatus.Ready(false) }, { emptyList() }, folderFiles = folders)
            val spec = RunSpec(RunId("folder-test"), "folder-test", "folder-test", provenance = RunProvenance(RunOrigin.Ui, Attendance.Present))
            val tools = toolDispatcher(screen, sandbox = sb, workspace = { "folder-device-test" }, runSpec = spec)
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
            val ws = SandboxDirs(context).workspace(WorkspaceKey.Conversation("folder-device-test")).apply { mkdirs() }
            File(ws, "summary.md").writeText("# 本周复习\n交替听写和朗读。")
            val exported = tool("share_file", buildJsonObject { put("path", "/workspace/summary.md"); put("folderPath", "${grant.path}/复习总结.md") })
            assertEquals(exported.toString(), JsonPrimitive(true), exported["ok"])
            assertEquals("# 本周复习\n交替听写和朗读。", folders.read("${grant.path}/复习总结.md").toString(Charsets.UTF_8))
            assertEquals(2, folders.list(grant.path).entries.size)
            snapshot("saved")
            compose.onNodeWithText("取消授权").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(testName).fetchSemanticsNodes().isEmpty() }
            expectError("folder_access_denied") { folders.read(path) }
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

    private fun expectError(code: String, block: () -> Unit) {
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

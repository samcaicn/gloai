package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.vision.JvmTestImageOps
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ScreenshotExportTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("capture", name, args)
    private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content

    @Test fun modelWithoutVisionCanCaptureAndShareTheActualImage() = runTest {
        val sb = FakeSandbox()
        try {
            val png = JvmTestImageOps.encode(8, 8) { _, _ -> 128 }
            val screen = FakeScreen().apply { this.png = png }
            val tools = toolDispatcher(screen, visionEnabled = false, sandbox = sb, workspace = { "wechat-thread" })
            assertTrue(tools.definitions().any { it.name == "phone_screenshot" })
            assertFalse(tools.offeredNow().any { it.name == "share_file" })
            val result = tools.execute(call("phone_screenshot", """{"savePath":"/workspace/screens/result.png"}"""))
            assertEquals("true", result.content.s("ok"))
            assertTrue(result.images.isEmpty())
            assertArrayEquals(png, sb.workspaceDir("wechat-thread").resolve("screens/result.png").readBytes())
            assertTrue(tools.offeredNow().any { it.name == "share_file" })
            val shared = tools.execute(call("share_file", """{"path":"/workspace/screens/result.png"}"""))
            assertEquals("true", shared.content.s("ok"))
            assertArrayEquals(png, sb.shared.single().first.readBytes())
            assertTrue(sb.runs.isEmpty())
        } finally { sb.root.deleteRecursively() }
    }

    @Test fun savingDoesNotNeedTheLinuxRuntimeAndDoesNotOverwriteFiles() = runTest {
        val sb = FakeSandbox(SandboxStatus.NotInstalled)
        try {
            val tools = toolDispatcher(FakeScreen(), sandbox = sb, workspace = { "thread" })
            assertEquals("save_path_required", tools.execute(call("phone_screenshot")).content.s("error"))
            val args = """{"savePath":"/workspace/screen.png"}"""
            assertEquals("true", tools.execute(call("phone_screenshot", args)).content.s("ok"))
            assertEquals("file_exists", tools.execute(call("phone_screenshot", args)).content.s("error"))
            assertEquals(0, sb.waits)
        } finally { sb.root.deleteRecursively() }
    }

    @Test fun protectedPagesAreNotSavedOrCaptured() = runTest {
        val sb = FakeSandbox()
        try {
            var captures = 0
            val screen = object : ScreenPort by FakeScreen() {
                override suspend fun isProtected() = true
                override suspend fun screenshotPng(): ByteArray { captures++; return byteArrayOf(1) }
            }
            val tools = toolDispatcher(screen, sandbox = sb, workspace = { "thread" })
            assertEquals("protected_content", tools.execute(call("phone_screenshot", """{"savePath":"/workspace/no.png"}""")).content.s("error"))
            assertEquals(0, captures)
            assertFalse(sb.workspaceDir("thread").resolve("no.png").exists())
        } finally { sb.root.deleteRecursively() }
    }

    @Test fun protectionAppearingDuringCapturePreventsSaving() = runTest {
        val sb = FakeSandbox()
        try {
            var captured = false
            val screen = object : ScreenPort by FakeScreen() {
                override suspend fun isProtected() = captured
                override suspend fun screenshotPng(): ByteArray { captured = true; return byteArrayOf(1) }
            }
            val tools = toolDispatcher(screen, sandbox = sb, workspace = { "thread" })
            val result = tools.execute(call("phone_screenshot", """{"savePath":"/workspace/no.png"}"""))
            assertEquals("protected_content", result.content.s("error"))
            assertTrue(result.images.isEmpty())
            assertFalse(sb.workspaceDir("thread").resolve("no.png").exists())
        } finally { sb.root.deleteRecursively() }
    }

    @Test fun screenshotCannotEscapeTheConversationWorkspaceOrFollowSymlinks() = runTest {
        val sb = FakeSandbox()
        val outside = Files.createTempDirectory("screenshot-outside").toFile()
        try {
            val tools = toolDispatcher(FakeScreen(), sandbox = sb, workspace = { "thread" })
            assertEquals("path_outside_workspace", tools.execute(call("phone_screenshot", """{"savePath":"/data/private.png"}""")).content.s("error"))
            assertEquals("invalid_arguments", tools.execute(call("phone_screenshot", """{"savePath":"/workspace/not-png.jpg"}""")).content.s("error"))
            Files.createSymbolicLink(sb.workspaceDir("thread").resolve("linked").toPath(), outside.toPath())
            assertEquals("false", tools.execute(call("phone_screenshot", """{"savePath":"/workspace/linked/escaped.png"}""")).content.s("ok"))
            assertFalse(outside.resolve("escaped.png").exists())
        } finally { sb.root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun aSeeingModelStillReceivesItsImageWhenSavingIt() = runTest {
        val sb = FakeSandbox()
        try {
            val tools = toolDispatcher(FakeScreen(), visionEnabled = true, sandbox = sb, workspace = { "thread" })
            val result = tools.execute(call("phone_screenshot", """{"savePath":"/workspace/screen.png"}"""))
            assertEquals(1, result.images.size)
            assertEquals("/workspace/screen.png", result.content.s("path"))
        } finally { sb.root.deleteRecursively() }
    }

    @Test fun readOnlyRunDoesNotGainWorkspaceWritingThroughScreenshot() = runTest {
        val sb = FakeSandbox()
        try {
            val tools = toolDispatcher(FakeScreen(), visionEnabled = true, sandbox = sb, workspace = { "thread" },
                runSpec = RunSpec(RunId("read-only"), "look", "thread", readOnly = true))
            val result = tools.execute(call("phone_screenshot", """{"savePath":"/workspace/no.png"}"""))
            assertEquals("screenshot_save_unavailable", result.content.s("error"))
            assertFalse(sb.workspaceDir("thread").resolve("no.png").exists())
        } finally { sb.root.deleteRecursively() }
    }

    @Test fun readEntryPointRefusesOptionalScreenshotWritesOnAnOrdinaryRun() = runTest {
        val sb = FakeSandbox()
        try {
            var captures = 0
            val screen = object : ScreenPort by FakeScreen() {
                override suspend fun screenshotPng(): ByteArray { captures++; return byteArrayOf(1) }
            }
            val tools = toolDispatcher(screen, visionEnabled = true, sandbox = sb, workspace = { "thread" })
            val result = tools.executeRead(call("phone_screenshot", """{"savePath":"/workspace/no.png"}"""))
            assertEquals("not_a_read", result.content.s("error"))
            assertEquals(0, captures)
            assertFalse(sb.workspaceDir("thread").resolve("no.png").exists())
            assertEquals("true", tools.executeRead(call("phone_screenshot")).content.s("ok"))
            assertEquals(1, captures)
        } finally { sb.root.deleteRecursively() }
    }
}

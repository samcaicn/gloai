package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.BuiltinShellManager
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.shell.IMikaShell

/**
 * 真机测试:内置助手经无线调试起来、交接 Binder、在自己的虚拟屏上注入点击,解绑后助手留着、再绑定直接可用。
 * 前提:安卓 12+,无线调试开着。配对二选一:
 * - 带 `-e pairingCode <6 位码>`(可加 `-e pairingPort <端口>`),此时系统的「使用配对码配对」窗口要开着;
 * - 已经用 Mika 的钥匙配对过:带 `-e assumePaired true`。
 */
@RunWith(AndroidJUnit4::class)
class BuiltinHelperDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val phone = PhoneServices.of(context)
    private val engine = phone.shellManager.engine

    private fun helperUp(): IMikaShell = runBlocking {
        when {
            args.getString("pairingCode") != null && engine.status.value != BuiltinShellStatus.Running ->
                engine.startPairing(args.getString("pairingCode")!!, args.getString("pairingPort")?.toIntOrNull())
            else -> {
                if (args.getString("assumePaired") == "true") {
                    context.getSharedPreferences("builtin_shell", Context.MODE_PRIVATE).edit().putBoolean("paired", true).commit()
                }
                engine.retry()
                assumeTrue("not paired: pass -e pairingCode <code> or -e assumePaired true", engine.status.value != BuiltinShellStatus.NotPaired)
            }
        }
        assertEquals(BuiltinShellStatus.Running, engine.status.value)
        withTimeout(5_000) { phone.hub.shell.filterNotNull().first() }
    }

    private fun poll(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(100)
        }
        return check()
    }

    @Test
    fun fileBridgeReadsSharedStorageAndRejectsInvalidTargets() {
        val shell = helperUp()
        val dir = "/sdcard/Download/mika-file-bridge-${android.os.Process.myPid()}"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val hintPrefs = context.getSharedPreferences("device-hints-test-${android.os.Process.myPid()}", Context.MODE_PRIVATE)
        val hintStore = com.wangzan101.mika.settings.DeviceDirectoryStore(hintPrefs, android.os.Process.myUid() / 100000)
        val testHub = HelperHub(context, hintStore, shell)
        fun command(cmd: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).use { it.readBytes() }
        }
        try {
            // executeShellCommand 不经过 sh，不能直接用 ';' 和 '>'；借助 ${IFS} 在 sh -c 单参数里分隔。
            command("mkdir -p $dir/subdir")
            command("sh -c printf\${IFS}bridge>$dir/sample.txt")
            hintStore.save(com.wangzan101.mika.settings.DeviceDirectory(dir, "Test", "Files", "user"))
            assertTrue(testHub.syncDeviceFiles())
            assertEquals("denied", shell.listFiles(dir, null, 128).getString("status"))
            hintStore.setAuthorization(dir, true, true)
            assertTrue(testHub.syncDeviceFiles())
            assertEquals("denied", shell.listFiles("/sdcard/Download", null, 128).getString("status"))
            val listing = shell.listFiles(dir, null, 128)
            assertEquals("ok", listing.getString("status"))
            val names = listing.getStringArrayList("names")!!
            val types = listing.getIntArray("types")!!
            assertEquals(1, types[names.indexOf("sample.txt")])
            assertEquals(6L, listing.getLongArray("sizes")!![names.indexOf("sample.txt")])
            assertTrue(listing.getLongArray("modified")!![names.indexOf("sample.txt")] > 0)
            assertEquals(2, types[names.indexOf("subdir")])
            val opened = shell.openFile("$dir/sample.txt")
            assertEquals("ok", opened.getString("status"))
            val fd = opened.getParcelable<android.os.ParcelFileDescriptor>("fd")!!
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {
                assertEquals("bridge", it.readBytes().toString(Charsets.UTF_8))
            }
            val workspaceId = "device-file-test-${android.os.Process.myPid()}"
            val dirs = com.wangzan101.mika.cap.sandbox.SandboxDirs(context)
            val sandbox = com.wangzan101.mika.cap.sandbox.AndroidSandbox(context, com.wangzan101.mika.cap.sandbox.SandboxState.get(context))
            try {
                val importPrefs = context.getSharedPreferences("device-import-test-${android.os.Process.myPid()}", Context.MODE_PRIVATE)
                importPrefs.edit().clear().commit()
                val store = com.wangzan101.mika.task.DeviceImportStore(importPrefs)
                val deviceFiles = com.wangzan101.mika.task.AndroidDeviceFiles(testHub, dirs, sandbox, hintStore, store)
                val hint = runBlocking { deviceFiles.listDirectoryHints().single { it.path == dir } }
                assertEquals("ok", hint.status)
                val updated = runBlocking { deviceFiles.updateDirectoryDescription(dir, "Reports") }
                assertEquals("mika", updated.source)
                assertEquals("Reports", hintStore.directories().single { it.path == dir }.description)
                val copied = runBlocking { deviceFiles.importFile("$dir/sample.txt", workspaceId) }
                assertEquals("/workspace/inbox/sample.txt", copied.path)
                assertEquals(6L, copied.size)
                val key = WorkspaceKey.Conversation(workspaceId)
                dirs.gate.openRead(dirs.workspace(key), "inbox/sample.txt").use {
                    assertEquals("bridge", it.input().readBytes().toString(Charsets.UTF_8))
                }
                val record = com.wangzan101.mika.task.DeviceImportStore(importPrefs).list(workspaceId).single()
                assertEquals("$dir/sample.txt", record.sourcePath)
                assertEquals(copied.path, record.workspacePath)
                assertEquals(copied, runBlocking { deviceFiles.importFile("$dir/sample.txt", workspaceId) })
                assertEquals(record.id, store.list(workspaceId).single().id)
                java.io.File(dirs.workspace(key), "inbox/sample.txt").delete()
                val replacement = runBlocking { deviceFiles.importFile("$dir/sample.txt", workspaceId) }
                assertEquals(copied.path, replacement.path)
                assertEquals(2, store.list(workspaceId).size)
                importPrefs.edit().clear().commit()
            } finally {
                runBlocking { dirs.deleteWorkspace(WorkspaceKey.Conversation(workspaceId)) }
            }
            assertEquals("not_file", shell.openFile(dir).getString("status"))
            assertEquals("denied", shell.openFile("/dev/null").getString("status"))
            assertEquals("denied", shell.openFile("/data/data/file").getString("status"))
            assertEquals("not_found", shell.openFile("$dir/missing").getString("status"))
            assertEquals("not_directory", shell.listFiles("$dir/sample.txt", null, 128).getString("status"))
            assertEquals("invalid", shell.listFiles("relative", null, 128).getString("status"))
            val first = shell.listFiles(dir, null, 1)
            assertEquals(listOf("sample.txt"), first.getStringArrayList("names"))
            val second = shell.listFiles(dir, first.getString("next"), 1)
            assertEquals(listOf("subdir"), second.getStringArrayList("names"))
            assertNull(second.getString("next"))
            command("sh -c printf\${IFS}nested>$dir/subdir/Report.PDF")
            command("ln -s /data/local/tmp $dir/escape")
            command("ln -s $dir/subdir $dir/alias")
            if (shell.listFiles(dir, null, 128).getStringArrayList("names")!!.contains("alias")) {
                val invalidPolicy = shell.setDeviceFilePolicy(1, "test-link", 1, arrayOf("$dir/alias"), booleanArrayOf(true))
                assertEquals("invalid", invalidPolicy.getString("status"))
            }
            val search = shell.searchFiles(dir, "report", "pdf", -1, -1, 2, 100, 5000)
            assertEquals("ok", search.getString("status"))
            assertEquals(listOf("Report.PDF"), search.getStringArrayList("paths")!!.map { java.io.File(it).name })
            val future = shell.searchFiles(dir, "report", "pdf", System.currentTimeMillis() + 86400000, -1, 2, 100, 5000)
            assertTrue(future.getStringArrayList("paths")!!.isEmpty())
            assertEquals("invalid", shell.searchFiles(dir, null, null, 2, 1, 2, 100, 5000).getString("status"))
            val shallow = shell.searchFiles(dir, null, null, -1, -1, 0, 100, 5000)
            assertEquals("max_depth", shallow.getString("reason"))
            assertTrue(shallow.getBoolean("truncated"))
            val bounded = shell.searchFiles(dir, null, null, -1, -1, 2, 1, 5000)
            assertEquals("max_entries", bounded.getString("reason"))
            assertEquals(1, bounded.getInt("visited"))
            assertEquals("denied", shell.searchFiles("/data/local/tmp", null, null, -1, -1, 2, 10, 1000).getString("status"))
            hintStore.setAuthorization(dir, true, false)
            assertTrue(testHub.syncDeviceFiles())
            assertEquals("denied", shell.openFile("$dir/subdir/Report.PDF").getString("status"))
            assertEquals("ok", shell.openFile("$dir/sample.txt").also { it.getParcelable<android.os.ParcelFileDescriptor>("fd")?.close() }.getString("status"))
            hintStore.setAuthorization(dir, false, false)
            assertTrue(testHub.syncDeviceFiles())
            assertEquals("denied", shell.openFile("$dir/sample.txt").getString("status"))
            assertEquals("ok", shell.setDeviceFilePolicy(1, "test-revision", 2, arrayOf(dir), booleanArrayOf(true)).getString("status"))
            assertEquals("stale_policy", shell.setDeviceFilePolicy(1, "test-revision", 1, emptyArray(), booleanArrayOf()).getString("status"))
            assertEquals("stale_policy", shell.setDeviceFilePolicy(1, "test-revision", 2, emptyArray(), booleanArrayOf()).getString("status"))
        } finally {
            runCatching { hintStore.setAuthorization(dir, false, false); testHub.syncDeviceFiles() }
            hintPrefs.edit().clear().commit()
            command("rm -rf $dir")
        }
    }

    @Test
    fun helperRunsAsShellDrivesItsDisplayAndSurvivesUnbind() {
        assumeTrue(Build.VERSION.SDK_INT >= BuiltinShellManager.MIN_SDK)
        val shell = helperUp()
        val ids = shell.identity()
        assertEquals("helper uid", 2000, ids[0])
        assertTrue("helper pid", ids[1] > 0 && ids[1] != android.os.Process.myPid())

        val conn = BuiltinShellConnection(phone.hub) { engine.need() }
        val main = InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync { conn.bind() }
        val bound = runBlocking { withTimeout(5_000) { conn.shell.filterNotNull().first() } }
        assertSame(shell, bound)

        val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        val dm = context.getSystemService(DisplayManager::class.java)
        try {
            val displayId = bound.createDisplay(reader.surface, 720, 1280, 320)
            assertTrue("createDisplay -> $displayId", displayId > 0)
            assertNotNull("display $displayId not visible", dm.getDisplay(displayId))
            assertTrue("injectTap", bound.injectTap(displayId, 100, 100))

            // 解绑:屏被撤掉,和服务结束一样;助手进程和它的 Binder 还在
            main.runOnMainSync { conn.unbind() }
            assertNull(conn.shell.value)
            assertTrue("display not released on unbind", poll(5_000) { dm.getDisplay(displayId) == null })
            assertTrue("helper died on unbind", shell.asBinder().pingBinder())
            assertSame(shell, phone.hub.shell.value)

            // 再绑定:直接拿到同一个助手,还能接着用
            main.runOnMainSync { conn.bind() }
            val again = runBlocking { withTimeout(5_000) { conn.shell.filterNotNull().first() } }
            assertSame(shell, again)
            val second = again.createDisplay(reader.surface, 720, 1280, 320)
            assertTrue("createDisplay after rebind -> $second", second > 0)
            assertTrue(again.injectTap(second, 50, 50))
        } finally {
            main.runOnMainSync { conn.unbind() }
            reader.close()
        }
    }
}

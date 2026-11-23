package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.tool.ScriptResult
import com.wangzan101.mika.core.tool.Risk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginScriptRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun scriptFile(body: String): File {
        val f = File(tmp.root, "s-${System.nanoTime()}.sh")
        f.writeText("#!/bin/sh\n$body\n")
        return f
    }

    private fun argsFile(): File = File(tmp.root, "args-${System.nanoTime()}.json").apply { writeText("{}") }

    @Test fun runsShAndCapturesStdout() = runBlocking {
        val r = PluginScriptRunner(shell = "sh")
        val out: ScriptResult = r.run(scriptFile("echo '{\"ok\":true}'"), argsFile(), emptyMap(), 5000)
        assertTrue(out.stdout.contains("\"ok\":true"))
        assertEquals(0, out.exitCode)
        assertTrue(!out.timedOut)
    }

    @Test fun realShellTimeoutKills() = runBlocking {
        val r = PluginScriptRunner(shell = "sh")
        val out: ScriptResult = r.run(scriptFile("exec sleep 30"), argsFile(), emptyMap(), 300)
        assertTrue(out.timedOut)
    }

    @Test fun stderrCaptured() = runBlocking {
        val r = PluginScriptRunner(shell = "sh")
        val out: ScriptResult = r.run(scriptFile("echo 'boom' >&2; exit 1"), argsFile(), emptyMap(), 5000)
        assertEquals(1, out.exitCode)
        assertTrue(out.stderr.contains("boom"))
    }

    @Test fun toolSpecsMapEnabledPluginTools() {
        val files = tmp.newFolder("files")
        val dir = File(files, "plugins/com.mika.purge-lite")
        dir.mkdirs()
        File(dir, "manifest.json").writeText(
            """{"schema":1,"id":"com.mika.purge-lite","name":"x","version":"1.0.0","kind":"capability","permissions":["run-scripts"],"tools":[{"name":"scan","description":"d","script":"scripts/scan.sh"},{"name":"patch","description":"d","script":"scripts/patch.sh","risk":"write"}],"skills":[]}"""
        )
        val registry = PluginRegistry(files, CoroutineScope(Dispatchers.Unconfined), appVersion = "0.1.45")
        val specs = registry.toolSpecs()
        assertEquals(listOf("plugin_com_mika_purge-lite_scan", "plugin_com_mika_purge-lite_patch"), specs.map { it.name })
        assertEquals(Risk.Read, specs[0].risk)
        assertEquals(Risk.Write, specs[1].risk)
        assertEquals("scripts/scan.sh", specs[0].scriptPath)
    }

    @Test fun readCappedLimitsOutput() {
        val input = java.io.ByteArrayInputStream("a".repeat(1000).toByteArray())
        assertEquals(100, readCapped(input, 100).length)
    }
}

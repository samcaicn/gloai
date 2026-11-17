package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.plugin.ScriptResult
import com.wangzan101.mika.core.plugin.ScriptRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginPromptHookTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun registry(): PluginRegistry {
        val files = tmp.newFolder("files")
        val dir = File(files, "plugins/com.mika.purge-lite")
        dir.mkdirs()
        File(dir, "manifest.json").writeText(
            """{"schema":1,"id":"com.mika.purge-lite","name":"x","version":"1.0.0","kind":"capability","permissions":["rewrite-prompt"],"promptTransform":"scripts/rewrite_prompt.sh","skills":[]}"""
        )
        return PluginRegistry(files, CoroutineScope(Dispatchers.Unconfined), appVersion = "0.1.45")
    }

    private class FakeRunner(private val onStdin: (String) -> ScriptResult) : ScriptRunner {
        var lastStdin: String? = null
        var lastEnv: Map<String, String>? = null
        override suspend fun run(script: File, argsFile: File, env: Map<String, String>, timeoutMs: Long): ScriptResult =
            ScriptResult(1, "", "unused", false)
        override suspend fun runStdin(script: File, stdin: String, env: Map<String, String>, timeoutMs: Long): ScriptResult {
            lastStdin = stdin
            lastEnv = env
            return onStdin(stdin)
        }
    }

    @Test fun transformPassesPromptThroughStdin() = runBlocking {
        val runner = FakeRunner { stdin -> ScriptResult(0, "PREFIX\n$stdin", "", false) }
        val hook = PluginPromptHook(registry(), runner, { "fake-model" })
        assertEquals("PREFIX\nhello", hook.transform("hello"))
        assertEquals("hello", runner.lastStdin)
    }

    @Test fun timeoutFallsBackToOriginal() = runBlocking {
        val runner = FakeRunner { ScriptResult(-1, "", "", timedOut = true) }
        val hook = PluginPromptHook(registry(), runner, { "fake-model" })
        assertEquals("hello", hook.transform("hello"))
    }

    @Test fun nonzeroExitFallsBack() = runBlocking {
        val runner = FakeRunner { ScriptResult(1, "garbage", "boom", false) }
        val hook = PluginPromptHook(registry(), runner, { "fake-model" })
        assertEquals("hello", hook.transform("hello"))
    }

    @Test fun modelNamePassedToEnv() = runBlocking {
        val runner = FakeRunner { stdin -> ScriptResult(0, stdin, "", false) }
        val hook = PluginPromptHook(registry(), runner, { "fake-model" })
        hook.transform("hello")
        assertEquals("fake-model", runner.lastEnv?.get("MIKA_MODEL"))
    }
}

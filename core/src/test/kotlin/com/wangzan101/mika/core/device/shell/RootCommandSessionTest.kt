package com.wangzan101.mika.core.device.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

class RootCommandSessionTest {
    @Test
    fun scriptBuildsAGroupSupervisorWithCapabilityFallbacks() {
        val script = RootCommandSession.androidScript

        assertTrue(script.contains("[ -x /system/bin/setsid ] && /system/bin/setsid -w"))
        assertTrue(script.contains("/system/bin/toybox setsid -w"))
        assertTrue(script.contains("command -v kill"))
        assertTrue(script.contains("group=\$\$"))
        assertTrue(script.contains("exec 3<&0"))
        assertTrue(script.contains("\"\$3\" -c \"\$1\" </dev/null 3<&- &"))
        assertTrue(script.contains("\$2 -s KILL -- \"-\$group\""))
        assertTrue(script.contains("exit \"\$status\""))
        assertTrue(script.endsWith("exec \"/system/bin/sh\" -c \"\$c\" </dev/null"))
    }

    @Test
    fun missingSetsidFallsBackWithShellBuiltinKill() = assertFallback(listOf(listOf("kill")))

    @Test
    fun missingCapabilitiesFallBackWithoutChangingTheExitCode() = assertFallback(listOf(listOf("/not/a/system/kill")))

    @Test
    fun normalExitStopsTheWatcherAndPreservesTheExitCode() = withSession(
        command = "exit 7",
        kill = listOf(listOf("kill")),
    ) { process ->
        assertTrue("watcher blocked normal exit", process.waitFor(5, SECONDS))
        assertEquals(7, process.exitValue())
    }

    @Test
    fun closingControlInputStopsTheShellAndItsNestedChild() = withSession(
        command = "sleep 30 & echo \$!; wait",
        fork = true,
    ) { process ->
        val stdout = process.inputStream.bufferedReader()
        val child = CompletableFuture.supplyAsync { stdout.readLine() }.get(5, SECONDS).toLong()
        try {
            process.outputStream.close()
            assertTrue("root shell still running", process.waitFor(5, SECONDS))
            assertTrue("cancellation reported success", process.exitValue() != 0)
            val stat = File("/proc/$child/stat")
            val deadline = System.nanoTime() + SECONDS.toNanos(2)
            while (true) {
                val state = runCatching { stat.readText().substringAfterLast(')').trim().first() }.getOrNull()
                if (state == null || state == 'Z') break
                assertTrue("child still running: $state", System.nanoTime() < deadline)
                MILLISECONDS.sleep(20)
            }
        } finally {
            ProcessHandle.of(child).ifPresent { if (it.isAlive) it.destroyForcibly() }
        }
    }

    private fun assertFallback(kill: List<List<String>>) {
        val process = ProcessBuilder(
            "/bin/sh", "-c",
            RootCommandSession.script(shell = "/bin/sh", setsid = emptyList(), kill = kill),
        ).apply { environment()["c"] = "exit 7" }.start()
        try {
            process.outputStream.close()
            assertTrue("fallback blocked normal exit", process.waitFor(5, SECONDS))
            assertEquals(process.errorStream.bufferedReader().readText(), 7, process.exitValue())
        } finally {
            process.outputStream.close()
            if (!process.waitFor(1, SECONDS)) process.destroyForcibly()
        }
    }

    private fun withSession(
        command: String,
        fork: Boolean = false,
        kill: List<List<String>> = listOf(listOf("/bin/kill"), listOf("kill")),
        test: (Process) -> Unit,
    ) {
        val shell = "/bin/sh"
        val setsid = "/usr/bin/setsid"
        assumeTrue(File("/proc/self/stat").isFile && File(shell).canExecute() && File(setsid).canExecute())
        val probe = ProcessBuilder(setsid, "-w", shell, "-c", "exit 0").start()
        assumeTrue(probe.waitFor(5, SECONDS) && probe.exitValue() == 0)
        val launch = listOf(setsid) + if (fork) listOf("-f") else emptyList()
        val process = ProcessBuilder(shell, "-c", RootCommandSession.script(shell, listOf(launch), kill))
            .apply { environment()["c"] = command }.start()
        try {
            test(process)
        } finally {
            process.outputStream.close()
            if (!process.waitFor(1, SECONDS)) process.destroyForcibly()
        }
    }
}

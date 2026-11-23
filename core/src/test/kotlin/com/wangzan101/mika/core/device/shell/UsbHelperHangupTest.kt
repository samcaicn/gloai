package com.wangzan101.mika.core.device.shell

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Executes the generated script with stand-ins for Android's shell identity and app_process. */
class UsbHelperHangupTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test(timeout = 15_000)
    fun usbHelperSurvivesHangupAndReceivesTheNonceOnStdin() {
        assumeTrue("requires a Linux host", System.getProperty("os.name").startsWith("Linux"))
        val tools = ProcessBuilder("sh", "-c", "command -v nohup >/dev/null && command -v setsid >/dev/null")
            .start()
        assumeTrue("requires nohup and setsid", tools.waitFor(5, TimeUnit.SECONDS) && tools.exitValue() == 0)

        val dir = temporary.root
        val bin = temporary.newFolder("bin")
        fun executable(name: String, body: String) {
            File(bin, name).apply {
                writeText("#!/bin/sh\n$body\n")
                assertTrue("make $name executable", setExecutable(true))
            }
        }
        executable("id", """[ "${'$'}1" = -u ] || exit 1; printf '2000\n'""")
        executable("pidof", """[ -f "${'$'}MIKA_TEST_DIR/pid" ] || exit 1; cat "${'$'}MIKA_TEST_DIR/pid";""")
        executable(
            "app_process",
            """
            IFS= read -r received || exit 1
            printf '%s' "${'$'}received" > "${'$'}MIKA_TEST_DIR/nonce"
            printf '%s' "${'$'}${'$'}" > "${'$'}MIKA_TEST_DIR/pid"
            tick=0
            while :; do
                tick=${'$'}((tick + 1))
                printf '%s' "${'$'}tick" > "${'$'}MIKA_TEST_DIR/heartbeat.tmp"
                mv "${'$'}MIKA_TEST_DIR/heartbeat.tmp" "${'$'}MIKA_TEST_DIR/heartbeat"
                sleep 0.05
            done
            """.trimIndent(),
        )
        val nonce = "once-only-test-nonce"
        val apk = temporary.newFile("base app's.apk")
        val script = File(dir, "usb-start.sh").apply {
            writeText(HelperHandoff.usbScript(apk.path, "com.wangzan101.mika", 10234, "pin", nonce, path))
        }
        val pidFile = File(dir, "pid")
        fun helperPid(): Long? = runCatching { pidFile.readText().toLong() }.getOrNull()
        fun heartbeat(): Long = runCatching { File(dir, "heartbeat").readText().toLong() }.getOrDefault(-1)
        val launcher = ProcessBuilder("sh", script.path).apply {
            environment()["PATH"] = bin.path + File.pathSeparator + environment()["PATH"]
            environment()["MIKA_TEST_DIR"] = dir.path
            redirectErrorStream(true)
        }.start()
        launcher.outputStream.close()
        try {
            assertTrue("startup script finishes", launcher.waitFor(6, TimeUnit.SECONDS))
            val output = launcher.inputStream.bufferedReader().readText()
            assertEquals(output, 0, launcher.exitValue())
            assertTrue("helper starts making progress", await { heartbeat() > 0 })
            assertEquals(nonce, File(dir, "nonce").readText())
            val pid = requireNotNull(helperPid())
            val arguments = ProcessHandle.of(pid).orElseThrow().info().arguments().orElseThrow()
            assertFalse("nonce must not be in process arguments", arguments.any { nonce in it })

            val before = heartbeat()
            val hangup = ProcessBuilder("sh", "-c", """kill -HUP "${'$'}1";""", "mika-hangup-test", pid.toString()).start()
            assertTrue("send HUP", hangup.waitFor(3, TimeUnit.SECONDS))
            assertEquals("send HUP", 0, hangup.exitValue())
            assertTrue("helper keeps working after HUP", await { heartbeat() >= before + 3 })
        } finally {
            if (launcher.isAlive) launcher.destroyForcibly()
            helperPid()?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}

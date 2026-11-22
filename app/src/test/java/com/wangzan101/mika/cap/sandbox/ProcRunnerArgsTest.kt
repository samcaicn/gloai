package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

class ProcRunnerArgsTest {
    private val dirs = SandboxDirs(File("/data/m/files/sandbox"), File("/data/m/cache/proot-tmp"), File("/data/app/lib/arm64"), JVM_GATE, SandboxTree())
    private val ws = dirs.workspace(WorkspaceKey.Conversation("conv1"))

    private fun List<String>.hasPair(a: String, b: String) = indices.any { it + 1 < size && this[it] == a && this[it + 1] == b }

    @Test fun argvOrderAndBinds() {
        val argv = ProcRunner.argv(dirs, ws, "sub/dir", 60, network = false, standIns = listOf("stat"),
            command = listOf("/bin/sh", "-lc", "echo hi"), devices = listOf("-b", "/dev/null"))
        assertEquals("/data/app/lib/arm64/libmikarun.so", argv[0])
        assertTrue(argv.hasPair("--timeout", "60"))
        assertTrue(argv.hasPair("--net", "off"))
        val proot = argv.indexOf("/data/app/lib/arm64/libproot.so")
        assertEquals("--", argv[proot - 1])
        for (flag in listOf("--kill-on-exit", "--link2symlink", "-0")) assertTrue(flag, argv.indexOf(flag) > proot)
        assertTrue(argv.hasPair("-r", "/data/m/files/sandbox/rootfs"))
        assertTrue(argv.hasPair("-b", "/dev/null"))
        assertFalse("whole /dev must not be bound", argv.hasPair("-b", "/dev"))
        assertTrue(argv.hasPair("-b", "/data/m/files/sandbox/proc/stat:/proc/stat"))
        assertTrue(argv.hasPair("-b", "/data/m/files/sandbox/work/c-conv1:/workspace"))
        assertTrue(argv.hasPair("-b", "/data/m/files/sandbox/skills:/opt/mika/skills"))
        assertTrue(argv.hasPair("-b", "/data/m/files/sandbox/mcp-cache:/opt/mika/mcp-cache"))
        assertTrue(argv.hasPair("-w", "/workspace/sub/dir"))
        assertEquals(listOf("/bin/sh", "-lc", "echo hi"), argv.takeLast(3))
        assertTrue(argv.indexOf("-w") < argv.size - 3)

        val root = ProcRunner.argv(dirs, ws, "", 5, network = true, standIns = emptyList(), command = listOf("true"), devices = emptyList())
        assertTrue(root.hasPair("-w", "/workspace"))
        assertTrue(root.hasPair("--net", "on"))
    }

    @Test fun shellArgvUsesPtyAndNoTimeout() {
        val argv = ProcRunner.argv(
            dirs, ws, "", 0, network = false, standIns = emptyList(), command = listOf("/bin/sh"),
            devices = emptyList(), pty = true, ptyCols = 100, ptyRows = 30,
        )
        assertTrue(argv.contains("--pty"))
        assertTrue(argv.hasPair("--pty-cols", "100"))
        assertTrue(argv.hasPair("--pty-rows", "30"))
        assertFalse("terminal sessions have no time limit", argv.contains("--timeout"))
        assertTrue(argv.hasPair("--net", "off"))
        assertEquals("/bin/sh", argv.last())
    }

    @Test fun shellArgvPassesSizeFile() {
        val size = File(java.io.File("/tmp").path, "pty.size")
        val argv = ProcRunner.argv(
            dirs, ws, "", 0, network = true, standIns = emptyList(), command = listOf("/bin/sh"),
            devices = emptyList(), pty = true, ptySizePath = size,
        )
        assertTrue(argv.hasPair("--pty-size", size.path))
        assertTrue(argv.contains("--pty"))
    }

    @Test fun shellEnvHasTerminalSettings() {
        val env = ProcRunner.env(dirs, null, null, "conv1", pty = true)
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("/dev/null", env["HISTFILE"])
        assertTrue(env.keys.none { it == "MIKA_BRIDGE" || it == "MIKA_TOKEN" })
    }

    @Test fun envIsMinimal() {
        val env = ProcRunner.env(dirs, null, null, "conv1")
        assertEquals(setOf("PROOT_LOADER", "PROOT_TMP_DIR", "HOME", "PATH", "LANG", "TMPDIR", "TZ", "MIKA_CONVERSATION"), env.keys)
        assertEquals("/data/app/lib/arm64/libproot_loader.so", env["PROOT_LOADER"])
        assertEquals("/data/m/cache/proot-tmp", env["PROOT_TMP_DIR"])
        assertTrue(env.keys.none { it == "LD_PRELOAD" || it.startsWith("ANDROID_") })
        val bridged = ProcRunner.env(dirs, "mika-bridge-x", "tok", "conv1")
        assertEquals("mika-bridge-x", bridged["MIKA_BRIDGE"]); assertEquals("tok", bridged["MIKA_TOKEN"])
    }

    /** 解析出的 stdio 秘密环境只在显式 allowlist 上追加:保留 PATH/HOME/TZ 等基础项,不继承宿主/App 环境,非法键与 NUL 拒绝。 */
    @Test fun sandboxProcessEnvironmentUsesExplicitAllowlist() {
        val env = ProcRunner.env(dirs, null, null, "conv1", extra = mapOf("SECRET_API_KEY" to "k", "REGION" to "cn"))
        // 基础项仍在
        assertEquals("/root", env["HOME"])
        assertEquals(ProcRunner.PATH, env["PATH"])
        assertTrue(env.containsKey("TZ"))
        // 只追加解析出的环境
        assertEquals("k", env["SECRET_API_KEY"])
        assertEquals("cn", env["REGION"])
        // 不继承宿主 / App 环境:键只落在显式 allowlist 内
        assertTrue(env.keys.none { it == "LD_PRELOAD" || it.startsWith("ANDROID_") || it == "JAVA_HOME" })
        // 非法键(格式不符)与 NUL 值拒绝;不允许覆盖基础项
        for (bad in listOf("1BAD", "A-B", "A.B", "PATH")) {
            assertThrows(IllegalArgumentException::class.java) {
                ProcRunner.env(dirs, null, null, "conv1", extra = mapOf(bad to "v"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcRunner.env(dirs, null, null, "conv1", extra = mapOf("OK" to "a\u0000b"))
        }
    }

    /** 代理只在给了时出现:大小写两套变量都给(有的工具只认大写),本机地址照旧直连。 */
    @Test fun proxyIsPassedOnlyWhenGiven() {
        val none = ProcRunner.env(dirs, null, null, "conv1", proxy = "")
        assertTrue(none.keys.none { it.lowercase().endsWith("_proxy") })
        val p = "http://u:pw@10.0.0.2:8080"
        val env = ProcRunner.env(dirs, null, null, "conv1", proxy = p)
        for (k in listOf("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")) assertEquals(k, p, env[k])
        assertEquals(ProcRunner.NO_PROXY, env["no_proxy"])
        assertEquals(ProcRunner.NO_PROXY, env["NO_PROXY"])
    }

    @Test fun envReadsPhoneTimezoneForEveryCommand() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Shanghai", "America/New_York", "Asia/Kathmandu", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(zone, ProcRunner.env(dirs, null, null, "conv1")["TZ"])
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun fixedOffsetTimezonesKeepTheirSign() {
        // POSIX 的 TZ 里 GMT+8 表示比 UTC 慢 8 小时,不能把 GMT+08:00 原样给出。
        val cases = mapOf(
            "GMT+08:00" to "Etc/GMT-8", "GMT-05:00" to "Etc/GMT+5", "GMT+05:45" to "<+0545>-05:45",
            "GMT-03:30" to "<-0330>03:30", "GMT" to "UTC", "UTC" to "UTC",
        )
        for ((id, tz) in cases) assertEquals(id, tz, ProcRunner.timezone { TimeZone.getTimeZone(id).toZoneId() })
        assertEquals("Etc/GMT-14", ProcRunner.timezone { java.time.ZoneOffset.ofHours(14) })
        assertEquals("UTC", ProcRunner.timezone { throw java.time.DateTimeException("no zone") })
    }

    @Test fun oddConversationIdsStayInsideWork() {
        for (id in listOf("../../etc", "a/b", "", "对话一", "p:abc")) {
            val f = dirs.workspace(WorkspaceKey.Conversation(id))
            assertEquals(id, dirs.work, f.parentFile)
            assertFalse(id, f.name.contains(".."))
        }
        assertEquals(dirs.workspace(WorkspaceKey.Conversation("x/y")), dirs.workspace(WorkspaceKey.Conversation("x/y")))
        // 简单名字和哈希名字分在两个前缀下:「h-…」样子的 ID 不会撞上别的对话的哈希目录;项目工作区固定为 p-<id>
        val hashed = dirs.workspace(WorkspaceKey.Conversation("a/b")).name
        assertTrue(hashed, hashed.startsWith("h-"))
        assertEquals("c-$hashed", dirs.workspace(WorkspaceKey.Conversation(hashed)).name)
        assertEquals("p-proj_1", dirs.workspace(WorkspaceKey.Project("proj_1")).name)
    }
}

package com.wangzan101.mika.core.device.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAccessTest {
    /** 先找 PATH 里的,再找常见的几处;都没有为 null。 */
    @Test
    fun findsSuOnPathThenInUsualPlaces() {
        assertEquals("/product/bin/su", SuProbe.find("/product/bin:/system/bin") { it == "/product/bin/su" || it == "/system/bin/su" })
        assertEquals("/system/xbin/su", SuProbe.find(null) { it == "/system/xbin/su" })
        assertEquals("/debug_ramdisk/su", SuProbe.find("", setOf("/debug_ramdisk/su")::contains))
        assertNull(SuProbe.find("/system/bin:/vendor/bin") { false })
        // PATH 里的相对路径、空段不算;结尾的斜杠去掉
        assertEquals("/system/bin/su", SuProbe.find("bin::/system/bin/") { it == "bin/su" || it == "/system/bin/su" })
    }

    @Test
    fun readsTheUidFromIdOutput() {
        assertEquals(0, SuProbe.uid("uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0"))
        assertEquals(2000, SuProbe.uid("uid=2000(shell) gid=2000(shell)"))
        assertEquals(2000, SuProbe.uid("2000\n"))
        assertEquals(0, SuProbe.uid("some banner\n0\n"))
        assertNull(SuProbe.uid(""))
        assertNull(SuProbe.uid("Permission denied"))
    }

    /** 答复:uid 对就是答应了;uid 不对是切不过去;没读到 uid 时看是不是到了时限。 */
    @Test
    fun answerFromOutputAndTimeout() {
        assertEquals(SuAnswer.Granted, SuProbe.answer("uid=0(root)", timedOut = false, expectUid = 0))
        assertEquals(SuAnswer.Granted, SuProbe.answer("2000", timedOut = false, expectUid = 2000))
        assertEquals(SuAnswer.WrongUid, SuProbe.answer("0", timedOut = false, expectUid = 2000))
        assertEquals(SuAnswer.Denied, SuProbe.answer("Permission denied", timedOut = false, expectUid = 0))
        assertEquals(SuAnswer.Denied, SuProbe.answer("", timedOut = false, expectUid = 0))
        assertEquals(SuAnswer.NoAnswer, SuProbe.answer("", timedOut = true, expectUid = 0))
    }

    /** 以 shell 身份跑时用户参数写在 -c 前面;命令是一个参数,不拆开。 */
    @Test
    fun suArguments() {
        assertEquals(listOf("/system/bin/su", "-c", "ls -l /data"), SuProbe.rootArgs("/system/bin/su", "ls -l /data"))
        assertEquals(listOf("/sbin/su", "2000", "-c", "id -u; read n"), SuProbe.shellArgs("/sbin/su", "id -u; read n"))
    }

    /** 用户请求、su 在不在、明确授权结果 → 能不能自动用 root 起助手;没有答复时绝不自动执行 su。 */
    @Test
    fun rootStartGate() {
        assertEquals(RootStart.Off, RootStart.of(enabled = false, suPresent = true, last = SuAnswer.Granted))
        assertEquals(RootStart.Off, RootStart.of(enabled = true, suPresent = true, last = null))
        assertEquals(RootStart.Off, RootStart.of(enabled = true, suPresent = false, last = null))
        assertEquals(RootStart.Ready, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.Granted))
        assertEquals(RootStart.Gone, RootStart.of(enabled = true, suPresent = false, last = SuAnswer.Granted))
        assertEquals(RootStart.Denied, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.Denied))
        assertEquals(RootStart.NoAnswer, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.NoAnswer))
        assertEquals(RootStart.NoShellUid, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.WrongUid))
        assertNull(RootStart.Off.failure)
        assertNull(RootStart.Ready.failure)
        assertEquals(BuiltinShellFailure.RootDenied, RootStart.Denied.failure)
        assertEquals(BuiltinShellFailure.RootUnavailable, RootStart.Gone.failure)
        // 拒绝、不回话可以重试;su 没了或切不过去要换别的办法
        assertEquals(BuiltinShellFix.Retry, BuiltinShellFailure.RootDenied.defaultFix)
        assertEquals(BuiltinShellFix.Retry, BuiltinShellFailure.RootNoAnswer.defaultFix)
        assertEquals(BuiltinShellFix.RePair, BuiltinShellFailure.RootUnavailable.defaultFix)
        assertTrue(!BuiltinShellFailures.retryable(BuiltinShellFailure.RootDenied))
    }

    @Test
    fun grantStateFollowsTheAnswer() {
        assertEquals(RootGrant.Unknown, RootGrant.of(null))
        assertEquals(RootGrant.Granted, RootGrant.of(SuAnswer.Granted))
        assertEquals(RootGrant.Denied, RootGrant.of(SuAnswer.Denied))
        assertEquals(RootGrant.NoAnswer, RootGrant.of(SuAnswer.NoAnswer))
        assertEquals(RootGrant.NoShellUid, RootGrant.of(SuAnswer.WrongUid))
    }

    /** 经 su 起助手的命令:先打 uid,再和经无线调试起的一样(口令不在命令里)。 */
    @Test
    fun rootCommandPrintsTheUidFirstAndKeepsTheNonceOffTheCommandLine() {
        val c = HelperHandoff.rootCommand("/data/app/x/base.apk", "com.wangzan101.mika", 10123, "ab")
        assertTrue(c.startsWith("id -u; read n; "))
        assertEquals(c, "id -u; " + HelperHandoff.command("/data/app/x/base.apk", "com.wangzan101.mika", 10123, "ab"))
    }
}

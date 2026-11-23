package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.ToolInfo
import com.wangzan101.mika.core.tool.Verb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteShareTest {
    private val tap = RouteStep("phone_tap", """{"ref":"@e1","snapshotId":"snap1"}""",
        Selector("button", "签到", "jd:id/sign", listOf(10, 20, 30, 40), 0, uniqueAtRecord = true),
        Anchors("com.jingdong", listOf("我的")), Anchors("com.jingdong", listOf("签到成功")))
    private val type = RouteStep("phone_type_text", """{"text":"{1}"}""",
        Selector("edittext", "", "jd:id/search", listOf(0, 0, 900, 60), 0),
        Anchors("com.jingdong", listOf("首页")), Anchors("com.jingdong", listOf("首页")))

    private fun route(vararg steps: RouteStep) = Route(
        id = "r1", template = "去京东签到", slots = 0, packages = listOf("com.jingdong"),
        appVersions = mapOf("com.jingdong" to "12.0"), steps = steps.toList(), lastUsedMs = 0L,
    )

    private val tools = ToolIndex(listOf(
        info("phone_tap", Verb.Tap, routable = true),
        info("phone_type_text", Verb.TypeText, routable = true),
        info("phone_run", Verb.PhoneRun, routable = false),
    ))

    private fun info(name: String, verb: Verb, routable: Boolean) = object : ToolInfo {
        override val name = name
        override val verb = verb
        override val capability = "phone"
        override val batchable = false
        override val routable = routable
        override val label = null
    }

    /** 指纹认「同一条路」的口径与 [samePath] 一致:锚点不算,工具/参数/目标算。 */
    @Test fun fingerprintIgnoresAnchorsAndAgreesWithSamePath() {
        val a = route(tap)
        val otherAnchors = a.copy(steps = listOf(
            tap.copy(before = Anchors("com.jingdong", listOf("首页")), after = Anchors("com.jingdong", listOf("已签")))))
        assertEquals(RouteFingerprint.of(a), RouteFingerprint.of(otherAnchors))
        assertTrue(samePath(a, otherAnchors))
        // 模板、参数、目标、App 有一个不同就不同
        assertNotEquals(RouteFingerprint.of(a), RouteFingerprint.of(a.copy(template = "京东签到")))
        assertNotEquals(RouteFingerprint.of(a), RouteFingerprint.of(a.copy(steps = listOf(tap.copy(argsJson = "{}")))))
        assertNotEquals(RouteFingerprint.of(a), RouteFingerprint.of(a.copy(steps = listOf(
            tap.copy(target = tap.target!!.copy(ordinal = 1))))))
        assertNotEquals(RouteFingerprint.of(a), RouteFingerprint.of(a.copy(packages = listOf("com.jd"))))
        // 包名顺序不影响指纹
        assertEquals(RouteFingerprint.of(a.copy(packages = listOf("a", "b"))), RouteFingerprint.of(a.copy(packages = listOf("b", "a"))))
    }

    @Test fun roundtripKeepsContentAndPassesLint() {
        val share = RouteShare.of(route(tap, type), "京东签到", "打开京东,点签到")
        val back = RouteShare.decode(share.encode())
        assertEquals(share, back)
        assertEquals(share.fingerprint, RouteFingerprint.of(back.route))
        assertEquals(emptyList<String>(), RouteShareLint.check(back, tools))
    }

    @Test fun lintRejectsUnknownNonRoutableAndTamperedContent() {
        val unknown = RouteShare.of(route(tap.copy(tool = "no_such_tool")), "x")
        assertEquals(listOf("step 1: unknown tool no_such_tool"), RouteShareLint.check(unknown, tools))
        val notRoutable = RouteShare.of(route(tap.copy(tool = "phone_run")), "x")
        assertEquals(listOf("step 1: phone_run is not a replayable action"), RouteShareLint.check(notRoutable, tools))
        // 内容被改过而指纹没跟着算:拒绝
        val tampered = RouteShare.decode(RouteShare.of(route(tap), "x").encode().replace("签到", "已签"))
        assertTrue(RouteShareLint.check(tampered, tools).any { it.startsWith("fingerprint") })
        // 停用的、模板空的、参数不是 JSON 对象的
        val disabled = RouteShare.of(route(tap).copy(disabled = true), "x")
        assertTrue(RouteShareLint.check(disabled, tools).any { it.contains("disabled") })
        val blank = RouteShare.of(route(tap).copy(template = " "), "x")
        assertTrue(RouteShareLint.check(blank, tools).any { it.contains("template") })
        val badArgs = RouteShare.of(route(tap.copy(argsJson = "not json")), "x")
        assertTrue(RouteShareLint.check(badArgs, tools).any { it.contains("not a JSON object") })
    }

    /** 路线实际碰到的 App 从锚点里取;审核据此核对「步骤没走到声明的 App 之外」。 */
    @Test fun touchedPackagesComesFromAnchors() {
        val share = RouteShare.of(route(tap, type), "京东签到")
        assertEquals(setOf("com.jingdong"), RouteShareLint.touchedPackages(share))
        val extra = share.copy(route = share.route.copy(steps = listOf(
            tap, type.copy(before = Anchors("com.other", listOf("首页"))))))
        assertEquals(setOf("com.jingdong", "com.other"), RouteShareLint.touchedPackages(extra))
        // 最后一步点完跳进别的 App(比如点了分享):这一步之前在原 App,之后在别的 App,后面没有步骤了,别的 App 也算碰到
        val jumps = share.copy(route = share.route.copy(steps = listOf(
            type, tap.copy(target = tap.target!!.copy(name = "分享"), after = Anchors("com.other", listOf("分享到"))))))
        assertEquals(setOf("com.jingdong", "com.other"), RouteShareLint.touchedPackages(jumps))
    }

    @Test fun lintRejectsOversizedShares() {
        val big = RouteShare.of(route(tap.copy(argsJson = """{"pad":"${"x".repeat(MAX_ROUTE_BYTES)}"}""")), "x")
        val problems = RouteShareLint.check(big, tools)
        assertTrue(problems.any { it.contains("exceeds") })
    }

    @Test fun unknownSchemaIsRejected() {
        val current = RouteShare.of(route(tap), "x")
        assertEquals(current, RouteShare.decode(current.encode()))
        try {
            RouteShare.decode(current.encode().replace("\"schema\":1", "\"schema\":99"))
            org.junit.Assert.fail("unknown schema must be rejected")
        } catch (e: IllegalArgumentException) {
            // 认不出的版本不收
        }
    }
}

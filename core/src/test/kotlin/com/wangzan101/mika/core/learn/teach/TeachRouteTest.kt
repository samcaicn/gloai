package com.wangzan101.mika.core.learn.teach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachRouteTest {
    private val launcher = "com.android.launcher3"

    private fun step(
        id: Int,
        pkg: String?,
        kind: TeachKind,
        node: String? = null,
        title: String? = null,
    ) = TeachStep(
        id = id,
        atMs = id * 1_000L,
        packageName = pkg,
        window = title,
        kind = kind,
        nodeName = node,
        viewId = null,
        insufficient = false,
        sensitiveOmitted = false,
        needsClarification = false,
    )

    private fun fold(
        steps: List<TeachStep>,
        launchers: Set<String> = setOf(launcher),
        browsers: Set<String> = emptySet(),
    ) = TeachRoute.fold(steps, launchers, browsers) { it }

    @Test fun foldsLauncherStepsIntoOneDirectLaunch() {
        val steps = listOf(
            step(0, launcher, TeachKind.Window, title = "桌面"),
            step(1, launcher, TeachKind.Click, node = "小红书"),
            step(2, "com.xingin.xhs", TeachKind.Window, title = "首页"),
            step(3, "com.xingin.xhs", TeachKind.Click, node = "搜索"),
        )
        val route = fold(steps)
        assertEquals(2, route.steps.size)
        assertTrue(route.steps[0].text.startsWith("启动"))
        assertTrue(route.steps[1].text.contains("点了「搜索」"))
        assertEquals(2, route.folded.size)
        assertTrue(route.folded.any { it.contains("小红书") })
        assertEquals(listOf("com.xingin.xhs"), route.apps.map { it.packageName })
        assertFalse(route.apps.single().browser)
    }

    @Test fun appSwitchMidDemoBecomesOneSwitchStep() {
        val steps = listOf(
            step(0, "com.a", TeachKind.Click, node = "首页"),
            step(1, launcher, TeachKind.Window, title = "桌面"),
            step(2, launcher, TeachKind.Click, node = "应用B"),
            step(3, "com.b", TeachKind.Window, title = "首页"),
            step(4, "com.b", TeachKind.Click, node = "开始"),
            step(5, launcher, TeachKind.Window, title = "桌面"),
            step(6, launcher, TeachKind.Click, node = "应用A"),
            step(7, "com.a", TeachKind.Click, node = "返回"),
        )
        val route = TeachRoute.fold(steps, setOf(launcher), emptySet()) { if (it == "com.a") "A" else "B" }
        assertEquals(listOf("com.a", "com.b"), route.apps.map { it.packageName })
        assertEquals(5, route.steps.size)
        assertTrue(route.steps[0].text.contains("点了「首页」"))
        assertEquals("改用 B", route.steps[1].text)
        assertTrue(route.steps[2].text.contains("点了「开始」"))
        assertEquals("回到 A", route.steps[3].text)
        assertTrue(route.steps[4].text.contains("点了「返回」"))
        assertEquals(4, route.folded.size)
    }

    @Test fun unknownLauncherLeavesEveryStepUntouched() {
        val steps = listOf(
            step(0, launcher, TeachKind.Window, title = "桌面"),
            step(1, launcher, TeachKind.Click, node = "小红书"),
            step(2, "com.xingin.xhs", TeachKind.Window, title = "首页"),
        )
        val route = fold(steps, launchers = emptySet())
        assertEquals(3, route.steps.size)
        assertEquals(steps.map { teachLine(it) }, route.steps.map { it.text })
        assertTrue(route.folded.isEmpty())
    }

    @Test fun demoStartedInsideAnAppDoesNotInventALaunchStep() {
        val steps = listOf(
            step(0, "com.xingin.xhs", TeachKind.Window, title = "首页"),
            step(1, "com.xingin.xhs", TeachKind.Click, node = "搜索"),
        )
        val route = fold(steps)
        assertEquals(1, route.steps.size)
        assertTrue(route.steps.none { it.kind == null })
        assertTrue(route.steps.single().text.contains("点了「搜索」"))
        assertTrue(route.folded.isEmpty())
    }

    @Test fun windowOnlyPackagesDoNotBecomeTheEntryApp() {
        val steps = listOf(
            step(0, launcher, TeachKind.Window, title = "桌面"),
            step(1, launcher, TeachKind.Click, node = "小红书"),
            step(2, "com.hihonor.hiboard", TeachKind.Window, title = "负一屏"),
            step(3, "com.xingin.xhs", TeachKind.Window, title = "首页"),
            step(4, "com.xingin.xhs", TeachKind.Click, node = "搜索"),
        )
        val route = fold(steps)
        assertEquals(listOf("com.xingin.xhs"), route.apps.map { it.packageName })
        assertEquals(2, route.steps.size)
        assertTrue(route.steps[0].text.startsWith("启动"))
        assertTrue(route.steps[1].text.contains("点了「搜索」"))
    }

    @Test fun marksTheDefaultBrowserForThePrompt() {
        val steps = listOf(step(0, "com.browser", TeachKind.Click, node = "搜索"))
        val route = fold(steps, browsers = setOf("com.browser"))
        assertTrue(route.apps.single().browser)
    }

    /** 只有窗口、没有点按的示范(浏览器整段收不到点击)折完没有任何真实操作,也不能凭空造出 App 入口。 */
    @Test fun aWindowOnlyDemoFoldsToNoRealAction() {
        val steps = listOf(
            step(0, launcher, TeachKind.Window, title = "桌面"),
            step(1, "com.android.edge", TeachKind.Window, title = "新标签页"),
            step(2, "com.android.edge", TeachKind.Window, title = "搜索结果"),
        )
        val route = fold(steps)
        assertTrue(route.steps.none { it.kind != null })
        assertTrue(route.apps.isEmpty())
        assertTrue(route.folded.isNotEmpty())
    }
}

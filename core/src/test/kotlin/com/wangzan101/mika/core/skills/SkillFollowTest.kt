package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.SkillPlaces
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 照着做了才算，声明了地点时必须有发生在该地点的成功动作。 */
class SkillFollowTest {
    private val tools = knownTools()
    private fun read(name: String, at: Long) = RunEvent.SkillUsed(at, name, "viewed")
    private fun act(at: Long, tool: String = "phone_tap", ok: Boolean = true) =
        RunEvent.ToolResult(at, 1, tool, if (ok) """{"ok":true}""" else """{"ok":false}""", ok, null)
    private fun saw(at: Long, place: Place) = RunEvent.Observed(at, 1, "界面", place = place)
    private fun followed(events: List<RunEvent>, places: Map<String, SkillPlaces> = emptyMap()) =
        SkillFollow.followed(events, events.filterIsInstance<RunEvent.SkillUsed>(), tools) { places[it] }

    @Test fun readThenActedCountsWhenNoAppsAreDeclared() {
        assertEquals(setOf("s"), followed(listOf(read("s", 10), act(20))))
    }

    @Test fun readingAfterTheWorkOrOnlyFailedActionsDoNotCount() {
        assertTrue(followed(listOf(act(5), read("s", 10))).isEmpty())
        assertTrue(followed(listOf(read("s", 10), act(20, ok = false))).isEmpty())
        // 读屏、查技能不是动手
        assertTrue(followed(listOf(read("s", 10), act(20, tool = "phone_observe"), act(21, tool = "skill_read"))).isEmpty())
    }

    @Test fun actionMustMatchDeclaredAppAfterReading() {
        val wechat = mapOf("s" to SkillPlaces(packages = setOf("com.tencent.mm")))
        val inWechat = act(20).copy(resultJson = """{"ok":true,"actionApp":"com.tencent.mm"}""")
        assertEquals(setOf("s"), followed(listOf(read("s", 10), inWechat), wechat))
        assertTrue(followed(listOf(read("s", 10), act(20), saw(21, Place.app("com.tencent.mm"))), wechat).isEmpty())
        // 进微信是在读技能之前,读完以后在别处动手
        assertTrue(followed(listOf(saw(5, Place.app("com.tencent.mm")), read("s", 10), act(20)), wechat).isEmpty())
    }

    @Test fun visitingSkillAppDoesNotAttributeAnActionInAnotherApp() {
        val wechat = mapOf("s" to SkillPlaces(packages = setOf("com.tencent.mm")))
        val unrelated = act(20).copy(resultJson = """{"ok":true,"screen":{"currentApp":"com.android.settings"}}""")
        assertTrue(followed(listOf(read("s", 10), saw(15, Place.app("com.tencent.mm")), unrelated), wechat).isEmpty())
    }

    @Test fun declaredSiteMatchesSubdomainsAndPages() {
        val bili = mapOf("s" to SkillPlaces(domains = setOf("bilibili.com")))
        val page = act(20, "web_navigate").copy(resultJson = """{"ok":true,"url":"https://m.bilibili.com/video/1"}""")
        assertEquals(setOf("s"), followed(listOf(read("s", 10), page), bili))
        val unrelated = page.copy(resultJson = """{"ok":true,"url":"https://notbilibili.com/video/1"}""")
        assertTrue(followed(listOf(read("s", 10), unrelated), bili).isEmpty())
    }

    @Test fun eachSkillIsJudgedOnItsOwn() {
        val places = mapOf("wx" to SkillPlaces(packages = setOf("com.tencent.mm")), "dy" to SkillPlaces(packages = setOf("com.ss.android.ugc.aweme")))
        val tap = act(20).copy(resultJson = """{"ok":true,"actionApp":"com.tencent.mm","screen":{"currentApp":"com.ss.android.ugc.aweme"}}""")
        val events = listOf(read("dy", 10), read("wx", 11), tap)
        assertEquals(setOf("wx"), followed(events, places))
    }

    // 界面一个字也读不出来时没有读屏记录:调用结果里报的 currentApp / currentSite 照样算进过
    @Test fun currentAppInToolResultsCountsAsVisiting() {
        val wechat = mapOf("s" to SkillPlaces(packages = setOf("com.tencent.mm")))
        val result = RunEvent.ToolResult(20, 1, "phone_tap", """{"ok":true,"screen":{"currentApp":"com.tencent.mm","full":false}}""", true, null)
        assertEquals(setOf("s"), followed(listOf(read("s", 10), result), wechat))
        val bili = mapOf("s" to SkillPlaces(domains = setOf("bilibili.com")))
        val page = RunEvent.ToolResult(20, 1, "web_navigate", """{"ok":true,"currentSite":"m.bilibili.com"}""", true, null)
        assertEquals(setOf("s"), followed(listOf(read("s", 10), page), bili))
        // 读技能之前的结果不算
        assertTrue(followed(listOf(result.copy(atMs = 5), read("s", 10), act(20)), wechat).isEmpty())
    }

    // 技能头部写的可能是知识名、别名、显示名(手写的),也可能是包名(自学的):认得出的都换成包名、域名
    @Test fun catalogResolvesKnowledgeNamesAliasesAndKeepsRawPackages() {
        val skills = InMemoryPersonalSkills(
            personalSkill("by-name", extraMeta = "apps: [\"wechat\"]\nsites: [\"bilibili.com\"]\n"),
            personalSkill("by-alias", extraMeta = "apps: [\"微信\", \"WeChat\"]\n"),
            personalSkill("by-package", extraMeta = "apps: [com.example.app]\n"),
            personalSkill("unknown", extraMeta = "apps: [\"一个不存在的 App\", \"wechatt\"]\n"),
            personalSkill("plain"),
        )
        val catalog = GuideCatalog.withPersonal(GuideCatalog.loadBundled(), skills)
        val byName = catalog.skillPlaces("by-name")!!
        assertTrue("com.tencent.mm" in byName.packages)
        assertTrue("bilibili.com" in byName.domains)
        assertEquals(setOf("com.tencent.mm"), catalog.skillPlaces("by-alias")!!.packages)
        assertTrue("com.example.app" in catalog.skillPlaces("by-package")!!.packages)
        // 认不出来、也不像包名的不算写了:不然这份技能永远认不出照着做了
        assertEquals(false, catalog.skillPlaces("unknown")!!.declared)
        assertEquals(false, catalog.skillPlaces("plain")!!.declared)
        assertNull(catalog.skillPlaces("missing"))
    }

    // App 和网站知识互相登记的一起算:技能写了网站,任务在它的 App 里做,也算照着做了
    @Test fun appAndSiteKnowledgeAreLinked() {
        val skills = InMemoryPersonalSkills(personalSkill("bili", extraMeta = "sites: [\"bilibili.com\"]\n"))
        val places = GuideCatalog.withPersonal(GuideCatalog.loadBundled(), skills).skillPlaces("bili")!!
        val biliApp = GuideCatalog.loadBundled().rows.first { it.name == "bilibili" && it.packages.isNotEmpty() }.packages.first()
        assertTrue(biliApp in places.packages)
        val events = listOf(read("bili", 10), act(20).copy(resultJson = """{"ok":true,"actionApp":"$biliApp"}"""))
        assertEquals(setOf("bili"), SkillFollow.followed(events, events.filterIsInstance<RunEvent.SkillUsed>(), tools) { places })
    }
}

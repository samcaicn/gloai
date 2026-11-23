package com.wangzan101.mika.core.learn.knowledge

import com.wangzan101.mika.core.agent.KNOWLEDGE_BODY_LIMIT
import com.wangzan101.mika.core.agent.TokenEstimate
import com.wangzan101.mika.core.agent.KNOWLEDGE_TRUNCATED_HINT
import com.wangzan101.mika.core.agent.clipKnowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.agent.GuideRecommender
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.tool.FakeScreen
import java.io.File
import com.wangzan101.mika.core.assembly.toolDispatcher

/** 随 App 打包的知识与技能:构建期校验在测试里再跑一遍,并核对运行时读到的内容。 */
class BundledKnowledgeTest {
    private val bundled by lazy { KnowledgeStore.loadBundled() }
    private val catalog by lazy { GuideCatalog.loadBundled() }
    private val resources = File("src/main/resources")

    @Test fun bundledTreesPassValidationAndIndexIsCurrent() {
        val r = KnowledgeIndex.build(resources)
        assertTrue(r.errors.joinToString("\n"), r.errors.isEmpty())
        assertEquals(mapOf("app" to 49, "site" to 29, "skill" to 6), r.entries.groupingBy { it.kind }.eachCount())
        // 类路径上的索引就是按当前文件生成的那份
        assertEquals(r.entries, catalog.rows)
    }

    @Test fun sitePaceFollowsTheSiteKnowledge() {
        assertEquals("careful", catalog.sitePace("www.zhipin.com"))
        assertEquals(null, catalog.sitePace("example.com"))
    }

    // App 的 pace 按包名查 App 知识:限制浏览快慢的那几个内置 App 是 careful,别的没有说法
    @Test fun appPaceFollowsTheAppKnowledgeByPackage() {
        val careful = listOf("xiaohongshu", "wechat", "douyin", "kuaishou", "weibo", "boss-zhipin", "xianyu", "xiaoheihe", "instagram", "tiktok")
        val apps = catalog.rows.filter { it.kind == KnowledgeIndex.APP }
        for (name in careful) {
            val row = apps.single { it.name == name }
            row.packages.forEach { assertEquals(name, "careful", catalog.appPace(it)) }
        }
        apps.filter { it.name !in careful }.flatMap { it.packages }.forEach { assertEquals(it, null, catalog.appPace(it)) }
        assertEquals(null, catalog.appPace("com.example.unknown"))
        // carefulApps 给设置里按应用操作节奏列表当初始内容:恰好是这些 App 的全部包名
        val carefulPkgs = careful.mapNotNull { n -> apps.singleOrNull { it.name == n } }.flatMap { it.packages }.sorted()
        assertEquals(carefulPkgs, catalog.carefulApps())
    }

    @Test fun everyEntryHasABody() {
        assertEquals(78, bundled.size)
        assertTrue(bundled.all { !it.body.isNullOrBlank() && it.body!!.length <= KnowledgeIndex.BODY_LIMIT })
        assertTrue(bundled.map { it.name }.containsAll(listOf("wechat", "xianyu", "android-settings", "github.com", "zhihu.com", "weixin.sogou.com")))
    }

    @Test fun onlyDocumentsAndManifestListedScripts() {
        val roots = listOf("knowledge/apps", "knowledge/sites", "skills").map { File(resources, it) }
        val others = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension !in setOf("md", "json") }.toList() }
        assertTrue(others.toString(), others.all { it.parentFile.name == "scripts" && File(it.parentFile, "manifest.json").isFile })
        // 英文副本不再随 App 打包
        assertTrue(roots.none { r -> r.walkTopDown().any { it.name.endsWith(".en.md") } })
    }

    @Test fun aliasMapsToItsOwnPackage() {
        val store = KnowledgeStore(bundled)
        assertEquals("com.taobao.idlefish", store.packageFor("闲鱼"))
        assertEquals("com.tencent.mm", store.packageFor("微信"))
    }

    @Test fun longBodyTruncatedWithHint() {
        // 上限按 token:截后连同截断说明不超过上限,且几乎用满
        val clipped = clipKnowledge("字".repeat(KNOWLEDGE_BODY_LIMIT * 2))
        assertTrue(clipped.startsWith("字".repeat(KNOWLEDGE_BODY_LIMIT)))
        assertTrue(clipped.endsWith("\n" + KNOWLEDGE_TRUNCATED_HINT))
        assertTrue("${TokenEstimate.of(clipped)}", TokenEstimate.of(clipped) in KNOWLEDGE_BODY_LIMIT - 5..KNOWLEDGE_BODY_LIMIT)
        assertEquals("短", clipKnowledge("短"))
    }

    @Test fun findSkillsMatchesTaskSkill() {
        val hits = catalog.find("发消息")
        assertTrue(hits.size <= 10)
        assertTrue(hits.any { it.kind == "skill" && it.name == "messaging" })
        assertTrue(catalog.find("微信", kind = "app").all { it.kind == "app" })
        assertTrue("wechat" in catalog.items.single { it.kind == "skill" && it.name == "messaging" }.apps)
        assertTrue("zhihu.com" in catalog.items.single { it.kind == "skill" && it.name == "info-research" }.sites)
    }

    @Test fun skillViewReturnsAttachment() {
        val v = catalog.view("site:weixin.sogou.com", "references/article-links.md") as GuideView.Found
        assertEquals("references/article-links.md", v.file)
        assertTrue(v.content.isNotBlank() && !v.truncated)
        val app = catalog.view("wechat") as GuideView.Found
        assertEquals("APP.md", app.file)
        assertTrue(app.content.contains("微信"))
        assertTrue(app.content.startsWith(UNAVAILABLE_TOOLS_NOTE + "\n"))
        assertFalse(app.content.contains("\n---\n"))
        val site = catalog.view("site:weixin.sogou.com") as GuideView.Found
        assertTrue("references/article-links.md" in site.info.files)
        assertTrue((catalog.view("weixin.sogou.com", "references/article-links.md") as GuideView.Found).content.isNotBlank())
        assertEquals("SKILL.md", (catalog.view("skill:messaging") as GuideView.Found).file)
    }

    @Test fun onlyAppSiteAndSkillKindsAnywhere() {
        assertEquals(setOf("app", "site", "skill"), catalog.rows.map { it.kind }.toSet())
        val defs = toolDispatcher(FakeScreen(), skills = catalog, sandbox = FakeSandbox()).definitions().joinToString("\n")
        val index = File(GuideCatalog::class.java.classLoader.getResource(KnowledgeIndex.RESOURCE)!!.toURI()).readText()
        val lines = GuideRecommender.index(catalog, "帮我回消息,顺便查下天气").orEmpty()
        for (text in listOf(defs, index, lines)) for (old in listOf("domain:", "domains/", "domain/", "\"domain\"", "device:", "devices/", "\"base\"")) {
            assertFalse("found $old", text.contains(old))
        }
        assertTrue(lines.contains("- skill:"))
        assertTrue(catalog.scriptFiles().keys.all { it.substringBefore('/') in setOf("apps", "sites", "skills") })
    }

    @Test fun skillViewUnknownIsError() {
        assertEquals("skill_not_found", (catalog.view("nope-nothing") as GuideView.Failed).error)
        assertEquals("skill_not_found", (catalog.view("site:wechat") as GuideView.Failed).error)
        assertEquals("file_not_found", (catalog.view("app:wechat", "../x.md") as GuideView.Failed).error)
    }

    @Test fun skillsAreNeverInjectedByTaskMatch() {
        val hit = KnowledgeStore(bundled).matchTask("帮我发微信", 10)
        assertTrue(hit.all { e -> catalog.items.any { it.kind in setOf("app", "site") && it.name == e.name } })
    }


    @Test fun singleAsciiLetterDoesNotMatchEveryName() {
        assertTrue(catalog.find("a").isEmpty())
        assertTrue(catalog.find("z").isEmpty())
        assertTrue(catalog.find("微信", kind = "app").any { it.name == "wechat" })
        assertTrue(catalog.find("微").any { it.name == "wechat" })
    }
}


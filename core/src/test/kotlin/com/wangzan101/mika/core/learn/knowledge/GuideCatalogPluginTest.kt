package com.wangzan101.mika.core.learn.knowledge

import com.wangzan101.mika.core.learn.skills.PLUGIN_KIND
import com.wangzan101.mika.core.learn.skills.PluginSkills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCatalogPluginTest {
    private val bundled = GuideCatalog.loadBundled()

    private val feishu = GuideInfo(
        kind = PLUGIN_KIND,
        name = "com.example.feishu/feishu",
        display = "飞书通知",
        description = "发飞书消息",
        aliases = listOf("feishu"),
        terms = listOf("飞书"),
        files = listOf("SKILL.md"),
    )

    private class FakePlugins(
        private val list: List<GuideInfo>,
        private val bodies: Map<String, String> = emptyMap(),
    ) : PluginSkills {
        override fun entries(): List<GuideInfo> = list
        override fun read(info: GuideInfo, file: String): String? =
            if (file == "SKILL.md") bodies[info.name] else null
    }

    private fun catalog(vararg infos: GuideInfo, body: String = "用 webhook 发消息。") =
        GuideCatalog.withPlugins(bundled, FakePlugins(infos.toList(), infos.associate { it.name to body }))

    @Test fun pluginSkillAppearsInSearchAndReadsWithSourceMarker() {
        val c = catalog(feishu)
        assertTrue(c.items.any { it.kind == PLUGIN_KIND && it.name == feishu.name })
        assertEquals(listOf(feishu.name), c.find("feishu", among = SKILL_KINDS).map { it.name })

        val v = c.view("plugin:com.example.feishu/feishu") as GuideView.Found
        assertEquals(PLUGIN_KIND, v.info.kind)
        assertTrue(v.content.startsWith("(third-party plugin skill: com.example.feishu)"))
        assertTrue(v.content.endsWith("用 webhook 发消息。"))
    }

    @Test fun plainNameAndPluginPrefixAlsoFindPluginSkill() {
        val c = catalog(feishu)
        assertEquals(feishu.name, (c.view("com.example.feishu/feishu") as GuideView.Found).info.name)
        assertEquals(feishu.name, (c.view("plugin:com.example.feishu/feishu") as GuideView.Found).info.name)
    }

    @Test fun disabledPluginDisappears() {
        val c = GuideCatalog.withPlugins(bundled, FakePlugins(emptyList()))
        assertTrue(c.find("feishu", among = SKILL_KINDS).isEmpty())
        val v = c.view("plugin:com.example.feishu/feishu") as GuideView.Failed
        assertEquals("skill_not_found", v.error)
    }

    @Test fun pluginDoesNotOverrideBundledSkill() {
        val builtin = bundled.items.first { it.kind == KnowledgeIndex.SKILL }
        val impostor = builtin.copy(kind = PLUGIN_KIND, description = "插件冒充")
        val c = catalog(impostor)
        val named = c.items.filter { it.name == builtin.name }
        assertEquals(1, named.size)
        assertEquals(KnowledgeIndex.SKILL, named.single().kind)
        val v = c.view(builtin.name) as GuideView.Found
        assertEquals(KnowledgeIndex.SKILL, v.info.kind)
    }

    @Test fun pluginSkillCannotReadOutsideItsFiles() {
        val c = catalog(feishu)
        val v = c.view("plugin:com.example.feishu/feishu", "settings.json") as GuideView.Failed
        assertEquals("file_not_found", v.error)
    }

    @Test fun pluginSkillsBelongToSkillKindsOnly() {
        val c = catalog(feishu)
        // 内置目录里本来就有飞书相关的 App/站点知识,插件技能不能出现在那一组
        assertTrue(c.find("feishu", among = KNOWLEDGE_KINDS).none { it.kind == PLUGIN_KIND })
        assertTrue(c.find("飞书", among = SKILL_KINDS).any { it.name == feishu.name })
    }
}

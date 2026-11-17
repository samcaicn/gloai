package com.wangzan101.mika.plugin

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestTest {
    private fun json(
        schema: String = "1",
        id: String = "\"com.example.feishu\"",
        name: String = "\"飞书通知\"",
        version: String = "\"1.0.0\"",
        extra: String = "",
    ) = """
        {"schema":$schema,"id":$id,"name":$name,"version":$version,
         "kind":"capability","skills":[{"name":"feishu","path":"skills/feishu","display":"飞书"}]$extra}
    """.trimIndent()

    private fun problems(text: String, app: String = "0.1.33") = PluginManifest.parse(text).validate(app)

    @Test fun validManifestPasses() {
        val m = PluginManifest.parse(json())
        assertEquals("com.example.feishu", m.id)
        assertEquals(listOf("feishu"), m.skills.map { it.name })
        assertTrue(m.validate("0.1.33").isEmpty())
    }

    @Test fun requiredFieldsAreChecked() {
        val p = problems("{}")
        assertTrue(PluginManifest.ERR_SCHEMA in p)
        assertTrue(PluginManifest.ERR_ID in p)
        assertTrue(PluginManifest.ERR_NAME in p)
        assertTrue(PluginManifest.ERR_VERSION in p)
    }

    @Test fun schemaMustBeExactlyOne() {
        assertTrue(PluginManifest.ERR_SCHEMA in problems(json(schema = "2")))
        assertTrue(PluginManifest.ERR_SCHEMA in problems(json(schema = "0")))
    }

    @Test fun invalidIdRejected() {
        for (id in listOf("\"Feishu\"", "\"com/example\"", "\"../evil\"", "\"com..example\"")) {
            assertTrue(id, PluginManifest.ERR_ID in problems(json(id = id)))
        }
    }

    @Test fun invalidSemanticVersionRejected() {
        for (v in listOf("\"1.0\"", "\"v1.0.0\"", "\"1.0.0.0\"")) {
            assertTrue(v, PluginManifest.ERR_VERSION in problems(json(version = v)))
        }
    }

    @Test fun unsupportedPagesSettingsStillRejected() {
        assertTrue(PluginManifest.ERR_ONLY_SKILLS in problems(json(extra = ",\"pages\":[{\"id\":\"home\",\"entry\":\"pages/index.html\"}]")))
        assertTrue(PluginManifest.ERR_ONLY_SKILLS in problems(json(extra = ",\"settings\":[{\"key\":\"webhook\"}]")))
    }

    private val validP1b = """
        {"schema":1,"id":"com.example.translate","name":"x","version":"1.0.0","kind":"capability",
         "permissions":["run-scripts"],
         "tools":[{"name":"translate","description":"d","script":"scripts/translate.sh","timeoutSec":120,"risk":"read","inputSchema":{"type":"object","properties":{}}}],
         "promptTransform":"scripts/rewrite_prompt.sh",
         "skills":[]}
    """.trimIndent()

    @Test fun toolsPermissionsPromptTransformAccepted() {
        val m = PluginManifest.parse(validP1b)
        assertEquals(emptyList<String>(), m.validate("0.1.45"))
        assertEquals("scripts/rewrite_prompt.sh", m.promptTransform)
    }

    @Test fun scriptMustBeSh() {
        assertTrue("script_not_sh" in problems(json(extra = ",\"tools\":[{\"name\":\"t\",\"script\":\"scripts/foo.py\"}]")))
        assertTrue("script_not_sh" in problems(json(extra = ",\"tools\":[{\"name\":\"t\"}]")))
    }

    @Test fun scriptPathMustStayInPackage() {
        assertTrue("script_not_sh" in problems(json(extra = ",\"tools\":[{\"name\":\"t\",\"script\":\"../evil.sh\"}]")))
        assertTrue("script_not_sh" in problems(json(extra = ",\"tools\":[{\"name\":\"t\",\"script\":\"/abs/evil.sh\"}]")))
    }

    @Test fun unknownPermissionRejected() {
        assertTrue("invalid_permission" in problems(json(extra = ",\"permissions\":[\"do-anything\"]")))
        assertTrue("invalid_permission" in problems(json(extra = ",\"permissions\":[\"network\"]")))
    }

    @Test fun promptTransformPathValidated() {
        assertTrue("invalid_prompt_transform" in problems(json(extra = ",\"promptTransform\":\"../evil.sh\"")))
        assertTrue("invalid_prompt_transform" in problems(json(extra = ",\"promptTransform\":\"scripts/hook.py\"")))
    }

    @Test fun disclosureListsToolsAndRisks() {
        val m = PluginManifest.parse(validP1b)
        val d = m.disclosure()
        assertEquals(listOf("translate" to "read"), d.tools)
        assertTrue(d.rewritesPrompt)
    }

    @Test fun defaultPromptRulesTemplateIsNonEmpty() {
        assertTrue(DEFAULT_PERSONA_CONF.contains("prefix"))
        assertTrue(DEFAULT_PERSONA_CONF.contains("replace"))
    }

    @Test fun duplicateToolNamesRejected() {
        val dup = """
            {"schema":1,"id":"com.example.x","name":"x","version":"1.0.0","kind":"capability",
             "permissions":["run-scripts"],
             "tools":[{"name":"scan","script":"scripts/scan.sh"},{"name":"scan","script":"scripts/patch.sh"}],
             "skills":[]}
        """.trimIndent()
        assertTrue("invalid_tool" in problems(dup))
    }

    @Test fun minAppMustBeCompatibleWithThisApp() {
        assertTrue(PluginManifest.ERR_MIN_APP_TOO_HIGH in problems(json(extra = ",\"minApp\":\"9.0.0\""), app = "0.1.33"))
        assertTrue(problems(json(extra = ",\"minApp\":\"0.1.33\""), app = "0.1.33").isEmpty())
        assertTrue(PluginManifest.ERR_MIN_APP_TOO_HIGH in problems(json(extra = ",\"minApp\":\"0.1.34\""), app = "0.1.33"))
        assertTrue(PluginManifest.ERR_MIN_APP in problems(json(extra = ",\"minApp\":\"nope\""), app = "0.1.33"))
    }

    @Test fun prereleaseIsOlderThanRelease() {
        assertEquals(-1, SemVer.compare("0.1.33-rc1", "0.1.33"))
        assertEquals(1, SemVer.compare("0.1.33", "0.1.32"))
        assertEquals(0, SemVer.compare("0.1.33+build", "0.1.33"))
        assertTrue(SemVer.isValid("0.1.33-rc.1"))
        assertTrue(!SemVer.isValid("0.1.33."))
    }

    @Test fun unknownFieldsAreIgnored() {
        val m = PluginManifest.parse(json(extra = ",\"future\":{\"anything\":true}"))
        assertTrue(m.validate("0.1.33").isEmpty())
    }

    @Test fun malformedJsonThrows() {
        try {
            PluginManifest.parse("{not json")
            throw AssertionError("expected SerializationException")
        } catch (expected: SerializationException) {
        }
    }

    @Test fun skillPathMustStayInsideSkills() {
        val bad = """
            {"schema":1,"id":"com.example.feishu","name":"x","version":"1.0.0",
             "skills":[{"name":"feishu","path":"../feishu"}]}
        """.trimIndent()
        assertTrue(PluginManifest.ERR_SKILL_PATH in problems(bad))
        val mismatch = """
            {"schema":1,"id":"com.example.feishu","name":"x","version":"1.0.0",
             "skills":[{"name":"feishu","path":"skills/other"}]}
        """.trimIndent()
        assertTrue(PluginManifest.ERR_SKILL_PATH in problems(mismatch))
    }

    @Test fun duplicateSkillNamesRejected() {
        val dup = """
            {"schema":1,"id":"com.example.feishu","name":"x","version":"1.0.0",
             "skills":[{"name":"feishu","path":"skills/feishu"},{"name":"feishu","path":"skills/feishu"}]}
        """.trimIndent()
        assertTrue(PluginManifest.ERR_SKILL in problems(dup))
    }
}

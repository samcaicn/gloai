package com.wangzan101.mika.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val filesDir get() = File(tmp.root, "files")
    private val plugins get() = File(filesDir, "plugins")

    private fun installer(maxBytes: Long = PluginInstaller.DEFAULT_MAX_BYTES) =
        PluginInstaller(filesDir, appVersion = "0.1.33", maxBytes = maxBytes)

    private fun manifest(
        id: String = "com.example.feishu",
        name: String = "飞书通知",
        version: String = "1.0.0",
        skills: String = """[{"name":"feishu","path":"skills/feishu","display":"飞书"}]""",
    ) = """
        {"schema":1,"id":"$id","name":"$name","version":"$version",
         "kind":"capability","skills":$skills}
    """.trimIndent()

    private fun zip(entries: Map<String, String>, name: String = "plugin.zip"): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((path, text) in entries) {
                z.putNextEntry(ZipEntry(path))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return f
    }

    private fun pluginZip(
        manifest: String = manifest(),
        skill: String = "---\nname: feishu\ndisplay: 飞书\n---\n用 webhook 发消息。",
        name: String = "plugin.zip",
    ) = zip(mapOf("manifest.json" to manifest, "skills/feishu/SKILL.md" to skill), name)

    private fun failureCode(result: Result<PluginRecord>): String? =
        (result.exceptionOrNull() as? PluginInstallException)?.code

    @Test fun installsSkillPluginAtomically() {
        val record = installer().installZip(pluginZip()).getOrThrow()
        assertEquals("com.example.feishu", record.id)
        assertEquals("飞书通知", record.name)
        assertEquals("1.0.0", record.version)
        assertEquals(listOf("feishu"), record.skillNames)
        assertTrue(File(plugins, "com.example.feishu/manifest.json").isFile)
        assertTrue(File(plugins, "com.example.feishu/skills/feishu/SKILL.md").isFile)
        assertTrue(File(plugins, ".staging").listFiles().orEmpty().isEmpty())
        assertFalse(File(plugins, "com.example.feishu.zip").exists())
    }

    @Test fun rejectsZipSlip() {
        val z = zip(
            mapOf(
                "manifest.json" to manifest(),
                "skills/feishu/SKILL.md" to "x",
                "../evil.txt" to "oops",
            ),
        )
        assertEquals(PluginErrors.UNSAFE_PATH, failureCode(installer().installZip(z)))
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(plugins, "com.example.feishu").exists())
        assertTrue(File(plugins, ".staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun rejectsAbsolutePath() {
        val z = zip(mapOf("manifest.json" to manifest(), "/abs.txt" to "x"))
        assertEquals(PluginErrors.UNSAFE_PATH, failureCode(installer().installZip(z)))
    }

    @Test fun rejectsBackslashAndDriveLetters() {
        assertEquals(PluginErrors.UNSAFE_PATH, failureCode(installer().installZip(
            zip(mapOf("manifest.json" to manifest(), "skills\\feishu\\SKILL.md" to "x")),
        )))
        assertEquals(PluginErrors.UNSAFE_PATH, failureCode(installer().installZip(
            zip(mapOf("manifest.json" to manifest(), "C:/abs.txt" to "x"), name = "drive.zip"),
        )))
    }

    @Test fun requiresManifestAtRoot() {
        val z = zip(mapOf("skills/feishu/SKILL.md" to "x"))
        assertEquals(PluginErrors.MISSING_MANIFEST, failureCode(installer().installZip(z)))
    }

    @Test fun rejectsOversizedPackage() {
        val z = pluginZip(skill = "x".repeat(500))
        assertEquals(PluginErrors.TOO_LARGE, failureCode(installer(maxBytes = 128).installZip(z)))
        assertFalse(File(plugins, "com.example.feishu").exists())
    }

    @Test fun rejectsDeclaredSkillWithoutSkillMd() {
        val z = zip(mapOf("manifest.json" to manifest(), "skills/feishu/other.md" to "x"))
        assertEquals(PluginErrors.INVALID_MANIFEST, failureCode(installer().installZip(z)))
    }

    @Test fun rejectsNonShScriptTool() {
        val withTools = manifest(
            id = "com.example.tools",
            skills = "[]",
        ).replace("\"kind\"", "\"tools\":[{\"name\":\"send\",\"script\":\"scripts/send.py\"}],\"kind\"")
        assertEquals(PluginManifest.ERR_SCRIPT_NOT_SH, failureCode(installer().installZip(pluginZip(manifest = withTools))))
    }

    @Test fun brokenManifestRejected() {
        assertEquals(PluginErrors.INVALID_MANIFEST, failureCode(installer().installZip(pluginZip(manifest = "{oops"))))
        assertEquals(PluginManifest.ERR_ID, failureCode(installer().installZip(pluginZip(manifest = manifest(id = "Bad Id")))))
    }

    @Test fun higherVersionReplacesOldOne() {
        assertEquals("1.0.0", installer().installZip(pluginZip()).getOrThrow().version)
        val next = pluginZip(
            manifest = manifest(version = "2.0.0"),
            skill = "---\nname: feishu\n---\n新版本",
            name = "v2.zip",
        )
        assertEquals("2.0.0", installer().installZip(next).getOrThrow().version)
        assertTrue(File(plugins, "com.example.feishu/skills/feishu/SKILL.md").readText().endsWith("新版本"))
    }

    @Test fun failedUpgradeKeepsOldVersion() {
        installer().installZip(pluginZip()).getOrThrow()
        val broken = zip(
            mapOf("manifest.json" to manifest(version = "2.0.0"), "skills/feishu/notes.md" to "x"),
            name = "broken.zip",
        )
        assertEquals(PluginErrors.INVALID_MANIFEST, failureCode(installer().installZip(broken)))
        assertEquals("1.0.0", PluginManifest.parse(
            File(plugins, "com.example.feishu/manifest.json").readText(),
        ).version)
        assertTrue(File(plugins, "com.example.feishu/skills/feishu/SKILL.md").isFile)
    }
}

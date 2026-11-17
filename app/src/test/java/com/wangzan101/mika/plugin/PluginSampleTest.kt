package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.skills.PLUGIN_KIND
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 仓库里的飞书示例要和当前阶段的校验规则一致:P1a 能装,工具版/连接器版留给后续阶段。 */
class PluginSampleTest {
    @get:Rule val tmp = TemporaryFolder()

    private val examples: File by lazy {
        var dir = File(System.getProperty("user.dir")).canonicalFile
        while (dir.parentFile != null && !File(dir, "examples/plugins").isDirectory) dir = dir.parentFile!!
        check(File(dir, "examples/plugins").isDirectory) { "examples/plugins not found from ${System.getProperty("user.dir")}" }
        File(dir, "examples/plugins")
    }

    private val feishu get() = File(examples, "com.example.feishu")

    @Test fun p1aSampleManifestIsValid() {
        val m = PluginManifest.parse(File(feishu, "manifest.json").readText())
        assertEquals("com.example.feishu", m.id)
        assertEquals(listOf("feishu"), m.skills.map { it.name })
        assertTrue(m.validate("0.1.33").isEmpty())
        assertTrue(File(feishu, "skills/feishu/SKILL.md").isFile)
    }

    @Test fun p1aSampleInstallsAndItsSkillIsReadable() {
        val zip = File(tmp.root, "feishu.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            add(z, File(feishu, "manifest.json"), "manifest.json")
            val skills = File(feishu, "skills")
            for (f in skills.walkTopDown().filter { it.isFile }) {
                add(z, f, f.relativeTo(feishu).invariantSeparatorsPath)
            }
        }
        val files = tmp.newFolder("files")
        val record = PluginInstaller(files, appVersion = "0.1.33").installZip(zip).getOrThrow()
        assertEquals(record.skillNames, listOf("feishu"))

        val registry = PluginRegistry(files, CoroutineScope(Dispatchers.Unconfined), appVersion = "0.1.33")
        val info = registry.skillsProvider.entries().single()
        assertEquals("com.example.feishu/feishu", info.name)
        assertEquals(PLUGIN_KIND, info.kind)
        val body = registry.skillsProvider.read(info, "SKILL.md").orEmpty()
        assertTrue(body.contains("飞书"))
        assertTrue(body.contains("连接器"))
    }

    @Test fun fullManifestRejectedForNonShScriptAndPermissions() {
        val m = PluginManifest.parse(File(feishu, "manifest.full.json").readText())
        val problems = m.validate("0.1.33")
        assertTrue(problems.toString(), PluginManifest.ERR_INVALID_PERMISSION in problems)
        assertTrue(problems.toString(), PluginManifest.ERR_SCRIPT_NOT_SH in problems)
        assertTrue(File(feishu, "scripts/send.py").isFile)
    }

    @Test fun connectorManifestAwaitsP4() {
        val m = PluginManifest.parse(File(examples, "com.example.feishu-connector/manifest.json").readText())
        assertEquals("connector", m.kind)
        assertTrue(PluginManifest.ERR_KIND in m.validate("0.1.33"))
    }

    private fun add(z: ZipOutputStream, file: File, name: String) {
        z.putNextEntry(ZipEntry(name))
        z.write(file.readBytes())
        z.closeEntry()
    }
}

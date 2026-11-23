package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.knowledge.GuideInfo
import com.wangzan101.mika.core.learn.skills.PLUGIN_KIND
import com.wangzan101.mika.core.learn.skills.PluginSkills
import java.io.File

/**
 * 从 `files/plugins/<id>/skills/<name>/SKILL.md` 读已启用插件的技能。
 * 技能名是 `<pluginId>/<name>`;坏掉的技能跳过,不让整个插件消失。
 * [plugins] 每次调用现取,停用后下一次查找就看不到;任务开始时可用 [snapshot] 固定一份。
 */
class PluginSkillsProvider(
    private val plugins: () -> List<PluginRecord>,
    private val root: File,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) : PluginSkills {

    private class SkillRef(val pluginId: String, val info: GuideInfo, val markdown: File)

    override fun entries(): List<GuideInfo> = load().map { it.info }

    override fun read(info: GuideInfo, file: String): String? =
        load().firstOrNull { it.info.name == info.name }?.let { readFile(it, file) }

    /** 任务开始时的固定快照:本次任务里启用/停用从下一次任务生效。 */
    fun snapshot(): PluginSkills {
        val refs = load()
        return object : PluginSkills {
            override fun entries(): List<GuideInfo> = refs.map { it.info }
            override fun read(info: GuideInfo, file: String): String? =
                refs.firstOrNull { it.info.name == info.name }?.let { readFile(it, file) }
        }
    }

    private fun load(): List<SkillRef> {
        val out = mutableListOf<SkillRef>()
        for (record in plugins().filter { it.enabled }) {
            val dir = File(root, record.id)
            val manifest = runCatching { PluginManifest.parse(File(dir, "manifest.json").readText()) }
                .onFailure { log("could not read manifest of ${record.id}", it) }
                .getOrNull() ?: continue
            for (skill in manifest.skills) {
                runCatching { ref(record, dir, skill) }
                    .onFailure { log("could not load skill ${record.id}/${skill.name}", it) }
                    .getOrNull()?.let { out += it }
            }
        }
        return out
    }

    private fun ref(record: PluginRecord, pluginDir: File, skill: PluginSkillDecl): SkillRef? {
        if (!PluginManifest.SKILL_NAME.matches(skill.name)) return null
        if (skill.path != "skills/${skill.name}" || skill.path.contains("..")) return null
        val base = pluginDir.canonicalFile
        val dir = File(base, skill.path).canonicalFile
        if (!dir.path.startsWith(base.path + File.separator)) return null
        val markdown = File(dir, SKILL_FILE)
        if (!markdown.isFile) return null
        // 防符号链接把 SKILL.md 指到插件目录外
        if (!markdown.canonicalFile.path.startsWith(dir.path + File.separator)) return null
        val (meta, body) = FrontMatter.parse(markdown.readText())
        if (body.isBlank()) return null
        fun one(key: String) = (meta[key] as? String)?.takeIf { it.isNotEmpty() }
        fun many(key: String) = (meta[key] as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(one(key))
        val apps = many("apps")
        val domains = many("domains")
        return SkillRef(
            pluginId = record.id,
            info = GuideInfo(
                kind = PLUGIN_KIND,
                name = "${record.id}/${skill.name}",
                display = skill.display?.takeIf { it.isNotEmpty() } ?: one("display") ?: skill.name,
                description = one("description").orEmpty(),
                aliases = many("aliases"),
                terms = apps + domains,
                files = listOf(SKILL_FILE),
                apps = apps,
                sites = many("sites") + domains,
            ),
            markdown = markdown,
        )
    }

    private fun readFile(ref: SkillRef, file: String): String? {
        if (file != SKILL_FILE) return null
        return runCatching {
            FrontMatter.parse(ref.markdown.readText()).second.takeIf { it.isNotBlank() }
        }.onFailure { log("could not read ${ref.info.name}", it) }.getOrNull()
    }

    private companion object {
        const val SKILL_FILE = "SKILL.md"
    }
}

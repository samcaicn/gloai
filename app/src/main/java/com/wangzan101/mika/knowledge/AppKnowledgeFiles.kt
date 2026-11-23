package com.wangzan101.mika.knowledge

import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeTrial
import com.wangzan101.mika.core.learn.knowledge.LearnedNote
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * App 知识补充的各个版本与阶段账本;待确认的(草稿)不会被知识库读取。每个 App 至多一份待确认的、只注入一份
 * ([AppKnowledgeTrial.inUse]):收下的修订试用期间顶替旧的那份,转正式才把旧的归档,被归档就换回旧的
 * (改动前的文件都留在 archive 里)。
 */
class AppKnowledgeFiles(dir: File) : AppKnowledgeDraftStore {
    private val root = File(dir, "knowledge/app-drafts")
    private val archive = File(root, "archive")
    private val journal = File(root, "changes.jsonl")
    private val idPattern = Regex("[A-Za-z0-9_-]+")

    @Synchronized override fun all(): List<AppKnowledgeDraft> =
        root.listFiles { f -> f.isDirectory && f.name != "archive" && idPattern.matches(f.name) }.orEmpty()
            .mapNotNull { d ->
                runCatching {
                    val row = Json.decodeFromString(AppKnowledgeDraft.serializer(), File(d, "meta.json").readText())
                    row.copy(markdown = File(d, "APP.md").readText())
                }.getOrNull()
            }

    @Synchronized override fun putPending(draft: AppKnowledgeDraft): Boolean {
        val rows = all()
        if (!idPattern.matches(draft.id) || !idPattern.matches(draft.appName) ||
            draft.stage != AppKnowledgeStage.Draft ||
            rows.any { it.appName == draft.appName && it.stage == AppKnowledgeStage.Draft } || stale(draft, rows)) return false
        save(draft)
        return true
    }

    @Synchronized override fun approve(id: String): Boolean {
        val rows = all()
        val old = rows.firstOrNull { it.id == id && it.stage == AppKnowledgeStage.Draft } ?: return false
        if (stale(old, rows)) {
            save(old.copy(stage = AppKnowledgeStage.Archived))
            return false
        }
        save(old.copy(stage = AppKnowledgeStage.Trial))
        archiveStale(old.appName)
        return true
    }

    @Synchronized override fun reject(id: String): Boolean {
        val old = all().firstOrNull { it.id == id && it.stage != AppKnowledgeStage.Archived } ?: return false
        save(old.copy(stage = AppKnowledgeStage.Archived))
        archiveStale(old.appName)
        return true
    }

    /** 试用的是注入的那份;转正式时它顶替过的旧版本一并归档,归档时旧版本自然换回来。 */
    @Synchronized override fun recordTrial(appName: String, good: Boolean): AppKnowledgeDraft? {
        val rows = all()
        val old = AppKnowledgeTrial.inUse(rows, appName)?.takeIf { it.stage == AppKnowledgeStage.Trial } ?: return null
        val next = AppKnowledgeTrial.next(old, good)
        if (next.stage == AppKnowledgeStage.Verified) rows.filter {
            it.appName == appName && it.id != old.id && (it.stage == AppKnowledgeStage.Trial || it.stage == AppKnowledgeStage.Verified)
        }.forEach { save(it.copy(stage = AppKnowledgeStage.Archived)) }
        save(next)
        archiveStale(appName)
        return next
    }

    /** 每个 App 注入的那份;旧版本起草的是带头部的整份 APP.md,只取正文。 */
    @Synchronized override fun notes(): Map<String, LearnedNote> {
        val rows = all()
        return rows.map { it.appName }.distinct().mapNotNull { app -> AppKnowledgeTrial.inUse(rows, app) }
            .associate { row -> row.appName to LearnedNote(FrontMatter.parse(row.markdown).second.trim(), trial = row.stage == AppKnowledgeStage.Trial) }
    }

    /** 修订起草时对着的那份已经不是现在注入的了(另一份修订先收下了):收下它会丢掉那一份。 */
    private fun stale(draft: AppKnowledgeDraft, rows: List<AppKnowledgeDraft>) =
        draft.revises != AppKnowledgeTrial.inUse(rows, draft.appName)?.id

    /**
     * 在用的那份变了(收下、归档、试用改了阶段)之后,对着别的那份起草的待确认修订再也收不下:归档,
     * 免得它一直占着「每个 App 至多一份待确认的」,挡住这个 App 往后的草稿。
     */
    private fun archiveStale(appName: String) {
        val rows = all()
        rows.filter { it.appName == appName && it.stage == AppKnowledgeStage.Draft && stale(it, rows) }
            .forEach { save(it.copy(stage = AppKnowledgeStage.Archived)) }
    }

    private fun save(row: AppKnowledgeDraft) {
        val d = File(root, row.id)
        if (d.exists()) {
            val revision = UUID.randomUUID().toString()
            d.copyRecursively(File(archive, revision), overwrite = true)
            journal.parentFile.mkdirs()
            journal.appendText(revision + " " + row.id + " " + row.stage + " " + System.currentTimeMillis() + "\n")
        }
        d.mkdirs()
        atomic(File(d, "APP.md"), row.markdown)
        atomic(File(d, "meta.json"), Json.encodeToString(AppKnowledgeDraft.serializer(), row.copy(markdown = "")))
    }

    private fun atomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.record.RunMetricsStore
import com.wangzan101.mika.core.usage.ConversationMeter
import com.wangzan101.mika.core.usage.ConversationUsage
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.PersonaStore
import com.wangzan101.mika.core.chat.GeneratedAudio
import com.wangzan101.mika.core.chat.GeneratedPhoto
import com.wangzan101.mika.core.memory.MemoryNote
import com.wangzan101.mika.core.memory.NoteKind
import com.wangzan101.mika.core.skills.SkillNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.util.UUID

/** 每个对话一个小 JSON 文件,放在 App 私有目录;重启后仍在。 */
class ConversationStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readConversation: (File) -> String = { it.readText() },
) {
    constructor(context: Context) : this(File(context.filesDir, "conversations"))

    fun newConversation(): String = UUID.randomUUID().toString()

    // Separate invalidation: model requests must not reload the transcript, reorder chats or reset auto-archive.
    private val usageRevision = MutableStateFlow(0L)
    private val metrics = RunMetricsStore(File(dir.parentFile, RunMetricsStore.FILE_NAME))
    private val usageJson = Json { ignoreUnknownKeys = true }

    fun observeUsage(id: String): Flow<ConversationUsage> =
        usageRevision.map { usageSnapshot(id) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun meter(id: String) = ConversationMeter { change -> updateUsage(id, change) }

    private fun usageFile(id: String) = File(dir, "${file(id).name}.usage")

    @Synchronized
    internal fun usageSnapshot(id: String): ConversationUsage {
        val saved = usageFile(id)
        if (saved.isFile) return usageJson.decodeFromString(ConversationUsage.serializer(), saved.readText())
        val branch = branchOrigin(id)
        val inherited = branch?.let { it.parentTurn + if (it.afterTurn) 1 else 0 } ?: 0
        val history = turns(id).drop(inherited).filterNot(NoticeTurn::isNotice)
        if (history.isEmpty()) return ConversationUsage()
        val summaries = metrics.byIds(history.mapNotNull { it.runId }.toSet()).values
        // Historical summaries lack cache-report coverage and may omit calls made after a task ended.
        return ConversationUsage(input = summaries.sumOf { it.allInputTokens ?: it.inputTokens },
            output = summaries.sumOf { it.allOutputTokens ?: it.outputTokens }, partial = true)
    }

    @Synchronized
    private fun updateUsage(id: String, change: (ConversationUsage) -> ConversationUsage) {
        if (safe(id) in deleted) return
        val old = usageSnapshot(id)
        val next = change(old)
        if (old == next) return
        dir.mkdirs()
        val dest = usageFile(id)
        val tmp = File(dir, "${dest.name}.tmp")
        tmp.writeText(usageJson.encodeToString(ConversationUsage.serializer(), next))
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        usageRevision.value++
    }

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * 写过的对话编号:存进一轮、改了某一轮、去掉记忆提示时各发一次,不管是谁写的(对话页、悬浮胶囊、定时任务)。
     * 对话页和侧栏据此重读,屏上的就是存下的那份。
     */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    private val _deletions = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 删掉的对话编号:[delete] 时发一次([changes] 也照发)。悬浮胶囊据此拿掉这个对话刚结束的任务、卡片不再接着它。 */
    val deletions: SharedFlow<String> = _deletions.asSharedFlow()

    /**
     * 用户此刻在哪个对话,见 [CurrentChat]。存在对话目录里的 `current` 文件(不是 `.json`,不进 [list]);
     * 进程重启后照样读得回来,胶囊不必等对话文件写下来才知道新开的是哪个。
     */
    private val _current = MutableStateFlow(readCurrent())

    val current: StateFlow<CurrentChat?> = _current.asStateFlow()

    fun currentChat(): CurrentChat? = _current.value

    /** 记下用户此刻在哪个对话;删掉的对话记不住。写进去了发一次 [current]。 */
    @Synchronized
    fun setCurrent(chat: CurrentChat?) {
        val target = when (chat) {
            is CurrentChat.Open -> if (safe(chat.id) in deleted) null else chat
            else -> chat
        }
        if (target == _current.value) return
        dir.mkdirs()
        runCatching {
            when (target) {
                null -> currentFile().delete()
                CurrentChat.Fresh -> currentFile().writeText(CURRENT_FRESH)
                is CurrentChat.Open -> currentFile().writeText(safe(target.id))
            }
        }
        _current.value = target
    }

    private fun readCurrent(): CurrentChat? {
        val f = currentFile().takeIf { it.isFile } ?: return null
        val text = runCatching { f.readText().trim() }.getOrNull().orEmpty()
        return when {
            text.isEmpty() -> null
            text == CURRENT_FRESH -> CurrentChat.Fresh
            else -> safe(text).takeIf(String::isNotEmpty)?.let(CurrentChat::Open)
        }
    }

    /**
     * 对话列表,最近改过的在前;[ConversationSummary.title] 是标题(用户改的或起好的),没有时是第一句话;
     * [ConversationSummary.pinnedAt] 是置顶的时间;[ConversationSummary.page] 是从哪一页开始的;
     * [ConversationSummary.branchOf] 是从哪个对话的第几轮分出来的;[ConversationSummary.archivedAt] 是归档的时间;
     * [ConversationSummary.restoredAt] 是最近一次手动取消归档的时间(自动归档据此给一段宽限)。
     * 没有内容的对话不列;归档的对话默认不列,见 [includeArchived]。
     */
    fun list(includeArchived: Boolean = false): List<ConversationSummary> =
        listPage(0, Int.MAX_VALUE, includeArchived = includeArchived)

    /**
     * 侧栏搜索用的正文索引:标题由调用方连同 [ConversationSummary] 一起传进来,正文按对话文件的修改时间缓存。
     * 读文件不占本类的锁,不挡正在收尾的任务补写。
     */
    val search: ConversationSearch = ConversationSearch(::file) { read(it)?.map { record -> record.first } }

    /** 尚未保存首轮的活动对话排在前面，与磁盘上的对话共用一套分页。归档的对话默认不列。 */
    @Synchronized
    fun listPage(
        offset: Int,
        limit: Int,
        pending: List<ConversationSummary> = emptyList(),
        includeArchived: Boolean = false,
    ): List<ConversationSummary> {
        val unsaved = pending.asSequence()
            .filter { firstMessage(file(it.id)) == null }
            .filter { includeArchived || archivedAt(it.id) == null }
        val saved = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .map { it to it.lastModified() }
            .sortedWith(compareByDescending<Pair<File, Long>> { it.second }.thenBy { it.first.name })
            .asSequence().mapNotNull { (f, updatedAt) ->
                val first = firstMessage(f) ?: return@mapNotNull null
                val id = f.nameWithoutExtension
                val archivedAt = archivedAt(id)
                if (!includeArchived && archivedAt != null) return@mapNotNull null
                ConversationSummary(id, title(id) ?: first, updatedAt, pinnedAt(id), pageOrigin(id), branchOrigin(id), archivedAt, restoredAt(id), projectId(id))
            }
        return (unsaved + saved).drop(offset).take(limit).toList()
    }

    /** 只读控制台用:最近的对话(不含归档),连同轮数一起返回。 */
    @Synchronized
    fun listForConsole(limit: Int): List<ConversationSummary> =
        listPage(0, limit).map { s -> s.copy(turnCount = read(file(s.id))?.size ?: 0) }

    private data class FirstMessage(val modified: Long, val size: Long, val text: String?)
    private val firstMessages = mutableMapOf<String, FirstMessage>()

    private fun firstMessage(f: File): String? {
        if (!f.isFile) return null
        val modified = f.lastModified()
        val size = f.length()
        firstMessages[f.name]?.let { if (it.modified == modified && it.size == size) return it.text }
        val text = read(f)?.firstOrNull()?.first?.userText
        firstMessages[f.name] = FirstMessage(modified, size, text)
        return text
    }

    /** 对话起好的标题(见 [ConversationTitler]);没有、读不到为 null。标题放在对话文件旁边的 `.title` 文件里,不动对话文件的格式。 */
    @Synchronized
    fun title(conversationId: String): String? =
        runCatching { titleFile(conversationId).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf(String::isNotEmpty)

    /**
     * 删掉过的对话。删之后才到的「补写」(后台起好的标题、任务收尾后补的结局和交代)不再把它写回来;
     * 再往里存进一轮([append])就是重新开始用这个编号,从这里拿掉。只记在进程里。
     */
    private val deleted = mutableSetOf<String>()
    // 只在本进程、这把锁内使用，防止筛选后发生写入的对话被旧快照归档。
    private val archiveVersions = mutableMapOf<String, Long>()
    private data class MessageTime(val modified: Long, val size: Long, val time: Long?)
    private val messageTimes = mutableMapOf<String, MessageTime>()
    private fun bumpArchiveVersion(id: String) {
        val key = safe(id)
        archiveVersions[key] = (archiveVersions[key] ?: 0L) + 1L
    }

    /**
     * 删掉一个对话存下的一切:对话文件、标题、置顶、网页来源、分支来源,以及写到一半的临时文件和挪到一边的损坏文件。
     * 删了什么都发一次 [changes] 和 [deletions]。
     * 对话的工作区、定时任务、在跑的任务不归这里管,见 [ConversationDeleter]。
     */
    @Synchronized
    fun delete(conversationId: String) {
        bumpArchiveVersion(conversationId)
        val main = file(conversationId).name
        val metadata = setOf(titleFile(conversationId).name, pinFile(conversationId).name,
            pageFile(conversationId).name, branchFile(conversationId).name, archiveFile(conversationId).name,
            restoreFile(conversationId).name, personaFile(conversationId).name, personaFile(conversationId).name + ".tmp",
            projectFile(conversationId).name, projectFile(conversationId).name + ".tmp")
        val files = dir.listFiles { f -> f.name == main || f.name.startsWith("$main.") || f.name in metadata }
            ?: if (Files.notExists(dir.toPath())) emptyArray() else throw IOException("Cannot list conversation files")
        // Leave the history in place if any of its companion files cannot be removed.
        files.sortedBy { it.name == main }.forEach { file ->
            val path = file.toPath()
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("Unexpected conversation directory: ${file.name}")
            Files.deleteIfExists(path)
        }
        usageRevision.value++
        deleted += safe(conversationId)
        firstMessages.remove(main)
        messageTimes.remove(main)
        // 删掉的正是用户此刻在的那个:不再指着它,胶囊回落到最近那个
        if (_current.value == CurrentChat.Open(safe(conversationId))) setCurrent(null)
        _changes.tryEmit(conversationId)
        _deletions.tryEmit(conversationId)
    }

    /**
     * 给对话记下起好的标题;已经有标题(起好的或用户改的)就不动(标题只起一次)。写进去了返回 true。
     * 所以后台起标题晚到时,用户先改的名字不会被盖掉。
     */
    @Synchronized
    fun setTitle(conversationId: String, title: String): Boolean {
        if (title(conversationId) != null) return false
        return writeTitle(conversationId, title)
    }

    /** 用户给对话改名:不管有没有标题都换成 [title];空的不改。写进去了返回 true。 */
    @Synchronized
    fun rename(conversationId: String, title: String): Boolean = writeTitle(conversationId, title)

    private fun writeTitle(conversationId: String, title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty() || safe(conversationId) in deleted) return false
        dir.mkdirs()
        val ok = runCatching { titleFile(conversationId).writeText(t) }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** 对话置顶的时间;没置顶、读不到为 null。放在对话文件旁边的 `.pin` 文件里。 */
    @Synchronized
    fun pinnedAt(conversationId: String): Long? =
        runCatching { pinFile(conversationId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    /** 置顶([pinned] 为真,记下时间 [at])或取消置顶。删掉的对话不动。改了返回 true。 */
    @Synchronized
    fun setPinned(conversationId: String, pinned: Boolean, at: Long = System.currentTimeMillis()): Boolean {
        if (safe(conversationId) in deleted) return false
        bumpArchiveVersion(conversationId)
        val f = pinFile(conversationId)
        val ok = if (pinned) {
            dir.mkdirs()
            runCatching { f.writeText(at.toString()) }.isSuccess
        } else !f.exists() || f.delete()
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** 对话归档的时间;没归档、读不到为 null。放在对话文件旁边的 `.archive` 文件里。 */
    @Synchronized
    fun archivedAt(conversationId: String): Long? =
        runCatching { archiveFile(conversationId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    /**
     * 最近一次手动取消归档的时间;没取消过、读不到为 null。放在 `.restored` 文件里。
     * 自动归档据此给手动恢复的对话一段宽限,不会刚恢复就又被收走。
     */
    @Synchronized
    fun restoredAt(conversationId: String): Long? =
        runCatching { restoreFile(conversationId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    /** 归档([archived] 为真,记下时间 [at])或取消归档。取消归档时另记下恢复时间,见 [restoredAt]。删掉的对话不动。改了返回 true。 */
    @Synchronized
    fun setArchived(conversationId: String, archived: Boolean, at: Long = System.currentTimeMillis()): Boolean {
        if (safe(conversationId) in deleted) return false
        bumpArchiveVersion(conversationId)
        val f = archiveFile(conversationId)
        val ok = if (archived) {
            dir.mkdirs()
            runCatching { f.writeText(at.toString()) }.isSuccess
        } else {
            val removed = !f.exists() || f.delete()
            // 手动恢复的时间单独记着:自动归档看到它,会给这个对话一段宽限
            if (removed && dir.isDirectory) runCatching { restoreFile(conversationId).writeText(at.toString()) }
            removed
        }
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** 消息时间缺失的旧记录使用文件时间；后台补写不应改变有时间记录的最后消息时间。 */
    private fun lastMessageAt(f: File): Long? {
        val records = read(f)?.takeIf { it.isNotEmpty() } ?: return null
        return records.maxOf { it.first.atMs ?: f.lastModified() }
    }

    internal data class ArchiveCandidate(val id: String, val version: Long)

    /** 仅快照文件名和版本，不持锁读取所有对话正文。 */
    @Synchronized
    internal fun autoArchiveCandidates(): List<ArchiveCandidate> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().map { f ->
            val id = f.nameWithoutExtension
            ArchiveCandidate(id, archiveVersions[id] ?: 0L)
        }

    /** 正文在锁外读取，写入前再次检查状态和文件是否变化。 */
    internal fun archiveIfStillEligible(
        candidate: ArchiveCandidate,
        cutoff: Long,
        at: Long,
        isRunning: (String) -> Boolean = { false },
    ): Boolean {
        val id = candidate.id
        val f = file(id)
        fun eligible(): Boolean = !isRunning(id) &&
            (archiveVersions[id] ?: 0L) == candidate.version &&
            pinnedAt(id) == null && archivedAt(id) == null &&
            (restoredAt(id)?.let { it >= cutoff } != true) &&
            currentChat() != CurrentChat.Open(id)

        val snapshot = synchronized(this) {
            if (!eligible() || !f.isFile) return false
            val modified = f.lastModified()
            val size = f.length()
            val cached = messageTimes[f.name]?.takeIf { it.modified == modified && it.size == size }
            (cached ?: MessageTime(modified, size, null)) to (cached != null)
        }
        val (fileState, cached) = snapshot
        val time = if (cached) fileState.time else runCatching { lastMessageAt(f) }.getOrNull()
        return synchronized(this) {
            if (!eligible() || !f.isFile || f.lastModified() != fileState.modified || f.length() != fileState.size) return false
            if (time == null) return false
            messageTimes[f.name] = fileState.copy(time = time)
            if (time >= cutoff) return false
            setArchived(id, archived = true, at = at)
        }
    }

    /**
     * 对话是从哪一页开始的(用户在浏览器页上对着一页问 Mika);不是、读不到为 null。
     * 放在对话文件旁边的 `.page` 文件里(JSON:url、host、title),不动对话文件的格式;旧对话没有这个文件。
     */
    @Synchronized
    fun pageOrigin(conversationId: String): PageOrigin? = runCatching {
        val f = pageFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        PageOrigin(str(o, "url") ?: return@runCatching null, str(o, "host") ?: return@runCatching null, str(o, "title").orEmpty())
    }.getOrNull()

    /** 记下对话从哪一页开始(之后在同一页上接着问时换成最新的那一页)。删掉的对话不动。写进去了返回 true。 */
    @Synchronized
    fun setPageOrigin(conversationId: String, origin: PageOrigin): Boolean {
        if (safe(conversationId) in deleted) return false
        dir.mkdirs()
        val json = buildJsonObject { put("url", origin.url); put("host", origin.host); put("title", origin.title) }
        val ok = runCatching { pageFile(conversationId).writeText(json.toString()) }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /**
     * 这个对话用的人格;没有、读不到、id 不合法为 null。放在 `<id>.persona` 里(JSON:version、current、history)。
     * 人格被删除后旁文件仍在:调用方拿 [PersonaBinding.personaId] 去 PersonaStore 找不到,就当缺失处理。
     */
    @Synchronized
    fun persona(conversationId: String): PersonaBinding? = runCatching {
        val f = personaFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        val current = str(o, "current")?.takeIf(PersonaStore::isValidId) ?: return@runCatching null
        val history = objects(o, "history").mapNotNull { h ->
            val to = str(h, "to")?.takeIf(PersonaStore::isValidId) ?: return@mapNotNull null
            val at = (h["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            PersonaSwitch(at, str(h, "from")?.takeIf(PersonaStore::isValidId), to)
        }
        PersonaBinding(current, history.takeLast(MAX_PERSONA_HISTORY))
    }.getOrNull()

    /**
     * 换掉这个对话用的人格;null 表示回到默认(删掉旁文件)。写进去了返回 true。
     * 切换追加一条 history(最多 [MAX_PERSONA_HISTORY] 条),同一个人格重复写不追加;先写临时文件再改名。
     */
    @Synchronized
    fun setPersona(conversationId: String, personaId: String?, at: Long = System.currentTimeMillis()): Boolean {
        if (safe(conversationId) in deleted) return false
        val f = personaFile(conversationId)
        if (personaId == null) {
            val ok = !f.exists() || f.delete()
            if (ok) _changes.tryEmit(conversationId)
            return ok
        }
        if (!PersonaStore.isValidId(personaId)) return false
        val previous = persona(conversationId)
        val history = buildList {
            addAll(previous?.history.orEmpty())
            previous?.personaId?.takeIf { it != personaId }?.let { add(PersonaSwitch(at, it, personaId)) }
        }.takeLast(MAX_PERSONA_HISTORY)
        val json = buildJsonObject {
            put("version", 1)
            put("current", personaId)
            put("history", buildJsonArray {
                history.forEach { h ->
                    add(buildJsonObject {
                        put("at", h.at)
                        h.from?.let { put("from", it) }
                        put("to", h.to)
                    })
                }
            })
        }
        dir.mkdirs()
        val tmp = File(dir, "${f.name}.tmp")
        val ok = runCatching {
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
        }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /**
     * 这个对话所属的项目;没有、读不到、id 不合法为 null。放在 `<id>.project` 里。
     * 项目被删后如果旁文件意外还在,调用方经 [WorkspaceResolver] 核对后再用。
     */
    @Synchronized
    fun projectId(conversationId: String): String? =
        runCatching { projectFile(conversationId).takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull()?.takeIf(ProjectStore::isValidId)

    /** 绑定对话所属的项目;null 表示解除绑定(删掉旁文件)。删掉的对话或非法项目编号不动。写进去了返回 true。 */
    @Synchronized
    fun setProject(conversationId: String, projectId: String?): Boolean {
        if (safe(conversationId) in deleted) return false
        val f = projectFile(conversationId)
        if (projectId == null) {
            val existed = f.exists()
            val ok = !existed || f.delete()
            if (existed && ok) _changes.tryEmit(conversationId)
            return ok
        }
        if (!ProjectStore.isValidId(projectId)) return false
        dir.mkdirs()
        val tmp = File(dir, "${f.name}.tmp")
        val ok = runCatching {
            tmp.writeText(projectId)
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                if (!tmp.renameTo(f)) {
                    f.writeText(projectId)
                    tmp.delete()
                }
            }
        }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /**
     * 把 [sourceId] 的轮次复制成新对话 [targetId],复制到 [throughTurn](0 起,含在内)为止。
     * 源不存在、源文件损坏、[throughTurn] 越界、目标已有对话文件时返回 false,什么都不写。
     * [title] 非空白时写成新对话标题;[origin] 非空时写 `<targetId>.branch`。
     * 标题、网页来源、分支来源都是旁文件,写不进去不影响对话本身已复制好。
     */
    @Synchronized
    fun branch(
        sourceId: String,
        targetId: String,
        throughTurn: Int,
        title: String? = null,
        origin: BranchOrigin? = null,
    ): Boolean = copyBranch(sourceId, targetId, throughTurn, includeTurn = true, title, origin)

    /**
     * 把 [sourceId] 在 [beforeTurn] 之前的轮次(不含这一轮)复制成新对话 [targetId],给「从用户消息重新问」用。
     * [beforeTurn] 为 0 时新对话还没有轮次(和刚新开的对话一样,不进侧栏列表),第一句话存进来后才算这个对话的第一轮。
     * 其余条件同 [branch]。
     */
    @Synchronized
    fun branchBefore(
        sourceId: String,
        targetId: String,
        beforeTurn: Int,
        title: String? = null,
        origin: BranchOrigin? = null,
    ): Boolean = copyBranch(sourceId, targetId, beforeTurn, includeTurn = false, title, origin)

    /** [branch] 和 [branchBefore] 共用:复制 [sourceId] 的前 [turn] 轮([includeTurn] 时连这一轮),再写标题、网页来源、分支来源。 */
    private fun copyBranch(sourceId: String, targetId: String, turn: Int, includeTurn: Boolean, title: String?, origin: BranchOrigin?): Boolean {
        val source = file(sourceId)
        if (!source.isFile) return false
        val records = read(source) ?: return false
        if (turn !in records.indices) return false
        if (file(targetId).exists()) return false
        deleted -= safe(targetId)
        write(targetId, records.take(if (includeTurn) turn + 1 else turn))
        title?.let { writeTitle(targetId, it) }
        pageOrigin(sourceId)?.let { setPageOrigin(targetId, it) }
        // 人格与项目归属跟着对话走;切换记录不复制,新对话从当前这一份开始
        persona(sourceId)?.let { setPersona(targetId, it.personaId) }
        projectId(sourceId)?.let { setProject(targetId, it) }
        origin?.let { writeBranchOrigin(targetId, it) }
        return true
    }

    /** 分支来源:从哪个对话的第几轮分出来的;没有、读不到为 null。放在 `<id>.branch` 里。 */
    @Synchronized
    fun branchOrigin(conversationId: String): BranchOrigin? = runCatching {
        val f = branchFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        val parentId = str(o, "parent")?.takeIf(String::isNotEmpty) ?: return@runCatching null
        val parentTurn = (o["turn"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 } ?: return@runCatching null
        val afterTurn = (o["after"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: return@runCatching null
        val at = (o["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@runCatching null
        BranchOrigin(parentId, parentTurn, afterTurn, at)
    }.getOrNull()

    /** 写分支来源;先写临时文件再改名,写到一半被杀不会留下半个文件。 */
    private fun writeBranchOrigin(conversationId: String, origin: BranchOrigin) {
        dir.mkdirs()
        val json = buildJsonObject {
            put("parent", origin.parentId); put("turn", origin.parentTurn); put("after", origin.afterTurn); put("at", origin.at)
        }
        val f = branchFile(conversationId)
        val tmp = File(dir, "${f.name}.tmp")
        runCatching {
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    /** 文件损坏时当作空对话,不让它挡住新任务。 */
    @Synchronized
    fun turns(conversationId: String): List<Turn> = records(conversationId).map { it.first }

    /** 这个对话存过内容(有对话文件);空编号、读不到为 false。 */
    @Synchronized
    fun exists(conversationId: String): Boolean = file(conversationId).isFile

    /** 每一轮连同这一轮导出的文件(文件卡片)。 */
    @Synchronized
    fun records(conversationId: String): List<Pair<Turn, List<SharedFile>>> {
        val f = file(conversationId)
        if (!f.isFile) return emptyList()
        return read(f).orEmpty()
    }

    internal data class RecordsSnapshot(
        val conversations: List<Pair<String, List<Pair<Turn, List<SharedFile>>>>>,
        val hasReadFailures: Boolean,
    )

    /** 读取所有已保存的对话；单个文件读不到时保留其余结果并标记失败。 */
    @Synchronized
    internal fun allRecords(): RecordsSnapshot {
        val exists = runCatching { dir.exists() }.getOrElse { return RecordsSnapshot(emptyList(), true) }
        if (!exists) return RecordsSnapshot(emptyList(), false)
        val files = runCatching { dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } }.getOrNull()
            ?: return RecordsSnapshot(emptyList(), true)
        var failed = false
        val conversations = files.sortedBy { it.name }.mapNotNull { f ->
            val records = runCatching { read(f) }.getOrNull()
            if (records == null) {
                failed = true
                null
            } else f.nameWithoutExtension to records
        }
        return RecordsSnapshot(conversations, failed)
    }

    /** 读不懂(不是 JSON 数组)时返回 null。 */
    private fun read(f: File): List<Pair<Turn, List<SharedFile>>>? = try {
        (Json.parseToJsonElement(readConversation(f)) as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val turn = Turn(
                field("userText") ?: return@mapNotNull null, field("outcome").orEmpty(), field("status") ?: return@mapNotNull null, field("note"), field("runId"),
                memoryNotes = objects(o, "memoryNotes").mapNotNull { n ->
                    val kind = NoteKind.entries.find { it.name == str(n, "kind") } ?: return@mapNotNull null
                    MemoryNote(kind, str(n, "memoryId") ?: return@mapNotNull null, str(n, "undoToken"), str(n, "pendingId"))
                },
                usedMemory = (o["usedMemory"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                skillNotes = objects(o, "skillNotes").map { n -> SkillNote(str(n, "text").orEmpty(), str(n, "draftId"), str(n, "suspended")) },
                unconfirmed = (o["unconfirmed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                result = field("result"),
                steers = (o["steers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                interruptedReplies = (o["interruptedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
                finishedReplies = (o["finishedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
                groups = (o["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                keptPage = field("keptPage"),
                keptPageClosed = field("keptPageClosed"),
                atMs = field("atMs")?.toLongOrNull(),
                audios = objects(o, "audios").mapNotNull(::audioOf),
                photos = objects(o, "photos").mapNotNull(::photoOf),
                verified = field("verified")?.toBooleanStrictOrNull(),
                remoteTaskId = field("remoteTaskId"),
                outcomeSystemType = field("outcomeSystemType"),
            )
            turn to (o["files"] as? JsonArray).orEmpty().mapNotNull(::fileOf)
        }
    } catch (e: SerializationException) {
        null
    }

    private fun fileOf(e: kotlinx.serialization.json.JsonElement): SharedFile? {
        val o = e as? JsonObject ?: return null
        fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return SharedFile(field("name") ?: return null, field("size")?.toLongOrNull() ?: 0, field("savedAs").orEmpty(), field("uri").orEmpty(), field("sha256"))
    }

    /**
     * 往对话里存进一轮。有新的一轮就是又有动静了,归档的对话自动取消归档(和主流聊天软件一致);
     * 只改已有那一轮的 [updateTurn] 不算。
     */
    private fun audioOf(o: JsonObject): GeneratedAudio? {
        val path = str(o, "path") ?: return null
        return GeneratedAudio(path, str(o, "durationMs")?.toLongOrNull(), str(o, "text"), str(o, "atMs")?.toLongOrNull())
    }

    private fun photoOf(o: JsonObject): GeneratedPhoto? {
        val path = str(o, "path") ?: return null
        return GeneratedPhoto(path, str(o, "width")?.toIntOrNull() ?: 0, str(o, "height")?.toIntOrNull() ?: 0, str(o, "prompt"), str(o, "atMs")?.toLongOrNull())
    }

    @Synchronized
    fun append(conversationId: String, turn: Turn, files: List<SharedFile> = emptyList()) {
        bumpArchiveVersion(conversationId)
        deleted -= safe(conversationId)
        // 先解除归档再写:write 发出的 changes 里读到的就是取消归档后的状态
        archiveFile(conversationId).delete()
        val f = file(conversationId)
        val prior = if (f.isFile) read(f) else emptyList()
        if (prior == null) {
            // 损坏的文件挪到一边留着,不拿新内容覆盖掉
            f.renameTo(File(dir, "${f.name}.corrupt-${System.currentTimeMillis()}"))
        }
        // 新的一轮记下存进来的时刻;带时间戳的(分支复制、恢复)照原样保留
        val stored = if (turn.atMs == null) turn.copy(atMs = clock()) else turn
        write(conversationId, prior.orEmpty() + (stored to files))
    }

    /** 改运行编号为 [runId] 的那一轮(任务结束后补上判的结局、记忆与技能提示);找不到就不动。 */
    @Synchronized
    fun updateTurn(conversationId: String, runId: String, transform: (Turn) -> Turn) {
        val f = file(conversationId)
        if (!f.isFile) return
        val records = read(f) ?: return
        if (records.none { it.first.runId == runId }) return
        write(conversationId, records.map { (t, fs) -> (if (t.runId == runId) transform(t) else t) to fs })
    }

    /** 记忆删了或全部清除时:去掉指向这些条目的提示和「用到」记录;[ids] 为 null 时去掉全部。 */
    @Synchronized
    fun stripMemory(ids: Set<String>?) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { f ->
            val records = read(f) ?: return@forEach
            fun keep(id: String) = ids != null && id !in ids
            val stripped = records.map { (t, fs) -> t.copy(memoryNotes = t.memoryNotes.filter { keep(it.memoryId) }, usedMemory = t.usedMemory.filter(::keep)) to fs }
            if (stripped != records) write(f.name.removeSuffix(".json"), stripped)
        }
    }

    private fun write(conversationId: String, all: List<Pair<Turn, List<SharedFile>>>) {
        bumpArchiveVersion(conversationId)
        messageTimes.remove(file(conversationId).name)
        dir.mkdirs()
        val json = JsonArray(all.map { (it, fs) ->
            buildJsonObject {
                put("userText", it.userText); put("outcome", it.outcome); put("status", it.status)
                it.note?.let { n -> put("note", n) }; it.runId?.let { r -> put("runId", r) }; it.result?.let { r -> put("result", r) }
                it.verified?.let { v -> put("verified", v) }
                it.remoteTaskId?.let { id -> put("remoteTaskId", id) }
                it.outcomeSystemType?.let { type -> put("outcomeSystemType", type) }
                // 只存种类和编号:记忆内容只在加密的记忆文件里,界面现取
                if (it.memoryNotes.isNotEmpty()) put("memoryNotes", JsonArray(it.memoryNotes.map { n ->
                    buildJsonObject {
                        put("kind", n.kind.name); put("memoryId", n.memoryId)
                        n.undoToken?.let { t -> put("undoToken", t) }; n.pendingId?.let { p -> put("pendingId", p) }
                    }
                }))
                if (it.usedMemory.isNotEmpty()) put("usedMemory", JsonArray(it.usedMemory.map(::JsonPrimitive)))
                if (it.unconfirmed.isNotEmpty()) put("unconfirmed", JsonArray(it.unconfirmed.map(::JsonPrimitive)))
                if (it.steers.isNotEmpty()) put("steers", JsonArray(it.steers.map(::JsonPrimitive)))
                if (it.interruptedReplies.any(String::isNotEmpty)) put("interruptedReplies", JsonArray(it.interruptedReplies.map(::JsonPrimitive)))
                if (it.finishedReplies.any(String::isNotEmpty)) put("finishedReplies", JsonArray(it.finishedReplies.map(::JsonPrimitive)))
                if (it.groups.isNotEmpty()) put("groups", JsonArray(it.groups.map(::JsonPrimitive)))
                it.keptPage?.let { h -> put("keptPage", h) }; it.keptPageClosed?.let { r -> put("keptPageClosed", r) }
                it.atMs?.let { t -> put("atMs", t) }
                if (it.audios.isNotEmpty()) put("audios", JsonArray(it.audios.map { a ->
                    buildJsonObject {
                        put("path", a.path); a.durationMs?.let { d -> put("durationMs", d) }
                        a.text?.let { t -> put("text", t) }; a.atMs?.let { t -> put("atMs", t) }
                    }
                }))
                if (it.photos.isNotEmpty()) put("photos", JsonArray(it.photos.map { p ->
                    buildJsonObject {
                        put("path", p.path); put("width", p.width); put("height", p.height)
                        p.prompt?.let { text -> put("prompt", text) }
                        p.atMs?.let { t -> put("atMs", t) }
                    }
                }))
                if (it.skillNotes.isNotEmpty()) put("skillNotes", JsonArray(it.skillNotes.map { n ->
                    buildJsonObject { put("text", n.text); n.draftId?.let { d -> put("draftId", d) }; n.suspended?.let { x -> put("suspended", x) } }
                }))
                if (fs.isNotEmpty()) put("files", JsonArray(fs.map { f ->
                    buildJsonObject {
                        put("name", f.name); put("size", f.size); put("savedAs", f.savedAs); put("uri", f.uri)
                        f.sha256?.let { put("sha256", it) }
                    }
                }))
            }
        })
        // 先写临时文件再改名,写到一半被杀不会留下半个文件
        val tmp = File(dir, "${file(conversationId).name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file(conversationId))) {
            file(conversationId).writeText(json.toString())
            tmp.delete()
        }
        firstMessages.remove(file(conversationId).name)
        _changes.tryEmit(conversationId)
    }

    private fun objects(o: JsonObject, k: String) = (o[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** App 进程里共用的那一份(读写都在它的锁里串行)。 */
        fun shared(context: Context): ConversationStore = (context.applicationContext as com.wangzan101.mika.MikaApp).conversations

        /** `current` 文件里表示「新开还没说话的对话」的那个值(对话编号只会是字母数字、`-`、`_`,不会撞上)。 */
        private const val CURRENT_FRESH = "fresh"

        /** 人格切换记录最多留这么多条。 */
        private const val MAX_PERSONA_HISTORY = 50
    }

    // id 来自 newConversation 或外部传入,只留安全字符,免得拼出目录外的路径
    private fun file(id: String) = File(dir, "${safe(id)}.json")
    private fun titleFile(id: String) = File(dir, "${safe(id)}.title")
    private fun pinFile(id: String) = File(dir, "${safe(id)}.pin")
    private fun pageFile(id: String) = File(dir, "${safe(id)}.page")
    private fun branchFile(id: String) = File(dir, "${safe(id)}.branch")
    private fun archiveFile(id: String) = File(dir, "${safe(id)}.archive")
    private fun restoreFile(id: String) = File(dir, "${safe(id)}.restored")
    private fun personaFile(id: String) = File(dir, "${safe(id)}.persona")
    private fun projectFile(id: String) = File(dir, "${safe(id)}.project")

    /** 用户此刻在哪个对话,见 [CurrentChat];不是 `.json`,不进 [list]。 */
    private fun currentFile() = File(dir, "current")
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}

data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinnedAt: Long? = null,
    val page: PageOrigin? = null,
    val branchOf: BranchOrigin? = null,
    val archivedAt: Long? = null,
    /** 最近一次手动取消归档的时间;自动归档给它一段宽限。 */
    val restoredAt: Long? = null,
    /** 所属项目编号;独立对话为 null。 */
    val projectId: String? = null,
    /** 这个对话已有的轮数;只读控制台查询时填,普通列表为 0。 */
    val turnCount: Int = 0,
)

/** 对话用的人格和切换记录;人格本身在 PersonaStore 里,这里只存 id。 */
data class PersonaBinding(val personaId: String, val history: List<PersonaSwitch> = emptyList())

/** 一次人格切换:[from] 为 null 表示从默认换过来。 */
data class PersonaSwitch(val at: Long, val from: String?, val to: String)

/** 分支来源:从 [parentId] 的第 [parentTurn] 轮(0 起)分出来的;[afterTurn] 为 false 时是复制到这一轮之前(第 2 阶段从用户消息重新问)。 */
data class BranchOrigin(val parentId: String, val parentTurn: Int, val afterTurn: Boolean, val at: Long)

/**
 * 用户此刻在哪个对话:App 里新开、还没说过话的是 [Fresh],正在看的是 [Open];没有记录(没进过 Mika、旧版本)为 null。
 * 悬浮胶囊据此接着该接的那个,而不是按「哪个对话文件最后被写过」去猜——新开的对话还没有文件,猜出来的会是上一个。
 */
sealed interface CurrentChat {
    data object Fresh : CurrentChat

    data class Open(val id: String) : CurrentChat
}

/** 对话从哪一页开始:网址、主机名、页面标题(没有为空串)。 */
data class PageOrigin(val url: String, val host: String, val title: String = "")

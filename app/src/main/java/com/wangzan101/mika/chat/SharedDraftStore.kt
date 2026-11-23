package com.wangzan101.mika.chat

import com.wangzan101.mika.cap.sandbox.Attachments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 系统分享附件的私有副本;[fileName] 只由本 App 生成,不保留外部路径。 */
data class SharedDraftAttachment(val fileName: String, val name: String, val size: Long, val photo: Boolean = true)

/** 一个对话里尚未发送的分享内容。只保存草稿,不包含任何执行状态。 */
data class SharedDraft(
    val conversationId: String,
    val text: String,
    val attachments: List<SharedDraftAttachment> = emptyList(),
) {
    constructor(conversationId: String, text: String, attachment: SharedDraftAttachment?) :
        this(conversationId, text, listOfNotNull(attachment))

    /** 兼容单附件读取;没有附件时为 null。 */
    val attachment: SharedDraftAttachment? get() = attachments.firstOrNull()
}

/**
 * 系统分享草稿按对话保存的落盘点。对话编号由对话页决定;这里只负责让草稿跨进程存下来。
 * 元数据先写临时文件再改名;附件在写入元数据前已完整复制,外部 URI 授权之后失效也能继续编辑和发送。
 */
class SharedDraftStore(private val dir: File) {
    private val metadata: File get() = File(dir, METADATA)
    private val importingAttachments = mutableMapOf<String, Int>()

    @Synchronized
    fun read(): SharedDraft? = readAll().lastOrNull()

    @Synchronized
    fun readAll(): List<SharedDraft> {
        val (drafts, pending) = readState()
        if (drafts.isEmpty() && pending == null && metadata.exists()) metadata.delete()
        return drafts
    }

    @Synchronized
    fun readPending(): SharedDraft? = readState().second

    /** 保存待用户确认替换的后台分享草稿;进程在确认弹窗期间被回收重建时可恢复。 */
    @Synchronized
    fun savePending(draft: SharedDraft): Boolean {
        if (!valid(draft)) return false
        val (oldDrafts, oldPending) = readState()
        return write(oldDrafts, draft, oldDrafts, oldPending)
    }

    @Synchronized
    fun clearPending() {
        val (oldDrafts, oldPending) = readState()
        if (oldPending == null) return
        write(oldDrafts, null, oldDrafts, oldPending)
    }

    /** 导入工作区期间保留文件副本，即使同会话收到新分享。 */
    @Synchronized
    fun setImporting(fileName: String?, active: Boolean) {
        if (fileName.isNullOrEmpty()) return
        setImporting(listOf(fileName), active)
    }

    /** 导入工作区期间保留多个文件副本，即使同会话收到新分享。 */
    @Synchronized
    fun setImporting(fileNames: Collection<String>, active: Boolean) {
        for (fileName in fileNames) {
            if (fileName.isEmpty()) continue
            if (active) {
                importingAttachments[fileName] = (importingAttachments[fileName] ?: 0) + 1
            } else {
                val next = (importingAttachments[fileName] ?: 0) - 1
                if (next <= 0) {
                    importingAttachments.remove(fileName)
                    discardFile(fileName)
                } else {
                    importingAttachments[fileName] = next
                }
            }
        }
    }

    /** 保存前要求所有附件已经是本目录里的完整普通文件;失败时不改旧草稿。 */
    @Synchronized
    fun save(draft: SharedDraft): Boolean {
        if (!valid(draft)) return false
        val (old, oldPending) = readState()
        val next = if (old.any { it.conversationId == draft.conversationId }) {
            old.map { if (it.conversationId == draft.conversationId) draft else it }
        } else old + draft
        val nextPending = oldPending?.takeUnless { it.conversationId == draft.conversationId }
        return write(next, nextPending, old, oldPending)
    }

    /** 发送或删除只清理所属对话,其他会话的分享附件继续可用。 */
    @Synchronized
    fun clear(conversationId: String) {
        val (old, oldPending) = readState()
        val next = old.filterNot { it.conversationId == conversationId }
        val nextPending = oldPending?.takeUnless { it.conversationId == conversationId }
        if (next.size == old.size && nextPending == oldPending) return
        write(next, nextPending, old, oldPending)
    }

    /** 移除某个附件([fileName] 非空)或该对话的全部共享附件([fileName] 为空)。 */
    @Synchronized
    fun removeAttachment(conversationId: String, fileName: String? = null) {
        val old = readState().first.firstOrNull { it.conversationId == conversationId } ?: return
        if (old.attachments.isEmpty()) return
        val remaining = if (fileName == null) emptyList() else old.attachments.filterNot { it.fileName == fileName }
        if (remaining.size == old.attachments.size) return
        if (remaining.isEmpty() && old.text.isBlank()) clear(conversationId) else save(old.copy(attachments = remaining))
    }

    /** 尚未绑到对话的副本取消时删除;已经保存成功的同一文件不能删。 */
    @Synchronized
    fun discard(attachment: SharedDraftAttachment?) {
        attachment?.let { discardFile(it.fileName) }
    }

    /** 批量清理尚未绑到对话的附件副本;已保存或导入中的文件不动。 */
    @Synchronized
    fun discard(attachments: Collection<SharedDraftAttachment>) {
        attachments.forEach { discardFile(it.fileName) }
    }

    private fun discardFile(fileName: String) {
        val (drafts, pending) = readState()
        if (fileName in importingAttachments ||
            drafts.any { d -> d.attachments.any { it.fileName == fileName } } ||
            pending?.attachments?.any { it.fileName == fileName } == true
        ) return
        attachmentFile(fileName)?.delete()
    }

    /** 清掉复制中断的临时文件、没来得及绑到对话的副本和损坏的元数据。 */
    @Synchronized
    fun cleanup() {
        val (drafts, pending) = readState()
        if (drafts.isEmpty() && pending == null && metadata.exists()) metadata.delete()
        val retained = retainedFiles(drafts, pending)
        dir.listFiles()?.forEach { f ->
            if (f.name == METADATA || f.name in retained) return@forEach
            if (f.isFile) f.delete()
        }
    }

    private fun retainedFiles(drafts: List<SharedDraft>, pending: SharedDraft?): Set<String> =
        (drafts.flatMap { d -> d.attachments.map(SharedDraftAttachment::fileName) } +
            pending?.attachments.orEmpty().map(SharedDraftAttachment::fileName) +
            importingAttachments.keys).toSet()

    private fun encodeAttachment(attachment: SharedDraftAttachment): JsonObject = buildJsonObject {
        put("file", attachment.fileName)
        put("name", attachment.name)
        put("size", attachment.size)
        put("photo", attachment.photo)
    }

    private fun encodeDraft(draft: SharedDraft): JsonObject = buildJsonObject {
        put("conversation", draft.conversationId)
        put("text", draft.text)
        if (draft.attachments.isNotEmpty()) {
            put("attachments", JsonArray(draft.attachments.map(::encodeAttachment)))
        }
    }

    private fun write(
        drafts: List<SharedDraft>,
        pending: SharedDraft?,
        oldDrafts: List<SharedDraft>,
        oldPending: SharedDraft?,
    ): Boolean {
        dir.mkdirs()
        if (drafts.isEmpty() && pending == null) {
            if (metadata.exists() && !metadata.delete()) return false
        } else {
            val json = buildJsonObject {
                put("drafts", JsonArray(drafts.map(::encodeDraft)))
                pending?.let { put("pending", encodeDraft(it)) }
            }.toString()
            val tmp = File(dir, "$METADATA.tmp")
            val written = runCatching {
                tmp.writeText(json)
                Files.move(tmp.toPath(), metadata.toPath(), StandardCopyOption.REPLACE_EXISTING)
                metadata.isFile && metadata.readText() == json
            }.getOrDefault(false)
            if (!written) {
                tmp.delete()
                return false
            }
        }
        val retained = retainedFiles(drafts, pending)
        (oldDrafts.flatMap { d -> d.attachments.map(SharedDraftAttachment::fileName) } +
            oldPending?.attachments.orEmpty().map(SharedDraftAttachment::fileName))
            .filterNot { it in retained }
            .forEach { attachmentFile(it)?.delete() }
        return true
    }

    fun attachmentFile(fileName: String): File? {
        if (!fileName.matches(FILE)) return null
        val f = File(dir, fileName)
        return f.takeIf { runCatching { it.canonicalFile.parentFile == dir.canonicalFile }.getOrDefault(false) }
    }

    private fun decodeAttachment(fields: JsonObject, requirePhoto: Boolean): SharedDraftAttachment? {
        fun string(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        val photo = if (requirePhoto) {
            string(fields, "photo")?.toBooleanStrictOrNull() ?: return null
        } else true
        return SharedDraftAttachment(
            string(fields, "file") ?: return null,
            string(fields, "name") ?: return null,
            string(fields, "size")?.toLongOrNull() ?: return null,
            photo,
        )
    }

    private fun decodeDraft(o: JsonObject): SharedDraft? {
        fun string(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        val attachments = when {
            "attachments" in o -> {
                val arr = o["attachments"] as? JsonArray ?: return null
                arr.map { element ->
                    val fields = element as? JsonObject ?: return null
                    decodeAttachment(fields, requirePhoto = true) ?: return null
                }
            }
            // 兼容旧版单附件格式(attachment)及更早的单图片格式(image,无 photo 标记)。
            "attachment" in o || "image" in o -> {
                val encoded = o["attachment"] ?: o["image"]
                val fields = encoded as? JsonObject ?: return null
                listOf(decodeAttachment(fields, requirePhoto = "attachment" in o) ?: return null)
            }
            else -> emptyList()
        }
        return SharedDraft(
            string(o, "conversation") ?: return null,
            string(o, "text") ?: return null,
            attachments,
        ).takeIf(::valid)
    }

    private fun readState(): Pair<List<SharedDraft>, SharedDraft?> = runCatching {
        val root = Json.parseToJsonElement(metadata.readText()) as? JsonObject ?: return emptyList<SharedDraft>() to null
        // 兼容原来只有一份分享草稿的文件。
        val entries = (root["drafts"] as? JsonArray)?.toList() ?: listOf(root)
        val drafts = entries.mapNotNull { (it as? JsonObject)?.let(::decodeDraft) }
        val pending = (root["pending"] as? JsonObject)?.let(::decodeDraft)
        drafts to pending
    }.getOrDefault(emptyList<SharedDraft>() to null)

    private fun valid(draft: SharedDraft): Boolean {
        if (!draft.conversationId.matches(ID) || draft.text.length > MAX_TEXT_CHARS) return false
        if (draft.text.isBlank() && draft.attachments.isEmpty()) return false
        if (draft.attachments.size > MAX_ATTACHMENTS) return false
        val seen = mutableSetOf<String>()
        return draft.attachments.all { attachment ->
            if (!seen.add(attachment.fileName)) return@all false
            val file = attachmentFile(attachment.fileName)?.takeIf { it.isFile } ?: return@all false
            attachment.name.isNotBlank() && attachment.name.length <= 240 && attachment.size >= 0 &&
                attachment.size <= Attachments.MAX_BYTES && file.length() == attachment.size
        }
    }

    companion object {
        const val MAX_TEXT_CHARS = 100_000
        const val MAX_ATTACHMENTS = 9
        private const val METADATA = "draft.json"
        private val ID = Regex("[A-Za-z0-9_-]{1,80}")
        private val FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
    }
}

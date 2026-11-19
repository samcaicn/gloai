package com.wangzan101.mika.core.export

import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import java.util.Locale

/**
 * 把对话、运行记录和资产解析成与渲染无关的 [ExportDocument]。
 * 内容过滤只在这里做一次:后端拿到的就是最终要导出的内容。
 */
class ExportDocumentBuilder(
    private val source: ExportSource,
    private val labels: ExportLabels,
    private val appVersion: String,
    private val language: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        const val MAX_TOOL_TEXT = 2_000
    }

    private val assets = LinkedHashMap<String, ExportAsset>()
    private val usedNames = mutableSetOf<String>()
    private val warnings = mutableListOf<ExportWarning>()

    suspend fun build(
        conversationId: String,
        title: String,
        selection: ExportSelection,
        content: ExportContent,
    ): ExportDocument {
        assets.clear()
        usedNames.clear()
        warnings.clear()
        val turns = source.turns(conversationId)
        val blocks = mutableListOf<ExportBlock>()

        for (turn in turns) {
            if (selected(ExportIds.user(turn.index), selection)) {
                val parsed = parseMessage(turn.userText)
                blocks += ExportBlock.UserMessage(
                    id = ExportIds.user(turn.index),
                    text = redact(parsed.words, content),
                    quote = parsed.quote?.let { QuotedText(it.first, redact(it.second, content)) },
                    page = parsed.page?.let {
                        val url = ExportRedactor.apply(Redactor.network(it.url).url ?: it.url, content.redaction)
                        PageRef(url, redact(it.title, content), it.selection?.let { s -> redact(s, content) })
                    },
                    attachments = if (content.includeAttachments) {
                        parsed.attachments.mapNotNull { (path, _) -> asset(AssetRequest.Workspace(path), kindFor(path), path.substringAfterLast('/'), content) }
                    } else emptyList(),
                    screens = if (content.includeScreens) {
                        parsed.screens.mapNotNull { id -> asset(AssetRequest.UserScreen(id), AssetKind.UserScreen, "screen-$id.jpg", content) }
                    } else emptyList(),
                    atMs = turn.atMs.takeIf { content.includeTimestamps },
                )
            }

            turn.steers.forEachIndexed { i, raw ->
                val id = ExportIds.steer(turn.index, i)
                if (!selected(id, selection)) return@forEachIndexed
                val parsed = parseMessage(raw)
                val steerAssets = buildList {
                    if (content.includeScreens) parsed.screens.mapNotNullTo(this) { s -> asset(AssetRequest.UserScreen(s), AssetKind.UserScreen, "screen-$s.jpg", content) }
                    if (content.includeAttachments) parsed.attachments.mapNotNullTo(this) { (path, _) -> asset(AssetRequest.Workspace(path), kindFor(path), path.substringAfterLast('/'), content) }
                }
                blocks += ExportBlock.SteerMessage(
                    id = id,
                    text = redact(parsed.words, content),
                    interruptedText = turn.interruptedReplies.getOrNull(i)?.takeIf(String::isNotEmpty)?.let { redact(it, content) },
                    finishedText = turn.finishedReplies.getOrNull(i)?.takeIf(String::isNotEmpty)?.let { redact(it, content) },
                    assets = steerAssets,
                )
            }

            val answerId = ExportIds.answer(turn.index)
            if (!selected(answerId, selection)) continue
            val run = if (content.includeToolSteps || content.includeModel || content.includeTimestamps) {
                turn.runId?.let { source.run(it) }
            } else null
            if (content.includeToolSteps && turn.runId != null && run == null) {
                warnings += ExportWarning("run_record_missing", "turn ${turn.index}")
            }
            val atMs = if (content.includeTimestamps) {
                val t = turn.atMs ?: run?.startedAtMs
                if (t == null) warnings += ExportWarning("timestamp_unknown", "turn ${turn.index}")
                t
            } else null
            val steps = if (content.includeToolSteps) {
                run?.steps.orEmpty().map { s ->
                    s.copy(
                        note = s.note?.let { redact(it, content) },
                        argsJson = clip(s.argsJson, content),
                        result = clip(s.result, content),
                    )
                }
            } else emptyList()
            val files = if (content.includeAttachments) {
                turn.files.mapNotNull { f -> asset(f.request, AssetKind.File, f.name, content) }
            } else emptyList()
            blocks += ExportBlock.Answer(
                id = answerId,
                markdown = redact(answerMarkdown(turn), content),
                outcome = outcomeOf(turn, content),
                atMs = atMs,
                model = run?.model?.takeIf { content.includeModel },
                toolSteps = steps,
                files = files,
            )
        }

        val finalTitle = redact(title.trim().ifEmpty { turns.firstOrNull()?.userText?.take(80) ?: "Mika" }, content)
        return ExportDocument(
            meta = ExportMeta(
                conversationId = conversationId,
                title = finalTitle,
                exportedAtMs = now(),
                appVersion = appVersion,
                language = language,
            ),
            blocks = blocks,
            assets = assets.values.toList(),
            warnings = warnings.toList(),
        )
    }

    private fun selected(id: ExportBlockId, selection: ExportSelection) = selection.allSelected || id in selection.blocks

    private fun outcomeOf(turn: ExportTurnRecord, content: ExportContent): AnswerOutcome = when (turn.status) {
        "succeeded" -> AnswerOutcome.Succeeded
        "unverified" -> AnswerOutcome.Unverified(
            turn.note?.let { redact(it, content) },
            turn.unconfirmed.map { redact(it, content) },
        )
        "cancelled" -> AnswerOutcome.Cancelled(
            turn.outcome.takeIf { it.isNotBlank() && it != Conversation.CANCELLED_OUTCOME }?.let { redact(it, content) },
        )
        else -> AnswerOutcome.Failed(redact(turn.outcome, content))
    }

    private suspend fun asset(request: AssetRequest, kind: AssetKind, preferredName: String, content: ExportContent): ExportAsset? {
        val key = when (request) {
            is AssetRequest.Workspace -> "ws:${request.path}"
            is AssetRequest.UserScreen -> "screen:${request.screenId}"
            is AssetRequest.ContentUri -> "uri:${request.uri}"
        }
        assets[key]?.let { return it }
        val resolved = source.resolve(request)
        if (resolved == null) {
            warnings += ExportWarning("asset_missing", redact(preferredName, content))
            return null
        }
        val rawName = resolved.fileName.ifEmpty { preferredName }
        val name = ExportNames.unique(ExportNames.safeFileName(redact(rawName, content)), usedNames)
        val asset = resolved.copy(id = key, kind = if (kind == AssetKind.File) resolved.kind else kind, fileName = name)
        assets[key] = asset
        return asset
    }

    /** 失败/取消的原因已经放在 outcome 里,正文不重复;成功的回答和未核实的回答保留原文。 */
    private fun answerMarkdown(turn: ExportTurnRecord): String = when (turn.status) {
        "failed", "cancelled" -> ""
        else -> turn.outcome
    }

    private fun kindFor(path: String): AssetKind = when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> AssetKind.Image
        else -> AssetKind.File
    }

    private fun redact(text: String, content: ExportContent) = ExportRedactor.apply(text, content.redaction)

    private fun clip(text: String?, content: ExportContent): String? {
        val redacted = text?.let { redact(it, content) } ?: return null
        if (redacted.length <= MAX_TOOL_TEXT) return redacted
        return redacted.take(MAX_TOOL_TEXT) + " …(" + String.format(Locale.ROOT, labels.truncated, redacted.length) + ")"
    }

    private data class ParsedMessage(
        val words: String,
        val quote: Pair<QuoteSource, String>?,
        val page: AttachedPage.Page?,
        val screens: List<String>,
        val attachments: List<Pair<String, String>>,
    )

    /** 组合消息按 网页来源 → 引用 → 屏幕 → 附件 → 正文 的顺序拆,后面的解析只看剩下的字。 */
    private fun parseMessage(raw: String): ParsedMessage {
        var text = raw
        val page = AttachedPage.parse(text).let { p -> p.page?.also { text = p.words } }
        val quote = QuoteMessage.parse(text).let { p ->
            p.quote?.also { text = p.words }?.let { q -> (if (q.from == QuoteMessage.From.Mika) QuoteSource.Mika else QuoteSource.User) to q.text }
        }
        val screens = UserScreenMessage.parse(text).let { p -> p.screens.also { if (it.isNotEmpty()) text = p.words } }
        val attachments = AttachmentLines.parse(text).let { p -> p.files.also { text = p.words } }
        return ParsedMessage(text.trim(), quote, page, screens, attachments)
    }
}

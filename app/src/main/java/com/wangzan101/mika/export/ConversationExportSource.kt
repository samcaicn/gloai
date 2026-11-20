package com.wangzan101.mika.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.export.AssetKind
import com.wangzan101.mika.core.export.AssetRequest
import com.wangzan101.mika.core.export.ExportAsset
import com.wangzan101.mika.core.export.ExportAssetReader
import com.wangzan101.mika.core.export.ExportFileRef
import com.wangzan101.mika.core.export.ExportRunRecord
import com.wangzan101.mika.core.export.ExportSource
import com.wangzan101.mika.core.export.ExportTurnRecord
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.workspace.GatedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream

/** 把对话文件、运行记录和工作区资产接给纯 core 的导出构建器。 */
class ConversationExportSource(
    context: Context,
    private val conversationId: String,
    internal val records: List<Pair<Turn, List<SharedFile>>>,
) : ExportSource, ExportAssetReader {
    private val app = context.applicationContext
    private val runs = RunStore(app)
    private val dirs = SandboxDirs(app)

    override suspend fun turns(conversationId: String): List<ExportTurnRecord> {
        require(conversationId == this.conversationId)
        return records.mapIndexed { index, (turn, files) ->
            ExportTurnRecord(
                index = index,
                userText = turn.userText,
                outcome = turn.outcome,
                status = turn.status,
                note = turn.note,
                unconfirmed = turn.unconfirmed,
                runId = turn.runId,
                atMs = turn.atMs,
                steers = turn.steers,
                interruptedReplies = turn.interruptedReplies,
                finishedReplies = turn.finishedReplies,
                files = files.map { f ->
                    ExportFileRef(f.name, f.size, AssetRequest.ContentUri(f.uri))
                },
            )
        }
    }

    override suspend fun run(runId: String): ExportRunRecord? = withContext(Dispatchers.IO) {
        val summary = runs.summary(runId) ?: return@withContext null
        ExportRunRecord(model = summary.model, startedAtMs = summary.startedAtMs, steps = RunEventToolSteps.of(runs.events(runId)))
    }

    override suspend fun resolve(request: AssetRequest): ExportAsset? = withContext(Dispatchers.IO) {
        val workspace = dirs.workspace(WorkspaceResolver.resolveFor(app, conversationId))
        when (request) {
            is AssetRequest.Workspace -> {
                // 只读工作区里的相对路径;gate 会拒绝链接、目录和非普通文件
                val rel = request.path.removePrefix("/workspace/")
                if (!request.path.startsWith("/workspace/") || rel.isEmpty()) return@withContext null
                val file = runCatching { dirs.gate.openRead(workspace, rel) }.getOrNull() ?: return@withContext null
                val size = file.size
                file.close()
                asset(request, nameOf(rel), size)
            }
            is AssetRequest.UserScreen -> {
                val rel = "screens/${request.screenId}.jpg"
                val file = runCatching { dirs.gate.openRead(workspace, rel) }.getOrNull() ?: return@withContext null
                val size = file.size
                file.close()
                asset(request, "screen-${request.screenId}.jpg", size)
            }
            is AssetRequest.ContentUri -> {
                val uri = Uri.parse(request.uri)
                var name = uri.lastPathSegment ?: "file"
                var size = 0L
                runCatching {
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { name = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                }
                // 打开一次确认还能读;真正的字节在渲染时按需读
                val readable = runCatching { app.contentResolver.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false)
                if (!readable) null else asset(request, name, size)
            }
        }
    }

    override suspend fun open(asset: ExportAsset): InputStream? = withContext(Dispatchers.IO) {
        runCatching {
            val workspace = dirs.workspace(WorkspaceResolver.resolveFor(app, conversationId))
            when (val request = asset.request) {
                is AssetRequest.Workspace -> gated(dirs.gate.openRead(workspace, request.path.removePrefix("/workspace/")))
                is AssetRequest.UserScreen -> gated(dirs.gate.openRead(workspace, "screens/${request.screenId}.jpg"))
                is AssetRequest.ContentUri -> app.contentResolver.openInputStream(Uri.parse(request.uri))
            }
        }.getOrNull()
    }

    private fun gated(file: GatedFile): InputStream = object : FilterInputStream(file.input()) {
        override fun close() {
            super.close()
            file.close()
        }
    }

    private fun asset(request: AssetRequest, name: String, size: Long): ExportAsset {
        val mime = mimeOf(name)
        return ExportAsset(
            id = request.toString(),
            kind = if (mime.startsWith("image/")) AssetKind.Image else AssetKind.File,
            fileName = name,
            mime = mime,
            sizeBytes = size,
            request = request,
        )
    }

    private fun nameOf(rel: String) = rel.substringAfterLast('/')

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "md" -> "text/markdown"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}

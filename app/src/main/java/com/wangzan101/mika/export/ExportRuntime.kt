package com.wangzan101.mika.export

import android.content.Context
import android.net.Uri
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.export.DirectoryExportSink
import com.wangzan101.mika.core.export.ExportBlock
import com.wangzan101.mika.core.export.ExportContent
import com.wangzan101.mika.core.export.ExportDocumentBuilder
import com.wangzan101.mika.core.export.ExportFormat
import com.wangzan101.mika.core.export.ExportManifest
import com.wangzan101.mika.core.export.ExportPackage
import com.wangzan101.mika.core.export.ExportPhase
import com.wangzan101.mika.core.export.ExportPresentation
import com.wangzan101.mika.core.export.ExportProgress
import com.wangzan101.mika.core.export.ExportRegistry
import com.wangzan101.mika.core.export.ExportSelection
import com.wangzan101.mika.core.export.ExportWarning
import com.wangzan101.mika.core.export.ManifestContent
import com.wangzan101.mika.core.export.ManifestConversation
import com.wangzan101.mika.core.export.ManifestCounts
import com.wangzan101.mika.core.export.ManifestSelection
import com.wangzan101.mika.core.export.ManifestWarning
import com.wangzan101.mika.core.export.markdown.MarkdownExportBackend
import com.wangzan101.mika.core.export.json.JsonExportBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

data class ExportOutcome(
    val warnings: List<ExportWarning>,
    val entryCount: Int,
    val bytes: Long,
)

/**
 * P1 的导出执行器:构建文档 → 后端渲染到 cache 暂存 → 复制资产 → 打包 → 写到 SAF。
 * 先写暂存再复制,失败/取消只清暂存,不删目的地。
 */
class ExportRuntime(private val context: Context) {
    private val labels = ExportLabelsUi.of(context)
    private val registry = ExportRegistry(
        formats = listOf(
            ExportFormat("markdown", "export_format_markdown", "text/markdown", "md"),
            ExportFormat("json", "export_format_json", "application/json", "json"),
        ),
        backends = listOf(MarkdownExportBackend(labels), JsonExportBackend()),
    )

    fun formats() = registry.formats()

    suspend fun export(
        source: ConversationExportSource,
        title: String,
        selection: ExportSelection,
        content: ExportContent,
        presentation: ExportPresentation,
        formatId: String,
        destination: Uri,
        bundle: Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): ExportOutcome = withContext(Dispatchers.IO) {
        val format = registry.format(formatId) ?: throw IOException("unknown format: $formatId")
        val backend = registry.defaultBackend(formatId)
        val staging = File(context.cacheDir, "exports/staging/${UUID.randomUUID()}")
        val temp = File(context.cacheDir, "exports/${UUID.randomUUID()}.${format.extension}")
        try {
            onProgress(ExportProgress(ExportPhase.Preparing, 0, 0))
            val document = ExportDocumentBuilder(source, labels, BuildConfig.VERSION_NAME, language()).build(
                conversationId = selection.conversationId,
                title = title,
                selection = selection,
                content = content,
            )
            backend.render(document, presentation, source, DirectoryExportSink(staging), onProgress)

            val warnings = document.warnings.toMutableList()
            val sink = DirectoryExportSink(staging)
            var totalAssets = 0L
            document.assets.forEachIndexed { index, asset ->
                currentCoroutineContext().ensureActive()
                onProgress(ExportProgress(ExportPhase.Packaging, index, document.assets.size))
                if (asset.sizeBytes > MAX_ASSET_BYTES) {
                    warnings += ExportWarning("asset_too_large", asset.fileName)
                    return@forEachIndexed
                }
                val remaining = MAX_TOTAL_ASSETS - totalAssets
                if (remaining <= 0) {
                    warnings += ExportWarning("asset_total_too_large", asset.fileName)
                    return@forEachIndexed
                }
                val target = File(staging, "assets/${asset.fileName}")
                try {
                    val input = source.open(asset)
                    if (input == null) {
                        warnings += ExportWarning("asset_missing", asset.fileName)
                        return@forEachIndexed
                    }
                    totalAssets += input.use { from ->
                        sink.open("assets/${asset.fileName}").use { to -> copyLimited(from, to, minOf(MAX_ASSET_BYTES, remaining)) }
                    }
                } catch (e: CancellationException) {
                    target.delete()
                    throw e
                } catch (e: AssetTooLarge) {
                    target.delete()
                    warnings += ExportWarning("asset_too_large", asset.fileName)
                } catch (e: Exception) {
                    target.delete()
                    warnings += ExportWarning("asset_unreadable", asset.fileName)
                }
            }

            val entries = ExportPackage.contentEntries(staging)
            val bytes = if (bundle) {
                val manifest = manifest(document, selection, content, entries, warnings)
                ExportPackage.writeManifest(staging, manifest)
                ExportPackage.zip(staging, temp)
                temp.length()
            } else {
                if (document.assets.isNotEmpty()) throw IOException("assets require a ZIP destination")
                if (!ExportPackage.copySingle(staging, temp)) throw IOException("nothing to export")
                temp.length()
            }
            onProgress(ExportProgress(ExportPhase.Delivering, 0, 0, bytes))
            copyToDestination(temp, destination) { done -> onProgress(ExportProgress(ExportPhase.Delivering, 0, 0, done)) }
            ExportOutcome(warnings = warnings, entryCount = entries.size, bytes = bytes)
        } finally {
            staging.deleteRecursively()
            temp.delete()
        }
    }

    private fun manifest(
        document: com.wangzan101.mika.core.export.ExportDocument,
        selection: ExportSelection,
        content: ExportContent,
        entries: List<com.wangzan101.mika.core.export.ManifestEntry>,
        warnings: List<ExportWarning>,
    ) = ExportManifest(
        appVersion = BuildConfig.VERSION_NAME,
        exportedAt = Instant.ofEpochMilli(document.meta.exportedAtMs).toString(),
        conversation = ManifestConversation(document.meta.conversationId, document.meta.title),
        selection = ManifestSelection(
            turns = document.blocks.mapNotNull { it.turnIndex() }.distinct().size,
            blocks = document.blocks.size,
            allSelected = selection.allSelected,
        ),
        content = ManifestContent(
            toolSteps = content.includeToolSteps,
            attachments = content.includeAttachments,
            screens = content.includeScreens,
            timestamps = content.includeTimestamps,
            model = content.includeModel,
            redaction = content.redaction.name.lowercase(),
        ),
        counts = ManifestCounts(
            blocks = document.blocks.size,
            toolSteps = document.blocks.filterIsInstance<ExportBlock.Answer>().sumOf { it.toolSteps.size },
            assets = document.assets.size,
        ),
        entries = entries,
        warnings = warnings.map { ManifestWarning(it.code, it.detail) },
    )

    private fun ExportBlock.turnIndex(): Int? = id.value.drop(1).substringBefore('-').toIntOrNull()

    private suspend fun copyToDestination(file: File, destination: Uri, progress: (Long) -> Unit) {
        currentCoroutineContext().ensureActive()
        val output = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("cannot open destination")
        output.use { out -> file.inputStream().use { input -> copy(input, out, progress) } }
    }

    private suspend fun copy(input: InputStream, output: OutputStream, progress: ((Long) -> Unit)? = null): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
            progress?.invoke(total)
        }
        return total
    }

    private suspend fun copyLimited(input: InputStream, output: OutputStream, max: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            if (total + n > max) throw AssetTooLarge()
            output.write(buf, 0, n)
            total += n
        }
        return total
    }

    private class AssetTooLarge : Exception()

    private fun language(): String = java.util.Locale.getDefault().language

    private companion object {
        const val MAX_ASSET_BYTES = 50L shl 20
        const val MAX_TOTAL_ASSETS = 200L shl 20
    }
}

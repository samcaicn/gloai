package com.wangzan101.mika.core.export

import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.export.json.JsonExportBackend
import com.wangzan101.mika.core.export.markdown.MarkdownExportBackend
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

class ExportTest {
    private val labels = ExportLabels.English

    private fun doc(
        content: ExportContent = ExportContent(),
        selection: ExportSelection = ExportSelection("c1"),
        source: ExportSource = Source(),
    ): ExportDocument = kotlinx.coroutines.runBlocking {
        ExportDocumentBuilder(source, labels, "test", "en") { 5_000L }.build("c1", "标题", selection, content)
    }

    @Test fun parsesPageQuoteScreenAttachmentAndBuildsBlocks() {
        val document = doc(
            ExportContent(
                includeToolSteps = true, includeTimestamps = true, includeModel = true,
                redaction = RedactionLevel.SecretsAndPii,
            ),
        )
        assertEquals(3, document.blocks.size)
        val user = document.blocks[0] as ExportBlock.UserMessage
        assertEquals("请看看 ***", user.text)
        assertEquals("old", user.quote?.text)
        assertEquals("https://example.com/p", user.page?.url)
        assertEquals("sel", user.page?.selection)
        assertEquals(1, user.screens.size)
        assertEquals(1, user.attachments.size)
        assertEquals(1_000L, user.atMs)

        val steer = document.blocks[1] as ExportBlock.SteerMessage
        assertEquals("改成这个", steer.text)

        val answer = document.blocks[2] as ExportBlock.Answer
        assertEquals("好了", answer.markdown)
        assertEquals("gpt-test", answer.model)
        assertEquals(1_000L, answer.atMs)
        assertEquals(1, answer.toolSteps.size)
        assertEquals("phone_read", answer.toolSteps.single().tool)
        assertEquals(2, document.assets.size)
        assertTrue(document.warnings.isEmpty())
    }

    @Test fun selectionKeepsOnlyChosenBlocks() {
        val onlyAnswer = doc(selection = ExportSelection("c1", setOf(ExportIds.answer(0)), allSelected = false))
        assertEquals(listOf(ExportIds.answer(0)), onlyAnswer.blocks.map { it.id })
        assertEquals(0, onlyAnswer.assets.size)
    }

    @Test fun missingRunRecordIsWarnedNotFatal() {
        val noRun = object : ExportSource {
            override suspend fun turns(conversationId: String) = Source().turns(conversationId)
            override suspend fun run(runId: String): ExportRunRecord? = null
            override suspend fun resolve(request: AssetRequest): ExportAsset? = Source().resolve(request)
        }
        val document = kotlinx.coroutines.runBlocking {
            ExportDocumentBuilder(noRun, labels, "test", "en") { 5_000L }
                .build("c1", "标题", ExportSelection("c1"), ExportContent(includeToolSteps = true, includeModel = true))
        }
        assertTrue(document.warnings.any { it.code == "run_record_missing" })
        assertTrue((document.blocks.last() as ExportBlock.Answer).toolSteps.isEmpty())
        assertNull((document.blocks.last() as ExportBlock.Answer).model)
    }

    @Test fun markdownContainsStructuredContent() = kotlinx.coroutines.runBlocking {
        val root = createTempDir()
        try {
            MarkdownExportBackend(labels).render(doc(ExportContent(includeToolSteps = true, includeModel = true)), ExportPresentation(), Reader, DirectoryExportSink(root)) {}
            val text = File(root, "document.md").readText()
            assertTrue(text.contains("# 标题"))
            assertTrue(text.contains("## User"))
            assertTrue(text.contains("## Mika"))
            assertTrue(text.contains("![screen-s1.jpg](assets/screen-s1.jpg)"))
            assertTrue(text.contains("[a.txt](assets/a.txt)"))
            assertTrue(text.contains("### Tool steps"))
            assertTrue(text.contains("`phone_read`"))
            assertTrue(text.contains("```text"))
            assertTrue(text.contains("*Model: gpt-test*"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun jsonContainsStableBlockTypes() = kotlinx.coroutines.runBlocking {
        val root = createTempDir()
        try {
            JsonExportBackend().render(doc(ExportContent(includeToolSteps = true)), ExportPresentation(), Reader, DirectoryExportSink(root)) {}
            val rootJson = Json.parseToJsonElement(File(root, "document.json").readText()).jsonObject
            assertEquals("mika.conversation-export", rootJson["format"]!!.jsonPrimitive.content)
            val types = rootJson["blocks"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
            assertEquals(listOf("user", "steer", "answer"), types)
            val answer = rootJson["blocks"]!!.jsonArray.last().jsonObject
            assertEquals(1, answer["toolSteps"]!!.jsonArray.size)
            assertEquals("phone_read", answer["toolSteps"]!!.jsonArray.single().jsonObject["tool"]!!.jsonPrimitive.content)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun stopSummaryIsExportedOnceAsTheCancellationOutcome() = kotlinx.coroutines.runBlocking {
        val summary = "已整理三份文件"
        val document = doc(
            selection = ExportSelection("c1", setOf(ExportIds.answer(0)), allSelected = false),
            source = Source(status = "cancelled", outcome = summary),
        )
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val sink = DirectoryExportSink(root)
            MarkdownExportBackend(labels).render(document, ExportPresentation(), Reader, sink) {}
            val text = File(root, "document.md").readText()
            assertEquals(1, text.lines().count { summary in it })
            assertTrue(text.contains("> ${labels.cancelled}\n> $summary"))

            JsonExportBackend().render(document, ExportPresentation(), Reader, sink) {}
            val json = Json.parseToJsonElement(File(root, "document.json").readText()).jsonObject
            val answer = json.getValue("blocks").jsonArray.single().jsonObject
            assertEquals(summary, answer.getValue("outcome").jsonObject.getValue("summary").jsonPrimitive.content)
            assertEquals("", answer.getValue("markdown").jsonPrimitive.content)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cancelledTurnWithoutStopSummaryExportsOnlyTheStatus() {
        val document = doc(source = Source(status = "cancelled", outcome = Conversation.CANCELLED_OUTCOME))
        val answer = document.blocks.filterIsInstance<ExportBlock.Answer>().single()
        assertEquals(AnswerOutcome.Cancelled(null), answer.outcome)
        assertEquals("", answer.markdown)
    }

    @Test fun namesAreSafeAndUnique() {
        assertEquals("ab.txt", ExportNames.safeFileName("../a\\b.txt"))
        assertEquals("attachment", ExportNames.safeFileName("..."))
        val used = mutableSetOf<String>()
        assertEquals("报表.pdf", ExportNames.unique("报表.pdf", used))
        assertEquals("报表 (2).pdf", ExportNames.unique("报表.pdf", used))
        assertEquals("报表 (3).pdf", ExportNames.unique("报表.pdf", used))
        val long = ExportNames.fit("很".repeat(200) + ".md")
        assertTrue(long.endsWith(".md"))
        assertTrue(long.codePointCount(0, long.length) <= ExportNames.MAX_NAME)
        val longWithoutExtension = ExportNames.fit("x".repeat(200))
        assertTrue(longWithoutExtension.codePointCount(0, longWithoutExtension.length) <= ExportNames.MAX_NAME)
        assertTrue(longWithoutExtension.toByteArray(Charsets.UTF_8).size <= ExportNames.MAX_NAME_BYTES)
        assertFalse(ExportNames.entryPath("assets/a.txt").startsWith("/"))
    }

    @Test fun unicodeAssetNamesFitFilesystemLimitsAndKeepTheirExtension() {
        val root = createTempDir()
        try {
            val name = ExportNames.safeFileName("界".repeat(120) + ".txt")
            File(root, name).writeText("asset")
            assertEquals("asset", File(root, name).readText())
            assertTrue(name.endsWith(".txt"))
            assertTrue(name.codePointCount(0, name.length) <= ExportNames.MAX_NAME)
            assertTrue(name.toByteArray(Charsets.UTF_8).size <= ExportNames.MAX_NAME_BYTES)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun uniqueUnicodeNamesStayWithinTheFilesystemByteLimit() {
        val name = ExportNames.fit("界".repeat(200) + ".txt")
        val used = mutableSetOf(name)
        val duplicate = ExportNames.unique(name, used)

        assertTrue(duplicate.endsWith(".txt"))
        assertTrue(duplicate.codePointCount(0, duplicate.length) <= ExportNames.MAX_NAME)
        assertTrue(duplicate.toByteArray(Charsets.UTF_8).size <= ExportNames.MAX_NAME_BYTES)
        assertTrue(used.contains(duplicate))
    }

    @Test(expected = IllegalArgumentException::class)
    fun entryPathRejectsTraversal() {
        ExportNames.entryPath("assets/../secret")
    }

    @Test fun packageWritesManifestAndZip() {
        val root = createTempDir()
        val out = createTempDir()
        try {
            File(root, "assets").mkdirs()
            File(root, "document.md").writeText("# hi")
            File(root, "assets/a.txt").writeText("abc")
            File(root, "assets/manifest.json").writeText("{}")
            val entries = ExportPackage.contentEntries(root)
            assertEquals(listOf("assets/a.txt", "assets/manifest.json", "document.md"), entries.map { it.path })
            assertEquals(ExportPackage.sha256(File(root, "assets/a.txt")), entries.first().sha256)
            val manifest = ExportManifest(
                appVersion = "test", exportedAt = "2026-11-03T00:00:00Z",
                conversation = ManifestConversation("c1", "标题"),
                selection = ManifestSelection(1, 3, true),
                content = ManifestContent(true, true, true, false, true, "secrets"),
                counts = ManifestCounts(3, 1, 1), entries = entries,
            )
            ExportPackage.writeManifest(root, manifest)
            ExportPackage.zip(root, File(out, "x.zip"))
            ZipFile(File(out, "x.zip")).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(listOf("assets/a.txt", "assets/manifest.json", "document.md", "manifest.json"), names)
            }
            assertFalse(ExportPackage.copySingle(root, File(out, "single.md")))
        } finally {
            root.deleteRecursively()
            out.deleteRecursively()
        }
    }

    @Test fun singleFilePackageCopiesTheOnlyDocument() {
        val root = createTempDir()
        val out = createTempDir()
        try {
            File(root, "document.md").writeText("# hi")
            assertTrue(ExportPackage.copySingle(root, File(out, "x.md")))
            assertEquals("# hi", File(out, "x.md").readText())
        } finally {
            root.deleteRecursively()
            out.deleteRecursively()
        }
    }

    @Test fun redactsSecretsAlwaysAndPiiOnRequest() {
        val secret = ExportRedactor.apply("key sk-1234567890abcdef", RedactionLevel.Secrets)
        assertTrue(secret.contains("***"))
        assertFalse(secret.contains("sk-1234567890abcdef"))
        val pii = ExportRedactor.apply("mail a@b.com phone 13800138000 path /data/user/0/x", RedactionLevel.SecretsAndPii)
        assertFalse(pii.contains("a@b.com"))
        assertFalse(pii.contains("13800138000"))
        assertFalse(pii.contains("/data/user/0/x"))
        val kept = ExportRedactor.apply("mail a@b.com", RedactionLevel.Secrets)
        assertTrue(kept.contains("a@b.com"))
    }

    @Test fun markdownEscapesUntrustedFields() = kotlinx.coroutines.runBlocking {
        val root = createTempDir()
        try {
            val asset = ExportAsset(
                "a", AssetKind.Image, "x](javascript:alert(1)).jpg", "image/jpeg", 1,
                AssetRequest.ContentUri("content://x"),
            )
            val document = ExportDocument(
                meta = ExportMeta("c1", "[x](javascript:alert(1)) <img src=x onerror=1>", 1_000L, "test", "en"),
                blocks = listOf(
                    ExportBlock.UserMessage(
                        ExportIds.user(0), "[x](javascript:alert(1)) <img src=x onerror=1>", null,
                        PageRef("https://example.com/x?a=1", "x](javascript:alert(1))", "sel <b>"),
                        listOf(asset), emptyList(), null,
                    ),
                    ExportBlock.Answer(
                        ExportIds.answer(0),
                        "hello <script>alert(1)</script>\n\n`List<String>`\n\n    ```\n<script>indented</script>",
                        AnswerOutcome.Succeeded, null, null,
                        listOf(ToolStep(1, null, "tool", null, "````\n<script>", false, 0, null)), emptyList(),
                    ),
                ),
                assets = listOf(asset),
            )
            MarkdownExportBackend(labels).render(document, ExportPresentation(), Reader, DirectoryExportSink(root)) {}
            val text = File(root, "document.md").readText()
            assertFalse(text.contains("[x](javascript:"))
            assertFalse(Regex("""(?<!\\)\]\(javascript:""").containsMatchIn(text))
            assertFalse(text.substringBefore("### Tool steps").contains("<script>"))
            assertFalse(Regex("""(?<!\\)<img""").containsMatchIn(text))
            assertTrue(text.contains("\\[x\\]"))
            assertTrue(text.contains("&lt;script>"))
            assertTrue(text.contains("`List<String>`"))
            assertTrue(text.contains("&lt;script>indented"))
            assertFalse(text.substringBefore("### Tool steps").contains("<script>indented"))
            assertTrue(text.contains("`````text"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun redactsPageUrlTitleAndAttachmentName() = kotlinx.coroutines.runBlocking {
        val source = object : ExportSource {
            override suspend fun turns(conversationId: String) = listOf(
                ExportTurnRecord(
                    index = 0,
                    userText = AttachmentLines.compose(
                        AttachedPage.compose(
                            AttachedPage.Page("https://example.com/cb?token=abc&email=a@b.com", "T", "s"),
                            "请看",
                        ),
                        listOf("/workspace/inbox/x.pdf" to "1 KB"),
                    ),
                    outcome = "好了",
                    status = "succeeded",
                ),
            )
            override suspend fun run(runId: String) = null
            override suspend fun resolve(request: AssetRequest) =
                if (request is AssetRequest.Workspace) {
                    ExportAsset("", AssetKind.File, "alice@example.com.pdf", "application/pdf", 1, request)
                } else null
        }
        val document = ExportDocumentBuilder(source, labels, "test", "en") { 1_000L }.build(
            "c1", "key sk-1234567890abcdef", ExportSelection("c1"),
            ExportContent(redaction = RedactionLevel.SecretsAndPii),
        )
        val user = document.blocks.filterIsInstance<ExportBlock.UserMessage>().single()
        val url = user.page!!.url
        assertFalse(url.contains("token=abc"))
        assertFalse(url.contains("a@b.com"))
        assertTrue(url.contains("token=***"))
        assertFalse(document.meta.title.contains("sk-1234567890abcdef"))
        assertFalse(user.attachments.single().fileName.contains("@"))
    }

    @Test fun markdownWarningsUseLocalizedLabels() = kotlinx.coroutines.runBlocking {
        val source = object : ExportSource {
            override suspend fun turns(conversationId: String) = listOf(
                ExportTurnRecord(
                    0, AttachmentLines.compose("看", listOf("/workspace/inbox/gone.txt" to "1 KB")),
                    "好了", "succeeded",
                ),
            )
            override suspend fun run(runId: String) = null
            override suspend fun resolve(request: AssetRequest): ExportAsset? = null
        }
        val document = ExportDocumentBuilder(source, labels, "test", "en") { 1L }.build(
            "c1", "t", ExportSelection("c1"), ExportContent(),
        )
        val root = createTempDir()
        try {
            MarkdownExportBackend(labels).render(document, ExportPresentation(), Reader, DirectoryExportSink(root)) {}
            val text = File(root, "document.md").readText()
            assertTrue(text.contains("> Warnings:"))
            assertTrue(text.contains("Missing: gone.txt"))
            assertFalse(text.contains("asset_missing"))
        } finally {
            root.deleteRecursively()
        }
    }

    private class Source(private val status: String = "succeeded", private val outcome: String = "好了") : ExportSource {
        override suspend fun turns(conversationId: String) = listOf(
            ExportTurnRecord(
                index = 0,
                userText = userText(),
                outcome = outcome,
                status = status,
                runId = "r1",
                atMs = 1_000L,
                steers = listOf("改成这个"),
                interruptedReplies = listOf(""),
                finishedReplies = listOf(""),
            ),
        )

        override suspend fun run(runId: String) = ExportRunRecord(
            model = "gpt-test",
            startedAtMs = 900L,
            steps = listOf(ToolStep(1, "look", "phone_read", "{\"x\":1}", "result", false, 12, "gpt-test")),
        )

        override suspend fun resolve(request: AssetRequest): ExportAsset? = when (request) {
            is AssetRequest.UserScreen -> ExportAsset("", AssetKind.UserScreen, "screen-s1.jpg", "image/jpeg", 10, request)
            is AssetRequest.Workspace -> ExportAsset("", AssetKind.File, "a.txt", "text/plain", 2_048, request)
            else -> null
        }

        private fun userText(): String {
            var text = "请看看 sk-1234567890abcdef"
            text = UserScreenMessage.compose("s1", text)
            text = QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, "old"), text)
            text = AttachedPage.compose(AttachedPage.Page("https://example.com/p", "T", "sel"), text)
            return AttachmentLines.compose(text, listOf("/workspace/inbox/a.txt" to "2 KB"))
        }
    }

    private object Reader : ExportAssetReader {
        override suspend fun open(asset: ExportAsset): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}

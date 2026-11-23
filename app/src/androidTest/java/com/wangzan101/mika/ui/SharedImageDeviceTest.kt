package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.chat.SharedDraft
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.chat.ui.draft
import com.wangzan101.mika.settings.ui.models.use

@RunWith(AndroidJUnit4::class)
class SharedImageDeviceTest {
    /** 走真实 ACTION_SEND、系统内容 URI 和生产 Activity,确认图片只进私有草稿,不自动启动任务。 */
    @Test
    fun imageShareBecomesPrivateDraftWithoutStartingTask() {
        val encoded = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(36, 102, 180)) }
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded))
        bitmap.recycle()
        val bytes = encoded.toByteArray()
        checkFileShare(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo.png", "image/png", bytes, "图片分享草稿验证", photo = true)
    }

    @Test
    fun pdfSharedViaClipDataKeepsItsNameAndCaption() {
        checkFileShare(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "会议记录.pdf", "application/pdf",
            "%PDF-1.7\n%%EOF".toByteArray(), "请总结会议记录", clipOnly = true)
    }

    @Test
    fun plainTextFileWithoutCaptionIsAnAttachment() {
        checkFileShare(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "清单.txt", "text/plain", "第一项".toByteArray(), "")
    }

    @Test
    fun multipleMixedFilesShareIntoOnePrivateDraftWithoutStartingTask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val app = target.applicationContext as MikaApp
        val resolver = target.contentResolver
        val encoded = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(12, 160, 90)) }
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded))
        bitmap.recycle()
        val photoBytes = encoded.toByteArray()
        val pdfBytes = "%PDF-1.7\n%%EOF".toByteArray()
        val photoName = "mika-${SystemClock.elapsedRealtimeNanos()}-multi.png"
        val pdfName = "mika-${SystemClock.elapsedRealtimeNanos()}-multi.pdf"
        val photoUri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, photoName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val pdfUri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, pdfName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            resolver.openOutputStream(photoUri, "w")!!.use { it.write(photoBytes) }
            resolver.update(photoUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            resolver.openOutputStream(pdfUri, "w")!!.use { it.write(pdfBytes) }
            resolver.update(pdfUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage(target.packageName)
                type = "*/*"
                putExtra(Intent.EXTRA_TEXT, "一起整理这两份材料")
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photoUri, pdfUri))
                clipData = ClipData.newRawUri(photoName, photoUri).apply { addItem(ClipData.Item(pdfUri)) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            assertEquals(MainActivity::class.java.name, intent.resolveActivity(target.packageManager)?.className)
            val runsBefore = app.taskState.runs.value.map { it.runId.value }.toSet()
            var received: SharedDraft? = null
            val scenario = ActivityScenario.launch<MainActivity>(intent)
            try {
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    received = app.sharedDrafts.readAll().firstOrNull { d ->
                        d.attachments.map { it.name } == listOf(photoName, pdfName)
                    }
                    if (received != null) break
                    SystemClock.sleep(100)
                }
                assertNotNull("The production activity did not save the multi-file draft", received)
                val draft = requireNotNull(received)
                assertEquals("一起整理这两份材料", draft.text)
                assertEquals(2, draft.attachments.size)
                assertTrue(draft.attachments[0].photo)
                assertEquals(false, draft.attachments[1].photo)
                assertArrayEquals(photoBytes, requireNotNull(app.sharedDrafts.attachmentFile(draft.attachments[0].fileName)).readBytes())
                assertArrayEquals(pdfBytes, requireNotNull(app.sharedDrafts.attachmentFile(draft.attachments[1].fileName)).readBytes())
                assertEquals(listOf(photoName, pdfName), app.chat.ui.value.attachments.map { it.name })
                assertEquals("Sharing multiple files must not start a task", runsBefore,
                    app.taskState.runs.value.map { it.runId.value }.toSet())
            } finally {
                received?.let { app.sharedDrafts.clear(it.conversationId) }
                runCatching { scenario.onActivity { it.finish() } }
            }
        } finally {
            resolver.delete(photoUri, null, null)
            resolver.delete(pdfUri, null, null)
        }
    }

    private fun checkFileShare(collection: Uri, name: String, mime: String, bytes: ByteArray, text: String,
        photo: Boolean = false, clipOnly: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val app = target.applicationContext as MikaApp
        val resolver = target.contentResolver
        val displayName = "mika-${SystemClock.elapsedRealtimeNanos()}-$name"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values))
        try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val intent = Intent(Intent.ACTION_SEND).apply {
                setPackage(target.packageName)
                type = mime
                if (text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, text)
                if (!clipOnly) putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(displayName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            assertEquals(MainActivity::class.java.name, intent.resolveActivity(target.packageManager)?.className)
            val runsBefore = app.taskState.runs.value.map { it.runId.value }.toSet()
            var received: SharedDraft? = null
            val scenario = ActivityScenario.launch<MainActivity>(intent)
            try {
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    received = app.sharedDrafts.readAll().firstOrNull { it.attachment?.name == displayName }
                    if (received != null) break
                    SystemClock.sleep(100)
                }
                assertNotNull("The production activity did not save the $mime draft", received)
                val draft = requireNotNull(received)
                assertEquals(text, draft.text)
                val attachment = requireNotNull(draft.attachment)
                assertEquals(photo, attachment.photo)
                assertEquals(bytes.size.toLong(), attachment.size)
                val privateCopy = requireNotNull(app.sharedDrafts.attachmentFile(attachment.fileName))
                assertArrayEquals(bytes, privateCopy.readBytes())
                val tray = app.chat.ui.value.attachments.single()
                assertEquals(photo, tray.photo)
                val trayUri = Uri.parse(tray.uri)
                resolver.query(trayUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(displayName, it.getString(0))
                }
                val draftDir = File(target.filesDir, "shared-drafts").canonicalFile
                assertTrue(privateCopy.canonicalFile.toPath().startsWith(draftDir.toPath()))
                assertEquals("Sharing a draft must not start a task", runsBefore,
                    app.taskState.runs.value.map { it.runId.value }.toSet())
            } finally {
                received?.let { app.sharedDrafts.clear(it.conversationId) }
                runCatching { scenario.onActivity { it.finish() } }
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    /** 界面已在后台存活时收到分享:空输入框直接填入当前会话,已有草稿时等待用户确认替换或取消。 */
    @Test
    fun shareWhileActivityAliveTargetsCurrentConversationAndPromptsWhenDraftExists() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val app = target.applicationContext as MikaApp
        val launchIntent = Intent(target, MainActivity::class.java)
        val scenario = ActivityScenario.launch<MainActivity>(launchIntent)
        try {
            val conversationId = app.conversations.newConversation()
            scenario.onActivity {
                app.chat.openConversation(conversationId)
                app.chat.input("")
            }
            instrumentation.waitForIdleSync()

            val firstText = "后台分享直接进当前会话"
            val firstShare = Intent(Intent.ACTION_SEND).apply {
                setClassName(target.packageName, MainActivity::class.java.name)
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, firstText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            target.startActivity(firstShare)
            val deadline1 = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline1 && app.chat.ui.value.input != firstText) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
            assertEquals(conversationId, app.chat.ui.value.conversationId)
            assertEquals(firstText, app.chat.ui.value.input)
            assertNull(app.chat.ui.value.pendingSharedDraft)

            val secondText = "后台分享需要确认替换"
            val secondShare = Intent(Intent.ACTION_SEND).apply {
                setClassName(target.packageName, MainActivity::class.java.name)
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, secondText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            target.startActivity(secondShare)
            val deadline2 = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline2 && app.chat.ui.value.pendingSharedDraft == null) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
            assertEquals(conversationId, app.chat.ui.value.conversationId)
            assertEquals(firstText, app.chat.ui.value.input)
            assertEquals(secondText, app.chat.ui.value.pendingSharedDraft?.text)

            val missingImage = Intent(Intent.ACTION_SEND).apply { type = "image/png" }
            scenario.onActivity { instrumentation.callActivityOnNewIntent(it, missingImage) }
            assertEquals(secondText, app.chat.ui.value.pendingSharedDraft?.text)
            assertEquals(secondText, app.sharedDrafts.readPending()?.text)

            scenario.onActivity { app.chat.confirmPendingSharedDraft() }
            instrumentation.waitForIdleSync()
            assertEquals(conversationId, app.chat.ui.value.conversationId)
            assertEquals(secondText, app.chat.ui.value.input)
            assertNull(app.chat.ui.value.pendingSharedDraft)
        } finally {
            app.chat.cancelPendingSharedDraft()
            app.chat.ui.value.conversationId?.let { app.sharedDrafts.clear(it) }
            runCatching { scenario.onActivity { it.finish() } }
        }
    }
}

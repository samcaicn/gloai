package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.chat.AttachFilesProvider
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 对话页的附件:「＋」的回调、相册页、输入框上方待发的那一排、发出去的消息怎么画。 */
@RunWith(AndroidJUnit4::class)
class ChatAttachmentsTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 假的图:每张都是一块 40×30 的空图,不读手机。 */
    private val images = AttachImages { _, _ -> ImageBitmap(40, 30) }

    private fun composer(
        attachments: List<PendingAttachment> = emptyList(),
        text: String = "",
        mode: ComposerMode = ComposerMode.Idle,
        rejected: List<AttachmentFailure> = emptyList(),
        onSend: () -> Unit = {},
        onPlus: (() -> Unit)? = null,
        attachBlocked: Boolean = false,
    ) = compose.setContent {
        MikaTheme {
            CompositionLocalProvider(LocalAttachImages provides images) {
                var t by remember { mutableStateOf(text) }
                Box(Modifier.fillMaxSize()) {
                    Composer(
                        Modifier, t, { t = it }, mode, hasHistory = false, onSend = onSend, onStop = {},
                        onPlus = onPlus,
                        attachBlocked = attachBlocked,
                        attachments = attachments,
                        rejected = rejected,
                    )
                }
            }
        }
    }

    @Test fun rejectedAttachmentsShowTheirIndividualReasons() {
        composer(rejected = listOf(
            AttachmentFailure("big.zip", AttachmentFailureReason.TOO_LARGE),
            AttachmentFailure("photo.jpg", AttachmentFailureReason.LOW_DEVICE_SPACE),
            AttachmentFailure("notes.txt", AttachmentFailureReason.IO_ERROR),
        ))
        compose.onNodeWithText("big.zip：超过 200 MiB 限制", substring = true).assertIsDisplayed()
        compose.onNodeWithText("photo.jpg：手机存储空间不足", substring = true).assertIsDisplayed()
        compose.onNodeWithText("notes.txt：无法读取或复制", substring = true).assertIsDisplayed()
    }

    @Test fun plusOpensTheSheetThroughCallback() {
        var opened = 0
        composer(onPlus = { opened++ })
        compose.onNodeWithContentDescription(AttachCopy.ADD).performClick()
        assertEquals(1, opened)
    }

    @Test fun waitingForAnAnswerCanStillOpenTheSheet() {
        var opened = 0
        composer(mode = ComposerMode.Answer, onPlus = { opened++ })
        compose.onNodeWithContentDescription(AttachCopy.ADD).assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test fun blockedAttachmentsKeepThePlusButOnlyExplain() {
        var opened = 0
        composer(onPlus = { opened++ }, attachBlocked = true)
        compose.onNodeWithContentDescription(AttachCopy.ADD).performClick()
        assertEquals(0, opened)
    }

    @Test fun noPlusWhenAttachmentsAreOff() {
        composer()
        compose.onAllNodesWithContentDescription(AttachCopy.ADD).assertCountEquals(0)
    }

    @Test fun trayShowsPhotosAndFileCardsAndAttachmentsAloneCanBeSent() {
        var sent = 0
        composer(
            listOf(
                PendingAttachment("content://m/1", "a.jpg", 1000),
                PendingAttachment("content://m/2", "b.png", 1000),
                PendingAttachment("content://f/1", "租房合同.pdf", 1200 * 1024),
            ),
            onSend = { sent++ },
        )
        compose.onAllNodesWithTag("tray-photo").assertCountEquals(2)
        compose.onAllNodesWithTag("tray-file").assertCountEquals(1)
        compose.onNodeWithText("租房合同.pdf").assertIsDisplayed()
        compose.onNodeWithText("1.2 MB").assertIsDisplayed()
        compose.onNodeWithContentDescription(AttachCopy.remove("a.jpg")).assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test fun sendIsOffWithNothingToSend() {
        composer()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
    }

    @Test fun removingFromTheTrayCallsBack() {
        val removed = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                CompositionLocalProvider(LocalAttachImages provides images) {
                    PendingTray(listOf(PendingAttachment("content://f/9", "a.pdf", 10)), { removed += it })
                }
            }
        }
        compose.onNodeWithContentDescription(AttachCopy.remove("a.pdf")).performClick()
        assertEquals(listOf("content://f/9"), removed)
    }

    @Test fun clickingLocalTrayItemsOpensThem() {
        val opened = mutableListOf<String>()
        val removed = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                CompositionLocalProvider(LocalAttachImages provides images) {
                    PendingTray(
                        items = listOf(
                            PendingAttachment("content://m/1", "a.jpg", 1000),
                            PendingAttachment("content://f/9", "a.pdf", 10),
                        ),
                        onRemove = { removed += it },
                        onOpen = { opened += it.uri },
                    )
                }
            }
        }
        compose.onNodeWithTag("tray-photo").assertHasClickAction().performClick()
        compose.onNodeWithTag("tray-file").assertHasClickAction().performClick()
        assertEquals(listOf("content://m/1", "content://f/9"), opened)
        assertEquals(emptyList<String>(), removed)
    }

    @Test fun localPendingAttachmentOpensInFileViewerAndMissingReturnsFalse() {
        val context = compose.activity
        assertFalse(runBlocking { AttachFiles.openPending(context, "content://nonexistent.invalid/missing.txt", "missing.txt") })
        val dir = File(context.cacheDir, "attach/viewing").apply { mkdirs() }
        val file = File(dir, "pending-preview.txt").apply { writeText("pending preview body") }
        try {
            val uri = FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), file).toString()
            compose.setContent {
                MikaTheme {
                    CompositionLocalProvider(LocalAttachImages provides images) {
                        PendingTray(listOf(PendingAttachment(uri, "pending-preview.txt", file.length())), onRemove = {})
                    }
                }
            }
            compose.onNodeWithTag("tray-file").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer.text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("pending preview body", substring = true).assertIsDisplayed()
            Espresso.pressBack()
        } finally {
            file.delete()
        }
    }

    @Test fun externalUriPendingAttachmentCopiesToViewingAndOpensInFileViewer() {
        val context = compose.activity
        val file = File(context.cacheDir, "external-pending.txt").apply { writeText("copied external body") }
        try {
            val uri = Uri.fromFile(file).toString()
            compose.setContent {
                MikaTheme {
                    CompositionLocalProvider(LocalAttachImages provides images) {
                        PendingTray(listOf(PendingAttachment(uri, "external-pending.txt", file.length())), onRemove = {})
                    }
                }
            }
            compose.onNodeWithTag("tray-file").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer.text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("copied external body", substring = true).assertIsDisplayed()
            Espresso.pressBack()
        } finally {
            file.delete()
        }
    }

    private fun photosPage(access: PhotoAccess, recent: List<PendingAttachment>?, selected: List<PendingAttachment> = emptyList(), onGrant: () -> Unit = {}) =
        compose.setContent {
            MikaTheme {
                CompositionLocalProvider(LocalAttachImages provides images) {
                    PlusPhotosPage(access, recent, selected, {}, onGrant, {}, {})
                }
            }
        }

    @Test fun photosPageWithoutPhotoAccessExplainsAndOffersToAllow() {
        var granted = 0
        photosPage(PhotoAccess.None, emptyList(), onGrant = { granted++ })
        compose.onNodeWithText(AttachCopy.NEED_ACCESS).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.GRANT).performClick()
        assertEquals(1, granted)
        compose.onAllNodesWithTag("recent-photo").assertCountEquals(0)
    }

    @Test fun photosPageListsRecentPhotosWithNumberedSelection() {
        val recent = (1..6).map { PendingAttachment("content://m/$it", "p$it.jpg", 1, photo = true) }
        photosPage(PhotoAccess.Full, recent, selected = listOf(recent[4], recent[1]))
        compose.onNodeWithText(AttachCopy.RECENT).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.ALL).assertIsDisplayed()
        compose.onAllNodesWithTag("recent-photo").assertCountEquals(6)
        compose.onAllNodes(hasTestTag("recent-photo") and isSelected()).assertCountEquals(2)
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
    }

    @Test fun partialAccessOffersToChangeTheSelection() {
        photosPage(PhotoAccess.Partial, listOf(PendingAttachment("content://m/1", "p.jpg", 1, photo = true)))
        compose.onNodeWithText(AttachCopy.RECENT_PARTIAL).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.CHANGE).assertIsDisplayed()
    }

    @Test fun plusSheetOmitsRedundantSubtitlesAndShortensBrowser() {
        // 避免「首次打开弹照片权限」打断断言
        RecentPhotos.markAsked(compose.activity)
        compose.setContent {
            MikaTheme {
                ChatPlusSheet(
                    selected = emptyList(),
                    onDismiss = {},
                    onPicked = {},
                    onToggle = { false },
                    onFull = {},
                    onDeviceFiles = null,
                    onPaste = {},
                    onTeach = {},
                    onChecklists = {},
                    onWorkflows = {},
                    onBrowser = {},
                    tasks = emptyList(),
                    onPickTask = {},
                    onPickSkill = {},
                )
            }
        }
        // 冗余副标题删掉
        compose.onAllNodesWithText("现在拍一张").assertCountEquals(0)
        compose.onAllNodesWithText("存一份待办清单").assertCountEquals(0)
        compose.onAllNodesWithText("跑一条已存的多步骤任务").assertCountEquals(0)
        // 保留的副标题还在
        compose.onNodeWithText("从相册选,可多选").assertExists()
        // 浏览器副标题精简成「内置浏览器」
        compose.onNodeWithText("内置浏览器").assertExists()
    }

    private fun bubble(text: String) = compose.setContent {
        MikaTheme {
            CompositionLocalProvider(LocalAttachImages provides images) {
                Box(Modifier.width(360.dp)) { UserBubble(text, "c-test") }
            }
        }
    }

    @Test fun sentMessageShowsPhotosFilesAndWordsButNoPaths() {
        bubble(
            "帮我看看这些\n附件:/workspace/inbox/a.jpg(1 KB)\n附件:/workspace/inbox/b.jpg(1 KB)\n" +
                "附件:/workspace/inbox/c.webp(1 KB)\n附件:/workspace/inbox/租房合同 (1).pdf(48 KB)",
        )
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(3)
        compose.onAllNodesWithTag("sent-file").assertCountEquals(1)
        compose.onNodeWithText("租房合同 (1).pdf").assertIsDisplayed()
        compose.onNodeWithText("48 KB").assertIsDisplayed()
        compose.onNodeWithText("帮我看看这些").assertIsDisplayed()
        compose.onAllNodesWithText("/workspace", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("附件:", substring = true).assertCountEquals(0)
    }

    @Test fun singlePhotoAloneHasNoWordsBubble() {
        bubble("附件:/workspace/inbox/a.jpg(1 KB)")
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(1)
        compose.onAllNodesWithText("附件", substring = true).assertCountEquals(0)
    }

    @Test fun plainMessageIsUnchanged() {
        bubble("明天早上 8 点提醒我带伞")
        compose.onNodeWithText("明天早上 8 点提醒我带伞").assertIsDisplayed()
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(0)
        compose.onAllNodesWithTag("sent-file").assertCountEquals(0)
    }
}

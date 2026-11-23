package com.wangzan101.mika.task.ui

import com.wangzan101.mika.core.run.record.PlaybackMark
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.record.RunStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskReportViewModelTest {
    @get:Rule val texts = ChineseTexts()
    @get:Rule val tmp = TemporaryFolder()
    private val store by lazy { RunStore(tmp.root, { null }, 30, { 1_760_000_000_000L }) { it } }

    @Test fun reportPreservesFullMarkdownAndOutcome() {
        val answer = "## 结果\n" + "完整回答".repeat(200) + "\n| 车次 | 票价 |\n| --- | --- |\n| G6042 | 302 |"
        val h = store.open("查询车票")
        h.event(RunEvent.TaskStarted(1, "查询车票", "m"))
        h.event(RunEvent.TaskEnded(2001, "succeeded", answer))
        h.event(RunEvent.OutcomeJudged(2002, "partial", "部分车次未查到"))
        h.close()
        val report = TaskReportViewModel(store, h.runId).load()!!
        assertEquals(answer, report.answer)
        assertEquals("只办成了一部分", report.header.outcome)
        assertTrue(report.playback.isEmpty())
        assertEquals(241, TaskReportViewModel.excerpt(answer).length)
        assertEquals("简短回答", TaskReportViewModel.excerpt("简短回答"))
    }

    @Test fun preservesBeforeAfterAndBatchActionsInTheSameStep() {
        val events = listOf(
            RunEvent.TaskPlayback(1, 4, "phone_tap", "a", 10, 11, PlaybackMark("tap", 900, 50)),
            RunEvent.TaskPlayback(2, 4, "phone_swipe", "b", 12, null, PlaybackMark("swipe", 500, 800, 500, 300), source = "batch"),
            RunEvent.Media(3, 11, 4, null),
        )
        val steps = TaskReportViewModel.playbackSteps(events)
        assertEquals(2, steps.size)
        assertEquals(listOf(PlaybackFrame(10, false), PlaybackFrame(11, true)), steps[0].frames)
        assertEquals(listOf(PlaybackFrame(12, false)), steps[1].frames)
        assertEquals("swipe", steps[1].mark!!.kind)
    }

    @Test fun legacyRecordsNeverInventMarkersOrDuplicateBeforeAfter() {
        val steps = TaskReportViewModel.playbackSteps(listOf(
            RunEvent.Media(1, 8, 2, "com.x"), RunEvent.Media(2, 8, 2, "com.x"), RunEvent.Media(3, 9, 2, "com.x"),
        ))
        assertEquals(2, steps.size)
        assertTrue(steps.all { it.legacy && it.mark == null && it.frames.size == 1 && it.frames.single().after == null })
    }

    @Test fun invalidOrFailedMarkersAreHiddenAndMissingRecordsAreExplicit() {
        val steps = TaskReportViewModel.playbackSteps(listOf(
            RunEvent.TaskPlayback(1, 1, "phone_tap", "a", 10, 11, PlaybackMark("tap", -1, 50)),
            RunEvent.TaskPlayback(2, 2, "phone_tap", "b", 12, 13, PlaybackMark("tap", 500, 50), succeeded = false),
            RunEvent.TaskPlayback(3, 3, "phone_tap", "c", null, 14, PlaybackMark("tap", 500, 50)),
        ))
        assertTrue(steps.all { it.mark == null })
        assertNull(TaskReportViewModel(store, "../../other").load())
        val h = store.open("失败测试")
        h.event(RunEvent.TaskEnded(2, "failed", "请求受限")); h.close()
        val r = TaskReportViewModel(store, h.runId).load()!!
        assertEquals("请求受限", r.answer)
        assertNotEquals("办成了", r.header.outcome)
        assertNull(TaskReportViewModel(store, h.runId).screen(12))
    }
    @Test fun launchWithoutBeforeFrameAndUnavailableFilesNeverCreateBlankPlaybackItems() {
        val h = store.open("有真实记录的任务")
        h.media(2, byteArrayOf(1)); h.media(4, byteArrayOf(2))
        h.event(RunEvent.TaskPlayback(1, 1, "phone_launch_app", "a", null, 2))
        h.event(RunEvent.TaskPlayback(2, 2, "phone_tap", "b", 3, 4, PlaybackMark("tap", 500, 500)))
        h.event(RunEvent.TaskEnded(3, "succeeded", "完成")); h.close()
        val r = TaskReportViewModel(store, h.runId).load()!!
        assertEquals(listOf(2, 4), r.frames.map { it.frame.screenshotId })
        assertEquals(1, r.missingFrames) // null 从未采集，不计入缺失文件
        assertTrue(r.frames.all { it.mark == null }) // 只剩后帧，不得把点击圈移到后帧
        assertEquals("结束时页面", r.pages.single().title)
    }

    @Test fun keyPagesRequireAnswerEvidenceAndExactCallAssociation() {
        val steps = listOf(
            PlaybackStep(1, "phone_clear_text", 1, 2, callId = "clear"),
            PlaybackStep(2, "phone_tap", 3, 4, callId = "read"),
            PlaybackStep(2, "phone_tap", 5, 6, callId = "other"),
        )
        val frames = steps.flatMapIndexed { i, s -> s.frames.map { ReplayFrame(i, it, null) } }
        val events = listOf(
            RunEvent.Observed(1, 1, "清空了输入框", id = 1),
            RunEvent.Activity(2, 1, "act", "phone_clear_text", obsIds = listOf(1), callId = "clear"),
            RunEvent.Observed(3, 2, "作者\n前海石公园迎来最佳citywalk时期\n无关正文", id = 2),
            RunEvent.Activity(4, 2, "act", "phone_tap", obsIds = listOf(2), callId = "read"),
            RunEvent.Observed(5, 2, "前海石公园迎来最佳citywalk时期", id = 3),
            RunEvent.Activity(6, 2, "act", "phone_tap", obsIds = listOf(3), callId = "read"),
        )
        val pages = TaskReportViewModel.keyPages(events, "推荐「前海石公园迎来最佳 citywalk 时期」这篇笔记。", steps, frames)
        assertEquals(listOf(ReportPage("前海石公园迎来最佳citywalk时期", 1, 4)), pages)
        assertEquals(listOf(0, 1), TaskReportViewModel.stages(steps, frames, pages).map { it.stepIndex })
        assertTrue(TaskReportViewModel.FRAME_DURATION_MS >= 2400)
    }

}

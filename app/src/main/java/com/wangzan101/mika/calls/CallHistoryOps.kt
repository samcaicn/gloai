package com.wangzan101.mika.calls

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.modelFor
import kotlinx.coroutines.CancellationException

// 电话运行时:通话记录与通话摘要
suspend fun CallRuntime.setHistoryHandled(id: String, handled: Boolean): CallActionResult = onMain {
    historyResult(records.setHandled(id, handled), if (handled) "已标为已处理" else "已标为待处理")
}

suspend fun CallRuntime.saveFollowUpDraft(id: String, text: String): CallActionResult = onMain {
    if (text.length > CallHistory.MAX_DRAFT_LENGTH) return@onMain fail(app.getString(R.string.calls_draft_save_failed))
    historyResult(records.saveFollowUpDraft(id, text), app.getString(R.string.calls_draft_saved))
}

suspend fun CallRuntime.deleteHistory(id: String): CallActionResult = onMain {
    historyResult(records.delete(id), "已删除通话记录")
}

suspend fun CallRuntime.clearHistory(): CallActionResult = onMain {
    historyResult(records.clear(), "已清空通话记录")
}

internal fun CallRuntime.historyResult(result: CallHistory.Result, message: String): CallActionResult = when (result) {
    CallHistory.Result.SAVED -> ok(message)
    CallHistory.Result.NOT_FOUND -> fail("这条通话记录已不存在")
    CallHistory.Result.WRITE_FAILED -> fail("无法保存通话记录变更，请重试")
}

suspend fun CallRuntime.retrySummary(id: String): CallActionResult {
    if (!settings.state.value.summaryEnabled) {
        return CallActionResult(false, app.getString(R.string.calls_summary_cloud_consent))
    }
    val record = records.state.value.firstOrNull { it.id == id }
        ?: return CallActionResult(false, "找不到这条通话记录")
    if (record.transcript.isEmpty()) return CallActionResult(false, "没有可用于总结的转写")
    return if (records.retryFailedSummary(id) { summarizeCall(id, it.transcript) }) CallActionResult(true, "摘要已更新")
    else CallActionResult(false, app.getString(R.string.calls_summary_failed))
}

internal suspend fun CallRuntime.summarizeCall(id: String, transcript: List<CallTranscriptLine>): Boolean {
    val direction = records.state.value.firstOrNull { it.id == id }?.direction ?: CallDirection.INCOMING
    val summary = try {
        if (!settings.state.value.summaryEnabled) null
        else app.providers.modelFor(ModelRole.Main)?.let {
            CallSummaryGenerator.generate(it, transcript, direction) { settings.state.value.summaryEnabled }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    return if (summary == null) {
        records.setSummary(id, null, SummaryStatus.FAILED)
        records.state.value.firstOrNull { it.id == id }?.let { notifications.updateCompletedSummary(id, it.number, it.direction, null, failed = true) }
        false
    } else {
        val saved = records.setSummary(id, summary, SummaryStatus.READY) == CallHistory.Result.SAVED
        if (saved) records.state.value.firstOrNull { it.id == id }?.let {
            notifications.updateCompletedSummary(id, it.number, it.direction, summary, failed = false)
        }
        saved
    }
}

package com.wangzan101.mika.task

import android.util.Log
import com.wangzan101.mika.cap.phone.shell.AppsAfterTask
import com.wangzan101.mika.core.screen.uistate.AfterTaskMarks
import com.wangzan101.mika.shell.IMikaShell

/**
 * 任务收尾时 Mika 屏上的 App 怎么办:把模型标下的去留交给内置助手([IMikaShell.finishDisplay]),
 * 由它按规则([AppsAfterTask])留下或关掉、再释放虚拟屏。要在还原静音之前调:正在出声的先关掉,留下的不会在还原时突然响。
 * 助手没做成(出错、不认得这块屏)时屏照旧在收尾时释放,上面的 App 随屏关掉。
 */
internal object AppsAfterTaskEnd {
    private const val TAG = "AppsAfterTask"

    /** 这个进程里最近一次任务结束时的结果(给诊断信息的一行,只有个数);还没有过为 null。 */
    @Volatile var lastLine: String? = null
        private set

    data class Outcome(val kept: List<String>, val closed: List<String>, val note: String?) {
        /** 运行记录里的一句:留下、关掉的键和原因。 */
        val detail: String
            get() = listOfNotNull(
                "kept ${kept.joinToString(",").ifEmpty { "-" }}",
                "closed ${closed.joinToString(",").ifEmpty { "-" }}",
                note,
            ).joinToString("; ")

        val diagnostics: String get() = AppsAfterTask.diagnosticsLine(kept.size, closed, note)
    }

    fun finish(shell: IMikaShell, displayId: Int, marks: AfterTaskMarks?): Outcome {
        val keep = marks?.keepKeys().orEmpty().toTypedArray()
        val close = marks?.closeKeys().orEmpty().toTypedArray()
        val result = try {
            shell.finishDisplay(displayId, keep, close)
        } catch (t: Throwable) {
            Log.w(TAG, "finishDisplay failed; apps close with the display", t)
            null
        }
        val outcome = if (result == null) Outcome(emptyList(), emptyList(), "closed with the display (helper did not sort them)")
        else Outcome(
            result.getStringArray(AppsAfterTask.KEY_KEPT)?.toList().orEmpty(),
            result.getStringArray(AppsAfterTask.KEY_CLOSED)?.toList().orEmpty(),
            result.getString(AppsAfterTask.KEY_NOTE),
        )
        lastLine = outcome.diagnostics
        return outcome
    }
}

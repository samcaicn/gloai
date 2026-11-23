package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** 对话页那一轮下面留页的那一行:还开着、关了(带原因或不带),和剩余时间的说法。 */
class KeptPageCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val page = KeptPage(1, "c1", "r1", "播放课程", "course.example.com", null, null, 0, 7_200_000)
    private fun ex(runId: String?, kept: String? = null, closed: String? = null) =
        Exchange("播放课程", emptyList(), ExchangeStatus.Done("好了", null), live = false, runId = runId, keptPage = kept, keptPageClosed = closed)

    @Test fun rowFollowsTheLivePageAndTheTurnRecord() {
        assertEquals(KeptRow.Live(page), KeptRow.of(ex("r1", kept = "course.example.com"), page))
        // 刚收尾、那一轮还没读回留页的记录时,照样按此刻留着的那一页显示
        assertEquals(KeptRow.Live(page), KeptRow.of(ex("r1"), page))
        assertEquals(KeptRow.Closed("expired"), KeptRow.of(ex("r1", kept = "course.example.com", closed = "expired"), null))
        // 别的一轮留的页不挂到这一轮下面
        assertEquals(KeptRow.Closed(null), KeptRow.of(ex("r0", kept = "a.example.com"), page))
        assertNull(KeptRow.of(ex("r2"), page))
        assertNull(KeptRow.of(ex(null), null))
    }
}

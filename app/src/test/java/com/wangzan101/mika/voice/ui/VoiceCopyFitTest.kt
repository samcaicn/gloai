package com.wangzan101.mika.voice.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 按着说话时字太多:去掉前面说完的整句,最新的字一直看得见。这里按字数量「放不放得下」,界面上按真实排版量行数。 */
class VoiceCopyFitTest {
    private fun fits(max: Int): (String, String) -> Boolean = { s, t -> (s + t).length <= max }

    @Test fun shortTextIsUntouched() {
        assertEquals("你好。" to "打开", VoiceCopy.fitHeard("你好。", "打开", fits(20)))
        assertEquals("" to "", VoiceCopy.fitHeard("", "", fits(0)))
    }

    @Test fun finishedSentencesAreDroppedFromTheFrontOneByOne() {
        val stable = "第一句话。第二句话！第三句话？"
        // 放得下后两句和还在说的
        assertEquals("…第二句话！第三句话？" to "第四", VoiceCopy.fitHeard(stable, "第四", fits(13)))
        // 只放得下最后一句
        assertEquals("…第三句话？" to "第四", VoiceCopy.fitHeard(stable, "第四", fits(9)))
        // 说完的都放不下:只留还在说的
        assertEquals("…" to "第四句话", VoiceCopy.fitHeard(stable, "第四句话", fits(6)))
    }

    // 还在说的那句自己就很长:留它的末尾
    @Test fun aLongCurrentSentenceKeepsItsEnd() {
        assertEquals("…" to "五六七八", VoiceCopy.fitHeard("说完了。", "一二三四五六七八", fits(5)))
        assertEquals("…" to "", VoiceCopy.fitHeard("说完了。", "一二三", fits(1)))
    }

    // 没有句号可断时(一整段还没切句)直接留末尾
    @Test fun noSentenceBreakFallsBackToTheEnd() {
        assertEquals("…" to "丁戊", VoiceCopy.fitHeard("甲乙丙，", "丁戊", fits(3)))
    }

    @Test fun latinSentencesBreakAfterPeriods() {
        assertEquals("…Second one." to " third", VoiceCopy.fitHeard("First one. Second one.", " third", fits(20)))
    }
}

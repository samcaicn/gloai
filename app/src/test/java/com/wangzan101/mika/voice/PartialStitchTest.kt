package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** 解码窗口往后挪时怎么把一份份中间结果接起来;带「真实」字样的样例是真实服务回应里出现过的形态。 */
class PartialStitchTest {
    private fun stitch(vararg parts: Triple<String, Long, Long>): PartialStitch =
        PartialStitch().also { s -> for ((text, start, duration) in parts) s.feed(text, start, duration) }

    private fun assertShown(carried: String, tail: String, s: PartialStitch) = assertEquals(carried to tail, s.carried to s.tail)

    @Test fun windowThatHasNotMovedReplacesTheWholeText() {
        val s = stitch(Triple("今天我们", 0, 1000), Triple("今天我们来聊一聊", 0, 2000))
        assertShown("", "今天我们来聊一聊", s)
    }

    // 新的一份开头几个字被改写了(真实:「它的更它的成功率」变成「他的成功率」):照样对得上,不重出字
    @Test fun rewrittenHeadStillAlignsWithoutRepeatingText() {
        val s = stitch(
            Triple("你刚刚讲到的手机，它的更它的成功率更低。我觉得今天", 0, 15000),
            Triple("他的成功率更低。我觉得今天能用到的", 5000, 20000),
        )
        assertShown("你刚刚讲到的手机，它的更它的成功率更低。我", "觉得今天能用到的", s)
    }

    // 新的一份的开头是窗口最前面、前文被截掉,解错了(真实:「其实是不同的思路，体验也不一样」变成「只是不同的思路体但也不一样」):
    // 这一截沿用已显示的,到两份一样的地方才换
    @Test fun headOfTheNewWindowKeepsTheTextAlreadyShown() {
        val s = stitch(
            Triple("最近在用的几个工具，它们其实是不同的思路，体验也不一样，比如这个功能", 0, 15000),
            Triple("只是不同的思路体但也不一样，比如这个功能它可以直接操作", 4000, 19000),
        )
        assertShown("最近在用的几个工具，它们其实是不同的思路，体验", "也不一样，比如这个功能它可以直接操作", s)
    }

    // 重叠太短、沿用完开头就没有可比的了:从窗口开头整份换成新的
    @Test fun shortOverlapSwitchesAtTheWindowStart() {
        val s = stitch(Triple("甲乙丙丁戊己庚辛", 0, 8000), Triple("丙丁戊己庚辛壬癸", 2000, 10000))
        assertShown("甲乙", "丙丁戊己庚辛壬癸", s)
    }

    // 新的一份在已显示的字里找不到(识别结果整个变了):按时间比例估窗口开头
    @Test fun unalignedTextFallsBackToTime() {
        assertShown("甲乙", "子丑寅卯辰巳", stitch(Triple("甲乙丙丁戊己庚辛壬癸", 0, 10000), Triple("子丑寅卯辰巳", 2000, 12000)))
        // 上一份没有时长可比:不留
        assertShown("", "子丑", stitch(Triple("甲乙", 0, 0), Triple("子丑", 1000, 1000)))
    }

    // 不在英文单词中间换,空格留在前半截
    @Test fun latinWordsAreNotSplit() {
        val s = stitch(
            Triple("比如说 computer use 这个功能可以直接操作电脑", 0, 15000),
            Triple("computer use 这个功能可以直接操作电脑界面，但是", 1500, 16500),
        )
        assertShown("比如说 computer ", "use 这个功能可以直接操作电脑界面，但是", s)
    }

    // 整句都是用空格分词的文字:在词和词之间换,不把词拆开,也不重出词(新的一份开头的大小写变了照样对得上)
    @Test fun spaceSeparatedTextSwitchesBetweenWords() {
        val s = stitch(
            Triple("I have been using a few tools lately because they take quite different approaches", 0, 15000),
            Triple("Using a few tools lately because they take quite different approaches and the experience", 1000, 16000),
        )
        assertShown("I have been using a few ", "tools lately because they take quite different approaches and the experience", s)
    }

    // 标点、空格、大小写不参与对位置
    @Test fun punctuationAndCaseAreIgnoredWhenAligning() {
        val s = stitch(
            Triple("今天，我们来聊一聊 API 的用法和它的限制还有价格", 0, 15000),
            Triple("我们来聊一聊api的用法，和它的限制还有价格以及", 1000, 16000),
        )
        assertShown("今天，我们来聊一聊 API ", "的用法，和它的限制还有价格以及", s)
    }

    @Test fun emptyPartialChangesNothingAndResetStartsOver() {
        val s = stitch(Triple("今天我们来聊一聊", 0, 15000), Triple("我们来聊一聊语音", 1000, 16000))
        s.feed("", 2000, 17000)
        assertShown("今天", "我们来聊一聊语音", s)
        s.reset()
        assertShown("", "", s)
        s.feed("第二段", 19000, 20000)
        assertShown("", "第二段", s)
    }
}

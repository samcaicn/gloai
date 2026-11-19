package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自建 Fun-ASR 实时服务的消息;样例取自真实服务的回应(说的是一句中文)。 */
class FunAsrProtocolTest {
    @Test fun openingAndClosingAreThePlainTextCommands() {
        val p = FunAsrProtocol()
        assertEquals(listOf("START"), p.opening())
        assertEquals(listOf("STOP"), p.closing())
        assertEquals(0L, p.packetGapMs)
    }

    // 指定语言时先发 LANGUAGE(服务端提示词用中文语言名),再发 START;自动时不发
    @Test fun languageIsSentBeforeStart() {
        assertEquals(listOf("LANGUAGE:中文", "START"), FunAsrProtocol(AsrLanguage.Chinese).opening())
        assertEquals(listOf("LANGUAGE:英文", "START"), FunAsrProtocol(AsrLanguage.English).opening())
        assertEquals(listOf("LANGUAGE:粤语", "START"), FunAsrProtocol(AsrLanguage.Cantonese).opening())
        assertEquals(listOf("START"), FunAsrProtocol(AsrLanguage.Auto).opening())
        // 服务端对 LANGUAGE 的确认排在 started 前面,不算开始
        assertEquals(LiveAsrUpdate.None, FunAsrProtocol(AsrLanguage.Chinese).read("""{"event": "language_set", "language": "中文"}"""))
    }

    @Test fun snapshotsReplaceTheWholeTextAndFinalWaitsForStopped() {
        val p = FunAsrProtocol()
        assertEquals(LiveAsrUpdate.Started, p.read("""{"event": "started"}"""))
        assertEquals(LiveAsrUpdate.Text("", ""), p.read("""{"sentences": [], "partial": "", "partial_start_ms": 0, "duration_ms": 500, "is_final": false}"""))
        assertEquals(LiveAsrUpdate.Text("", "你好，帮。"), p.read("""{"sentences": [], "partial": "你好，帮。", "partial_start_ms": 0, "duration_ms": 1000, "is_final": false}"""))
        assertEquals(
            LiveAsrUpdate.Text("先打开微信。", "给张三发消息"),
            p.read("""{"sentences": [{"text": "先打开微信。", "start": 0, "end": 1800, "spk": 0}], "partial": "给张三发消息", "partial_start_ms": 1800, "duration_ms": 3000, "is_final": false}"""),
        )
        val final = """{"sentences": [{"text": "先打开微信。", "start": 0, "end": 1800}, {"text": "给张三发一条消息。", "start": 1800, "end": 6867}], "partial": "", "partial_start_ms": 0, "duration_ms": 6867, "is_final": true}"""
        assertEquals(LiveAsrUpdate.Text("先打开微信。给张三发一条消息。", ""), p.read(final))
        assertEquals(LiveAsrUpdate.Ended("先打开微信。给张三发一条消息。"), p.read("""{"event": "stopped"}"""))
    }

    // 一段话说得长、还没切句时服务端只解最近 15 秒:窗口往后挪掉出去的字留在预览里,不被删掉;怎么接见 PartialStitchTest
    @Test fun textThatSlidesOutOfThePartialWindowIsKept() {
        val p = FunAsrProtocol()
        fun partial(text: String, start: Long, duration: Long) =
            p.read("""{"sentences": [], "partial": "$text", "partial_start_ms": $start, "duration_ms": $duration, "is_final": false}""")
        assertEquals(LiveAsrUpdate.Text("", "今天我们来聊一聊语音识别的问题，首先需要打开设置页面。"), partial("今天我们来聊一聊语音识别的问题，首先需要打开设置页面。", 0, 15000))
        // 窗口挪了:新的一份从「我们」开始;重叠那截的开头沿用已显示的,到两份一样的地方才换成新的
        assertEquals(
            LiveAsrUpdate.Text("今天我们来聊一聊语音", "识别的问题，首先需要打开设置页面，然后"),
            partial("我们来聊一聊语音识别的问题，首先需要打开设置页面，然后", 1000, 16000),
        )
        assertEquals(
            LiveAsrUpdate.Text("今天我们来聊一聊语音识别的问题，", "首先需要打开设置页面，然后找到"),
            partial("聊语音识别的问题。首先需要打开设置页面，然后找到", 2000, 17000),
        )
        // 窗口没挪、同一截又解了一遍:留住的不动,后面跟着新的
        assertEquals(
            LiveAsrUpdate.Text("今天我们来聊一聊语音识别的问题，", "首先需要打开设置页面，然后找到模型"),
            partial("聊语音识别的问题，首先需要打开设置页面，然后找到模型", 2000, 17500),
        )
        // 这段整段放进 sentences:留下的字作废,只用服务端的句子;下一段从头来,不和上一段对齐
        val whole = "今天我们来聊一聊语音识别的问题，首先需要打开设置页面，然后找到模型。"
        assertEquals(
            LiveAsrUpdate.Text(whole, ""),
            p.read("""{"sentences": [{"text": "$whole", "start": 0, "end": 17800}], "partial": "", "partial_start_ms": 18000, "duration_ms": 18500, "is_final": false}"""),
        )
        assertEquals(
            LiveAsrUpdate.Text(whole, "第二段"),
            p.read("""{"sentences": [{"text": "$whole", "start": 0, "end": 17800}], "partial": "第二段", "partial_start_ms": 19000, "duration_ms": 20000, "is_final": false}"""),
        )
    }

    // 留着掉出去的字时松开:终结果只用服务端整段重解的句子,不拼留下的那截
    @Test fun finalIgnoresCarriedText() {
        val p = FunAsrProtocol()
        p.read("""{"sentences": [], "partial": "今天我们来聊一聊", "partial_start_ms": 0, "duration_ms": 15000, "is_final": false}""")
        assertEquals(
            LiveAsrUpdate.Text("今天", "我们来聊一聊语音"),
            p.read("""{"sentences": [], "partial": "我们来聊一聊语音", "partial_start_ms": 1000, "duration_ms": 16000, "is_final": false}"""),
        )
        assertEquals(
            LiveAsrUpdate.Text("今天我们来聊一聊语音识别。", ""),
            p.read("""{"sentences": [{"text": "今天我们来聊一聊语音识别。", "start": 0, "end": 16500}], "partial": "", "partial_start_ms": 0, "duration_ms": 16500, "is_final": true}"""),
        )
        assertEquals(LiveAsrUpdate.Ended("今天我们来聊一聊语音识别。"), p.read("""{"event": "stopped"}"""))
    }

    // 窗口挪了但这一份是空的(这一刻没在说话):留住的字还在,后面不跟字
    @Test fun emptyPartialKeepsCarriedText() {
        val p = FunAsrProtocol()
        p.read("""{"sentences": [], "partial": "今天我们来聊一聊", "partial_start_ms": 0, "duration_ms": 15000, "is_final": false}""")
        p.read("""{"sentences": [], "partial": "我们来聊一聊语音", "partial_start_ms": 1000, "duration_ms": 16000, "is_final": false}""")
        assertEquals(
            LiveAsrUpdate.Text("今天", ""),
            p.read("""{"sentences": [], "partial": "", "partial_start_ms": 16500, "duration_ms": 16500, "is_final": false}"""),
        )
    }

    // 真实服务对一段 40 多秒不停顿的话的全部回应(手机录的音):服务端定下这段之前,每一份预览去掉标点后都是终结果的开头——
    // 没有接缝处重出来的字,也没有窗口开头解错的字;定下之后和终结果都只用服务端的句子
    @Test fun recordedLongUtterancePreviewNeverStraysFromTheFinalText() {
        val lines = FunAsrProtocolTest::class.java.getResource("/voice/funasr-long-utterance.jsonl")!!.readText().trim().lines()
        fun letters(text: String) = text.filter(Char::isLetterOrDigit).lowercase()
        val final = (FunAsrProtocol().read(lines.last()) as LiveAsrUpdate.Text).stable
        val p = FunAsrProtocol()
        var stitched = 0
        for (line in lines.dropLast(1)) {
            val locked = "\"sentences\": []" !in line
            val text = p.read(line) as LiveAsrUpdate.Text
            if (locked) { assertTrue(text.stable, final.startsWith(text.stable.take(20))); continue }
            if (text.stable.isEmpty()) continue
            stitched++
            assertTrue("${text.stable}|${text.tail}", letters(final).startsWith(letters(text.stable + text.tail)))
        }
        assertEquals(18, stitched)
        assertEquals(LiveAsrUpdate.Text(final, ""), p.read(lines.last()))
        assertEquals(LiveAsrUpdate.Ended(final), p.read("""{"event": "stopped"}"""))
    }

    // 设置页「部署说明」打开的是仓库里这份文档:挪了文档要一起改
    @Test fun deployGuidePointsAtADocInThisRepo() {
        val path = FunAsrProtocol.DEPLOY_GUIDE.substringAfter("/blob/main/", "")
        assertTrue(path, path.isNotEmpty() && java.io.File("..", path).isFile)
    }

    // 没送过音频(或太短)时服务端不给终结果,直接回 stopped:当没听清
    @Test fun stoppedWithoutFinalIsEmpty() {
        assertEquals(LiveAsrUpdate.Ended(""), FunAsrProtocol().read("""{"event": "stopped"}"""))
    }

    @Test fun acknowledgementsDoNotChangeText() {
        val p = FunAsrProtocol()
        assertEquals(LiveAsrUpdate.None, p.read("""{"event": "hotwords_set", "hotwords": ["Mika"]}"""))
        assertEquals(LiveAsrUpdate.None, p.read("""{"event": "language_set", "language": "中文"}"""))
    }

    @Test fun latinSentencesAreJoinedWithASpace() {
        val p = FunAsrProtocol()
        val update = p.read("""{"sentences": [{"text": "Open WeChat"}, {"text": "and send a message"}], "partial": "", "is_final": true}""")
        assertEquals(LiveAsrUpdate.Text("Open WeChat and send a message", ""), update)
    }

    @Test fun addressesAreNormalized() {
        assertEquals("ws://192.168.1.10:10095", FunAsrProtocol.normalize("192.168.1.10"))
        assertEquals("ws://192.168.1.10:10095", FunAsrProtocol.normalize(" 192.168.1.10:10095 "))
        assertEquals("ws://asr.lan", FunAsrProtocol.normalize("ws://asr.lan/"))
        assertEquals("ws://asr.lan:8080/asr", FunAsrProtocol.normalize("http://asr.lan:8080/asr/"))
        assertEquals("wss://asr.example.com/ws?room=1", FunAsrProtocol.normalize("https://asr.example.com/ws?room=1"))
        assertEquals("ws://[fd00::5]:10095", FunAsrProtocol.normalize("ws://[fd00::5]:10095"))
        // 规范过的地址再规范一次不变
        for (url in listOf("ws://192.168.1.10:10095", "wss://asr.example.com/ws?room=1", "ws://[fd00::5]:10095")) {
            assertEquals(url, FunAsrProtocol.normalize(url))
        }
    }

    @Test fun badAddressesAreRejected() {
        for (bad in listOf("", "  ", "ftp://host", "ws://", "ws://user:pass@host:10095", "two words", "ws://host:port")) {
            assertNull(bad, FunAsrProtocol.normalize(bad))
        }
    }

    @Test fun hostOfDropsPathAndQuery() {
        assertEquals("192.168.1.10:10095", FunAsrProtocol.hostOf("ws://192.168.1.10:10095"))
        assertEquals("asr.example.com", FunAsrProtocol.hostOf("wss://asr.example.com/ws?token=x"))
    }
}

package com.wangzan101.mika.voice.vendors

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.voice.Pcm16
import com.wangzan101.mika.voice.vendors.VendorHttp.path
import com.wangzan101.mika.voice.vendors.VendorHttp.string
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class VendorProtocolsTest {
    private fun obj(text: String) = VendorHttp.json.parseToJsonElement(text).jsonObject

    @Test fun `wav round trip keeps samples and rate`() {
        val samples = ShortArray(100) { (it * 300 - 15000).toShort() }
        val decoded = Pcm16.parseWav(Pcm16.wav(Pcm16.toBytes(samples), 24_000))!!
        assertEquals(24_000, decoded.sampleRate)
        assertArrayEquals(samples, decoded.samples)
    }

    @Test fun `resample halves length from 16k to 8k`() {
        assertEquals(800, Pcm16.resample(ShortArray(1600), 16_000, 8_000).size)
    }

    @Test fun `concatenated json objects split even with braces in strings`() {
        val list = VendorHttp.jsonObjects("""{"a":"}{"}
{"b":{"c":1}}{"d":2}""")
        assertEquals(3, list.size)
        assertEquals("}{", list[0].path("a").string())
    }

    @Test fun `volc stream joins audio and stops on an error code`() {
        val a = Base64.getEncoder().encodeToString(byteArrayOf(1, 2))
        val b = Base64.getEncoder().encodeToString(byteArrayOf(3))
        val body = """{"code":0,"data":"$a"}
{"code":0,"data":"$b"}
{"code":20000000,"message":"ok","data":null}"""
        assertArrayEquals(byteArrayOf(1, 2, 3), VendorTts.parseVolc(body))
        assertThrows(ModelError.Server::class.java) { VendorTts.parseVolc("""{"code":40000001,"message":"bad"}""") }
    }

    @Test fun `volc body carries speaker, pcm 24k and speech rate`() {
        val body = obj(VendorTts.volcBody("你好", "zh_female_vv_uranus_bigtts", 1.5f))
        assertEquals("zh_female_vv_uranus_bigtts", body.path("req_params", "speaker").string())
        assertEquals("pcm", body.path("req_params", "audio_params", "format").string())
        assertEquals("50", body.path("req_params", "audio_params", "speech_rate").toString())
    }

    @Test fun `minimax maps status codes and decodes hex pcm`() {
        val ok = VendorTts.parseMiniMax(obj("""{"data":{"audio":"0102"},"extra_info":{"audio_sample_rate":24000},"base_resp":{"status_code":0}}"""))
        assertArrayEquals(byteArrayOf(1, 2), ok.pcm)
        assertThrows(ModelError.Auth::class.java) {
            VendorTts.parseMiniMax(obj("""{"base_resp":{"status_code":1004,"status_msg":"auth"}}"""))
        }
    }

    @Test fun `tencent errors become categorized`() {
        assertThrows(ModelError.Auth::class.java) {
            VendorStt.parseTencent(obj("""{"Response":{"Error":{"Code":"AuthFailure.SignatureFailure","Message":"x"}}}"""))
        }
        assertEquals("hi", VendorStt.parseTencent(obj("""{"Response":{"Result":"hi"}}""")).path("Result").string())
    }

    @Test fun `tencent signing is stable and scoped by date and service`() {
        val a = TencentAuth.headers("asr", "SentenceRecognition", "2019-06-14", "{}", "id", "key", epochSeconds = 1_700_000_000)
        val b = TencentAuth.headers("asr", "SentenceRecognition", "2019-06-14", "{}", "id", "key", epochSeconds = 1_700_000_000)
        assertEquals(a, b)
        assertTrue(a["Authorization"]!!.startsWith("TC3-HMAC-SHA256 Credential=id/2023-11-14/asr/tc3_request"))
        assertEquals("asr.tencentcloudapi.com", a["Host"])
    }

    @Test fun `xfyun url carries signed authorization`() {
        val url = XfyunAuth.url("iat-api.xfyun.cn", "/v2/iat", "k", "s", 0)
        assertTrue(url.startsWith("wss://iat-api.xfyun.cn/v2/iat?authorization="))
        assertTrue(url.contains("date=Thu%2C%2001%20Jan%201970"))
    }

    @Test fun `xfyun words are joined and first frame has business params`() {
        val words = VendorStt.xfyunWords(obj("""{"data":{"result":{"ws":[{"cw":[{"w":"你"}]},{"cw":[{"w":"好"}]}]},"status":2}}"""))
        assertEquals("你好", words)
        val first = obj(VendorStt.xfyunAudioFrame("app", "zh", 0, ByteArray(2)))
        assertEquals("app", first.path("common", "app_id").string())
        val later = obj(VendorStt.xfyunAudioFrame("app", "zh", 1, ByteArray(2)))
        assertEquals(null, later["common"])
    }

    @Test fun `azure and deepgram parsing`() {
        assertEquals("hello", VendorStt.parseAzure(obj("""{"RecognitionStatus":"Success","DisplayText":"hello"}""")))
        assertEquals("", VendorStt.parseAzure(obj("""{"RecognitionStatus":"NoMatch"}""")))
        assertEquals("hi", VendorStt.parseDeepgram(obj("""{"results":{"channels":[{"alternatives":[{"transcript":"hi"}]}]}}""")))
        assertEquals("zh-CN", VendorStt.azureLocale(null))
        assertEquals("en-US", VendorStt.azureLocale("en"))
    }

    @Test fun `azure ssml escapes text and takes locale from voice`() {
        val ssml = VendorTts.azureSsml("a<b", "en-US-JennyNeural", 1f)
        assertTrue(ssml.contains("xml:lang='en-US'"))
        assertTrue(ssml.contains("a&lt;b"))
    }

    @Test fun `long text is cut at sentence marks within the limit`() {
        val text = "第一句话说完了。第二句话也说完了。第三句。"
        val pieces = VendorTts.pieces(text, 10)
        assertTrue(pieces.all { it.length <= 10 })
        assertEquals(text, pieces.joinToString(""))
    }

    @Test fun `vendor model joins pieces into one wav at the first rate`() = runBlocking {
        val calls = mutableListOf<String>()
        val model = VendorTtsModel("m", TtsCapabilities(), "v", 4) { text, voice, _ ->
            calls += "$voice:$text"
            PcmAudio(ByteArray(text.length * 2), 16_000)
        }
        val out = model.synthesize(TtsRequest("ab。cd。"))
        assertEquals(listOf("v:ab。", "v:cd。"), calls)
        val decoded = Pcm16.parseWav(out.bytes)!!
        assertEquals(16_000, decoded.sampleRate)
        assertEquals(6, decoded.samples.size)
    }
}

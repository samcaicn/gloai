package com.wangzan101.mika.voice.vendors

import com.wangzan101.mika.voice.model.VoiceCapability
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VendorModelListsTest {
    private fun j(text: String) = Json.parseToJsonElement(text)
    private val STT = setOf(VoiceCapability.STT)
    private val TTS = setOf(VoiceCapability.TTS)

    @Test fun `openai list keeps every model and names only the speech ones`() {
        val models = VendorModelLists.openAiShaped(j("""{"data":[{"id":"whisper-1"},{"id":"gpt-4o-mini-tts"},{"id":"gpt-4o"},
            {"id":"gpt-realtime"},{"id":"gpt-4o-transcribe"}]}"""), VendorModelLists::openAiCaps).associate { it.id to it.caps }
        assertEquals(5, models.size)
        assertEquals(STT, models["whisper-1"])
        assertEquals(STT, models["gpt-4o-transcribe"])
        assertEquals(TTS, models["gpt-4o-mini-tts"])
        assertEquals(setOf(VoiceCapability.REALTIME), models["gpt-realtime"])
        assertEquals(emptySet<VoiceCapability>(), models["gpt-4o"])
    }

    @Test fun `groq names whisper and orpheus`() {
        assertEquals(STT, VendorModelLists.groqCaps("whisper-large-v3-turbo"))
        assertEquals(TTS, VendorModelLists.groqCaps("canopylabs/orpheus-v1-english"))
        assertEquals(emptySet<VoiceCapability>(), VendorModelLists.groqCaps("llama-3.3-70b-versatile"))
    }

    @Test fun `gemini drops what cannot generate content and splits speak from listen`() {
        val models = VendorModelLists.gemini(j("""{"models":[
            {"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-3.8-flash-tts","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
            {"name":"models/gemma-3-27b-it","supportedGenerationMethods":["generateContent"]}]}""")).associateBy { it.id }
        assertEquals(setOf("gemini-2.5-flash", "gemini-3.8-flash-tts", "gemma-3-27b-it"), models.keys)
        assertEquals("Gemini 2.5 Flash", models["gemini-2.5-flash"]?.name)
        assertEquals(STT, models["gemini-2.5-flash"]?.caps)
        assertEquals(TTS, models["gemini-3.8-flash-tts"]?.caps)
        assertEquals(emptySet<VoiceCapability>(), models["gemma-3-27b-it"]?.caps)
        assertEquals(listOf("Kore", "Puck"), VendorModelLists.geminiVoices(j("""{"voices":[{"name":"voices/Kore"},{"voiceName":"Puck"}]}""")))
    }

    @Test fun `siliconflow uses its own audio sub type`() {
        val models = VendorModelLists.siliconFlow(
            j("""{"data":[{"id":"FunAudioLLM/SenseVoiceSmall"},{"id":"FunAudioLLM/CosyVoice2-0.5B"},{"id":"IndexTeam/IndexTTS-2"}]}"""),
            j("""{"data":[{"id":"FunAudioLLM/SenseVoiceSmall"}]}"""),
        ).associate { it.id to it.caps }
        assertEquals(STT, models["FunAudioLLM/SenseVoiceSmall"])
        assertEquals(TTS, models["IndexTeam/IndexTTS-2"])
        val custom = VendorModelLists.siliconFlowVoices(j("""{"result":[{"model":"IndexTeam/IndexTTS-2","customName":"me","uri":"speech:me:1"}]}"""))
        val (voices, names) = VendorModelLists.siliconFlowModelVoices("IndexTeam/IndexTTS-2", custom["IndexTeam/IndexTTS-2"].orEmpty())
        assertEquals("speech:me:1", voices.first())
        assertEquals("IndexTeam/IndexTTS-2:alex", voices[1])
        assertEquals("me", names["speech:me:1"])
    }

    @Test fun `elevenlabs keeps models that can speak and reads voice names`() {
        val models = VendorModelLists.elevenLabsModels(j("""[{"model_id":"eleven_v3","name":"Eleven v3","can_do_text_to_speech":true},
            {"model_id":"eleven_sts","can_do_text_to_speech":false},{"model_id":"eleven_turbo_v2_5"}]"""))
        assertEquals(listOf("eleven_v3", "eleven_turbo_v2_5"), models.map { it.id })
        assertEquals(listOf("abc" to "Rachel"), VendorModelLists.elevenLabsVoices(j("""{"voices":[{"voice_id":"abc","name":"Rachel"}],"has_more":false}""")))
    }

    @Test fun `deepgram groups speak voices under their model family`() {
        val models = VendorModelLists.deepgram(j("""{"stt":[{"canonical_name":"nova-3","batch":true},{"canonical_name":"nova-3","batch":true},
            {"canonical_name":"flux","batch":false}],
            "tts":[{"canonical_name":"aura-2-thalia-en","architecture":"aura-2","name":"thalia","languages":["en"]},
            {"canonical_name":"aura-2-celeste-es","architecture":"aura-2","name":"celeste","languages":["es"]},
            {"canonical_name":"aura-asteria-en","name":"asteria"}]}""")).associateBy { it.id }
        assertEquals(setOf("nova-3", "aura-2", "aura"), models.keys)
        assertEquals(listOf("aura-2-thalia-en", "aura-2-celeste-es"), models["aura-2"]?.voices)
        assertEquals("Thalia · en", models["aura-2"]?.voiceNames?.get("aura-2-thalia-en"))
        assertEquals(listOf("aura-asteria-en"), models["aura"]?.voices)
    }

    @Test fun `minimax voices span system cloned and generated`() {
        val voices = VendorModelLists.miniMaxVoices(j("""{"system_voice":[{"voice_id":"male-qn-qingse","voice_name":"青涩青年"}],
            "voice_cloning":[{"voice_id":"my-clone"}],"voice_generation":null}"""))
        assertEquals(listOf("male-qn-qingse" to "青涩青年", "my-clone" to "my-clone"), voices)
        assertEquals("https://api.minimax.io", VendorModelLists.hostOf("https://api.minimax.io/v1/t2a_v2"))
        assertEquals(TTS, VendorModelLists.miniMaxCaps("speech-2.8-hd"))
    }

    @Test fun `bailian reads stated capabilities and drops non speech models`() {
        val models = VendorModelLists.bailian(j("""{"output":{"models":[
            {"model":"fun-asr-realtime","capabilities":["Realtime-ASR"]},
            {"model":"qwen3-tts-flash","capabilities":["TTS"]},
            {"model":"qwen-max","capabilities":["Text-Generation"]},
            {"model":"mystery"}]}}""")).associate { it.id to it.caps }
        assertEquals(mapOf("fun-asr-realtime" to STT, "qwen3-tts-flash" to TTS, "mystery" to emptySet()), models)
    }

    @Test fun `azure voices put the phone language first`() {
        val voices = VendorModelLists.azureVoices(j("""[{"ShortName":"en-US-JennyNeural","LocalName":"Jenny","Locale":"en-US"},
            {"ShortName":"zh-CN-XiaoxiaoNeural","LocalName":"晓晓","Locale":"zh-CN"}]"""), Locale.SIMPLIFIED_CHINESE)
        assertEquals("zh-CN-XiaoxiaoNeural", voices.first().first)
        assertTrue(voices.first().second.startsWith("晓晓"))
    }
}

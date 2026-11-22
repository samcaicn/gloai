package com.wangzan101.mika.voice

import android.content.Context
import android.content.ContextWrapper
import android.speech.tts.TextToSpeech
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.TtsBinding
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class SystemTtsModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ContextWrapper(null)

    private class FakeEngine : SystemTtsEngine {
        var initStatus = TextToSpeech.SUCCESS
        var asyncInit = false
        var initCallback: ((Int) -> Unit)? = null
        var languageResult = TextToSpeech.LANG_AVAILABLE
        var language: Locale? = null
        var rate: Float? = null
        var synthesisResult = TextToSpeech.SUCCESS
        var outputBytes: ByteArray = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        var fireErrorOnSynthesize = false
        var neverComplete = false
        var synthesizedText: String? = null
        var synthesizedFile: File? = null
        var doneCallback: ((String) -> Unit)? = null
        var errorCallback: ((String) -> Unit)? = null
        var stopped = false
        var shutdown = false

        override fun init(onInit: (Int) -> Unit) {
            initCallback = onInit
            if (!asyncInit) onInit(initStatus)
        }

        override fun setLanguage(locale: Locale): Int {
            language = locale
            return languageResult
        }

        override fun setSpeechRate(rate: Float): Int {
            this.rate = rate
            return TextToSpeech.SUCCESS
        }

        override fun setOnUtteranceProgressListener(onDone: (String) -> Unit, onError: (String) -> Unit) {
            doneCallback = onDone
            errorCallback = onError
        }

        override fun synthesizeToFile(text: String, file: File, utteranceId: String): Int {
            synthesizedText = text
            synthesizedFile = file
            if (synthesisResult != TextToSpeech.SUCCESS) return synthesisResult
            if (fireErrorOnSynthesize) {
                errorCallback?.invoke(utteranceId)
                return TextToSpeech.SUCCESS
            }
            file.parentFile?.mkdirs()
            file.writeBytes(outputBytes)
            if (!neverComplete) doneCallback?.invoke(utteranceId)
            return TextToSpeech.SUCCESS
        }

        override fun stop() {
            stopped = true
        }

        override fun shutdown() {
            shutdown = true
        }
    }

    private fun model(
        engine: FakeEngine,
        language: () -> AppLanguage = { AppLanguage.English },
    ) = SystemTtsModel(
        context = context,
        engineFactory = { engine },
        resolvedLanguage = language,
        outputDir = { tmp.root },
    )

    @Test
    fun `synthesis produces a complete wav and shuts the engine down`() = runTest {
        val engine = FakeEngine()
        val bytes = ByteArray(44).also {
            "RIFF".toByteArray().copyInto(it, 0)
            "WAVE".toByteArray().copyInto(it, 8)
        }
        engine.outputBytes = bytes
        val tts = model(engine)

        val response = tts.synthesize(TtsRequest("hello", speed = 1.25f))

        assertEquals("audio/wav", response.mime)
        assertArrayEquals(bytes, response.bytes)
        assertEquals("hello", engine.synthesizedText)
        assertEquals(1.25f, engine.rate)
        assertEquals(Locale.forLanguageTag("en-US"), engine.language)
        assertTrue(engine.stopped)
        assertTrue(engine.shutdown)
        assertFalse(engine.synthesizedFile?.exists() ?: true)
    }

    @Test
    fun `synthesis waits for asynchronous initialization`() = runTest {
        val engine = FakeEngine().apply { asyncInit = true }
        val tts = model(engine)
        var completed = false
        var response: TtsResponse? = null
        val job = launch {
            response = tts.synthesize(TtsRequest("hello"))
            completed = true
        }

        runCurrent()
        assertFalse(completed)
        assertNull(engine.synthesizedText)

        engine.initCallback?.invoke(TextToSpeech.SUCCESS)
        runCurrent()

        assertTrue(completed)
        assertEquals("audio/wav", response?.mime)
        assertTrue(response?.bytes?.isNotEmpty() ?: false)
        assertTrue(engine.shutdown)
        job.join()
    }

    @Test
    fun `missing language data is an explicit system-unavailable failure`() = runTest {
        val engine = FakeEngine().apply { languageResult = TextToSpeech.LANG_MISSING_DATA }
        val tts = model(engine)

        val error = runCatching { tts.synthesize(TtsRequest("hello")) }.exceptionOrNull()

        assertTrue(error is VoiceModelError)
        assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, (error as VoiceModelError).category)
        assertTrue(engine.shutdown)
    }

    @Test
    fun `unsupported language is an explicit system-unavailable failure`() = runTest {
        val engine = FakeEngine().apply { languageResult = TextToSpeech.LANG_NOT_SUPPORTED }
        val tts = model(engine)

        val error = runCatching { tts.synthesize(TtsRequest("hello")) }.exceptionOrNull()

        assertTrue(error is VoiceModelError)
        assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, (error as VoiceModelError).category)
        assertTrue(engine.shutdown)
    }

    @Test
    fun `synthesis error releases the engine`() = runTest {
        val engine = FakeEngine().apply { synthesisResult = TextToSpeech.ERROR }
        val tts = model(engine)

        val error = runCatching { tts.synthesize(TtsRequest("hello")) }.exceptionOrNull()

        assertTrue(error is VoiceModelError)
        assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, (error as VoiceModelError).category)
        assertTrue(engine.stopped)
        assertTrue(engine.shutdown)
    }

    @Test
    fun `utterance error callback surfaces a failure and releases the engine`() = runTest {
        val engine = FakeEngine().apply { fireErrorOnSynthesize = true }
        val tts = model(engine)

        val error = runCatching { tts.synthesize(TtsRequest("hello")) }.exceptionOrNull()

        assertTrue(error is VoiceModelError)
        assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, (error as VoiceModelError).category)
        assertTrue(engine.shutdown)
    }

    @Test
    fun `empty synthesis output is rejected`() = runTest {
        val engine = FakeEngine().apply { outputBytes = byteArrayOf() }
        val tts = model(engine)

        val error = runCatching { tts.synthesize(TtsRequest("hello")) }.exceptionOrNull()

        assertTrue(error is VoiceModelError)
        assertEquals(VoiceErrorCategory.PROTOCOL_ERROR, (error as VoiceModelError).category)
        assertTrue(engine.shutdown)
    }

    @Test
    fun `cancellation stops and shuts the engine down and deletes the temp wav`() = runTest {
        val engine = FakeEngine().apply { neverComplete = true }
        val tts = model(engine)
        val job = launch { tts.synthesize(TtsRequest("hello")) }
        runCurrent()

        job.cancel()
        runCurrent()

        assertTrue(engine.stopped)
        assertTrue(engine.shutdown)
        assertFalse(engine.synthesizedFile?.exists() ?: true)
    }

    @Test
    fun `new install defaults to the built-in system TTS`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = false)
        val controller = voiceTestEnvironment(catalog).tts

        assertEquals(TtsSelectionController.SYSTEM_TTS_REF, controller.current())
        assertTrue(catalog.state.value.models.any { it.id == "system-tts" })
    }

    @Test
    fun `an existing migrated cloud selection is never replaced by the default`() {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertModel(VoiceModel("provider:p1", "voice-1", "Voice", tts = TtsBinding(TtsProtocol.OpenAiSpeech)))
        val cloud = VoiceModelRef("provider:p1", "voice-1", VoiceCapability.TTS)
        catalog.setTtsSelection(cloud)
        val controller = voiceTestEnvironment(catalog).tts

        assertEquals(cloud, controller.current())
    }
}

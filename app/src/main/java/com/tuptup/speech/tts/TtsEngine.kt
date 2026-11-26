package com.tuptup.speech.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.tuptup.speech.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 播讲引擎：优先系统离线 TTS；系统无引擎/无中文数据时自动切在线合成（百度翻译 TTS）。
 * 两条路都必须回调 onDone，否则 UI 的“播放中”会卡住。
 */
class TtsEngine(private val ctx: Context) {
    private val settings = Settings(ctx.applicationContext)
    val local = ZipVoiceEngine(ctx.applicationContext)
    val anchor = AnchorVoiceEngine(ctx.applicationContext)

    companion object {
        private const val TAG = "TuptupTTS"
        private const val SYSTEM_ENGINE = "com.yuemeng.speechsuite" // 本机预装讯飞语萌
        private const val ONLINE_TTS = "https://fanyi.baidu.com/gettts?lan=zh&spd=5&source=web&text="
        private val ZH_LOCALES = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.forLanguageTag("zh-CN"),
            Locale.forLanguageTag("zho-CN")
        )

        /** 端侧录音音色分析：RMS 响度 + 过零率→音高近似 */
        fun analyze(pcm: ShortArray, sampleRate: Int): Pair<Float, Float> {
            var zcr = 0; var energy = 0.0; var count = 0
            val step = maxOf(1, pcm.size / 20000)
            var prev = 0
            for (i in pcm.indices step step) {
                val v = pcm[i].toInt()
                if (i > 0 && ((prev < 0) != (v < 0))) zcr++
                energy += v * v; count++; prev = v
            }
            val z = zcr.toFloat() / maxOf(1, count)
            val rms = kotlin.math.sqrt(energy / maxOf(1, count)).toFloat() / 32768f
            val pitch = (0.7f + z * 18f).coerceIn(0.6f, 1.8f)
            val volume = (0.8f + rms * 3f).coerceIn(0.6f, 1.4f)
            return pitch to volume
        }
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var mediaPlayer: MediaPlayer? = null
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(Dispatchers.Main)

    var speed: Float = 1.0f
    var pitch: Float = 1.0f

    private val _voices = MutableStateFlow<List<android.speech.tts.Voice>>(emptyList())
    val voicesState: StateFlow<List<android.speech.tts.Voice>> = _voices.asStateFlow()

    private val _engine = MutableStateFlow("未初始化")
    val engineState: StateFlow<String> = _engine.asStateFlow()

    fun init(onReady: (Boolean) -> Unit) {
        bind(SYSTEM_ENGINE) { ok ->
            if (!ok) {
                Log.w(TAG, "engine $SYSTEM_ENGINE unavailable, trying system default")
                tts?.shutdown(); tts = null
                bind(null, onReady)
            } else onReady(ok)
        }
    }

    private fun bind(engine: String?, onBound: (Boolean) -> Unit) {
        try {
            val listener = TextToSpeech.OnInitListener { status ->
                val inst = tts
                var ok = status == TextToSpeech.SUCCESS && inst != null
                var langResult = TextToSpeech.ERROR
                if (ok) {
                    for (loc in ZH_LOCALES) {
                        langResult = inst!!.setLanguage(loc)
                        if (langResult != TextToSpeech.LANG_MISSING_DATA &&
                            langResult != TextToSpeech.LANG_NOT_SUPPORTED) break
                    }
                    ok = langResult != TextToSpeech.LANG_MISSING_DATA &&
                         langResult != TextToSpeech.LANG_NOT_SUPPORTED
                }
                ready = ok
                _voices.value = inst?.voices?.filter {
                    it.locale.language.startsWith("zh", true)
                }?.toList() ?: emptyList()
                _engine.value = if (ok) "默认音色 · ${_voices.value.size} 种可选" else "默认音色"
                Log.d(TAG, "bind engine=$engine status=$status lang=$langResult ready=$ok voices=${_voices.value.size}")
                onBound(ok)
            }
            tts = if (engine == null) TextToSpeech(ctx, listener) else TextToSpeech(ctx, listener, engine)
        } catch (e: Exception) {
            Log.w(TAG, "bind($engine) threw: ${e.message}")
            ready = false
            _engine.value = "默认音色"
            onBound(false)
        }
    }

    fun voices(): List<android.speech.tts.Voice> = _voices.value

    /** 统一入口：我的声音 → 主播音色包 → 系统离线/在线兜底 */
    fun speak(text: String, voice: android.speech.tts.Voice?, stylePitch: Float = 1f, onDone: (() -> Unit)? = null) {
        if (settings.useLocalVoice) speakZipVoice(text, voice, stylePitch, onDone)
        else if (anchor.isDownloaded) speakAnchor(text, voice, stylePitch, onDone)
        else speakLocal(text, voice, stylePitch, onDone)
    }

    /** 我的声音播讲；合成失败自动降级默认音色，不让播讲卡死 */
    private fun speakZipVoice(
        text: String, voice: android.speech.tts.Voice?, stylePitch: Float,
        onDone: (() -> Unit)?
    ) {
        releasePlayer()
        _engine.value = "正在准备…"
        scope.launch {
            val err = local.synthesize(text, settings.localRefText, File(ctx.cacheDir, "tts_local.wav"))
            if (err == null) {
                anchor.releaseTts() // 克隆与主播包互斥加载，省内存
                _engine.value = "我的声音 · 播讲中"
                withContext(Dispatchers.Main) {
                    playFile(File(ctx.cacheDir, "tts_local.wav"), "我的声音", onDone)
                }
            } else {
                Log.w(TAG, "local voice failed: $err -> fallback")
                _engine.value = "播讲中"
                withContext(Dispatchers.Main) { speakLocal(text, voice, stylePitch, onDone) }
            }
        }
    }

    /** 主播音色包播讲；失败自动降级系统音色 */
    private fun speakAnchor(
        text: String, voice: android.speech.tts.Voice?, stylePitch: Float,
        onDone: (() -> Unit)?
    ) {
        releasePlayer()
        _engine.value = "正在准备…"
        scope.launch {
            val err = anchor.synthesize(text, settings.anchorVoiceSid, File(ctx.cacheDir, "tts_anchor.wav"),
                speed = speed * stylePitch)
            if (err == null) {
                local.releaseTts() // 互斥加载
                _engine.value = "播讲中"
                withContext(Dispatchers.Main) {
                    playFile(File(ctx.cacheDir, "tts_anchor.wav"), "播讲完成", onDone, adjustSpeed = false)
                }
            } else {
                Log.w(TAG, "anchor voice failed: $err -> fallback")
                _engine.value = "播讲中"
                withContext(Dispatchers.Main) { speakLocal(text, voice, stylePitch, onDone) }
            }
        }
    }

    private fun speakLocal(text: String, voice: android.speech.tts.Voice?, stylePitch: Float, onDone: (() -> Unit)?) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val vol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
        Log.d(TAG, "speak len=${text.length} ready=$ready musicVolume=$vol/$max")
        if (vol == 0) {
            _engine.value = "⚠ 媒体音量为 0，请调高音量后重试"
            onDone?.invoke()
            return
        }
        val engine = tts
        if (engine != null && ready) {
            engine.stop()
            voice?.let { engine.setVoice(it) }
            engine.setSpeechRate(speed)
            engine.setPitch(stylePitch)
            val utteranceId = "tts_${System.currentTimeMillis()}"
            // 监听器必须在 speak() 之前挂上，否则短句可能先播完再注册，播放状态卡住
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == utteranceId) onDone?.invoke() }
                override fun onError(id: String?) {
                    if (id != utteranceId) return
                    Log.w(TAG, "system engine onError, fallback to online")
                    speakOnline(text, onDone)
                }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            speakOnline(text, onDone)
        }
    }

    /** 在线兜底：百度翻译 TTS 返回 MP3，用 MediaPlayer 播 STREAM_MUSIC */
    private fun speakOnline(text: String, onDone: (() -> Unit)?) {
        releasePlayer()
        _engine.value = "播讲中…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val url = ONLINE_TTS + URLEncoder.encode(text, "UTF-8")
                    val resp = client.newCall(Request.Builder().url(url).build()).execute()
                    val bytes = resp.body?.bytes()
                    if (!resp.isSuccessful || bytes == null || bytes.size < 100) {
                        Log.w(TAG, "online tts http=${resp.code} size=${bytes?.size ?: 0}")
                        false
                    } else {
                        val f = File(ctx.cacheDir, "tts_online.mp3")
                        f.writeBytes(bytes)
                        Log.d(TAG, "online tts fetched bytes=${bytes.size}")
                        withContext(Dispatchers.Main) { playFile(f, "播讲中", onDone) }
                        true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "online tts failed: ${e.message}")
                    false
                }
            }
            if (!ok) {
                _engine.value = "暂时无法播讲"
                onDone?.invoke()
            }
        }
    }

    private fun playFile(f: File, label: String, onDone: (() -> Unit)?, adjustSpeed: Boolean = true) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            if (adjustSpeed) try { mp.playbackParams = mp.playbackParams.setSpeed(speed) } catch (_: Exception) {}
            mp.setOnCompletionListener { p ->
                _engine.value = "$label · 播讲完成"
                onDone?.invoke()
                p.release()
                if (mediaPlayer === p) mediaPlayer = null
            }
            mp.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                _engine.value = "在线音频播放失败"
                onDone?.invoke()
                p.release()
                if (mediaPlayer === p) mediaPlayer = null
                true
            }
            mp.prepare()
            mp.start()
            mediaPlayer = mp
            Log.d(TAG, "online tts started duration=${mp.duration}ms")
        } catch (e: Exception) {
            Log.w(TAG, "playFile failed: ${e.message}")
            onDone?.invoke()
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        mediaPlayer = null
    }

    fun stop() {
        tts?.stop()
        releasePlayer()
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        releasePlayer()
        ready = false
        _voices.value = emptyList()
        _engine.value = "已关闭"
    }
}

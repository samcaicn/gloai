package com.wangzan101.mika.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/**
 * 系统 [TextToSpeech] 的一次性门面。每个合成操作新建一个引擎、用完即 [shutdown]，
 * 所以这里把引擎做成可注入的接口:JVM 测试用一个假引擎驱动合成状态机,真机才碰 [TextToSpeech]。
 * 接口只用 JVM 类型(Locale/File/函数),回调是普通的 lambda,不在接口里出现 Android 监听器。
 */
interface SystemTtsEngine {
    /** 异步初始化;完成后回调一次 [onInit](TextToSpeech.SUCCESS 或 TextToSpeech.ERROR)。 */
    fun init(onInit: (status: Int) -> Unit)

    /** 返回 TextToSpeech.LANG_AVAILABLE / LANG_MISSING_DATA / LANG_NOT_SUPPORTED。 */
    fun setLanguage(locale: Locale): Int

    fun setSpeechRate(rate: Float): Int

    /** 注册合成进度回调;[onError] 只带 utteranceId,错误码由真实引擎自行归一。 */
    fun setOnUtteranceProgressListener(onDone: (utteranceId: String) -> Unit, onError: (utteranceId: String) -> Unit)

    /** 合成到 [file];返回 TextToSpeech.SUCCESS 或 TextToSpeech.ERROR。 */
    fun synthesizeToFile(text: String, file: File, utteranceId: String): Int

    fun stop()

    fun shutdown()
}

/** 真实实现:每个实例持有自己的 [TextToSpeech],调用方保证一次合成用完即关。 */
class AndroidSystemTtsEngine(context: Context) : SystemTtsEngine {
    private val context = context.applicationContext
    private var tts: TextToSpeech? = null

    override fun init(onInit: (status: Int) -> Unit) {
        tts = TextToSpeech(context) { status -> onInit(status) }
    }

    override fun setLanguage(locale: Locale): Int = tts?.setLanguage(locale) ?: TextToSpeech.ERROR

    override fun setSpeechRate(rate: Float): Int = tts?.setSpeechRate(rate) ?: TextToSpeech.ERROR

    override fun setOnUtteranceProgressListener(onDone: (String) -> Unit, onError: (String) -> Unit) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = onDone(utteranceId)
            override fun onError(utteranceId: String) = onError(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = onError(utteranceId)
        })
    }

    override fun synthesizeToFile(text: String, file: File, utteranceId: String): Int =
        tts?.synthesizeToFile(text, null, file, utteranceId) ?: TextToSpeech.ERROR

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}

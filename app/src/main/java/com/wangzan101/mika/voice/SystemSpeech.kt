package com.wangzan101.mika.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** 手机上登记的一个识别服务,见 [SystemSpeech.services]。[component] 是「包名/类名」的短写。 */
data class RecognizerService(val component: String, val open: Boolean, val permission: String?)

/** 手机自带识别:能力检测、错误码怎么归类、识别用的语言。 */
object SystemSpeech {
    /**
     * 安卓 11 起要在清单里声明查询 RecognitionService,否则这里永远是不可用。
     * [silent] 是之前试过、服务不响应的记号(见 [SpeechSettings.systemSilent]):系统只能说有没有,理不理要试了才知道。
     */
    fun capability(context: Context, silent: Boolean = false): SystemCapability {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val online = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        return SystemCapability(available = online || onDevice, onDevice = onDevice, responsive = !silent)
    }

    /**
     * 识别服务的错误码归到哪种失败;null 表示「这一段没听到话」(没匹配上、没开口),按着时重开、松开后照已有的给结果。
     * 列表外的码(服务端错误、语言不支持等)按服务出了问题处理。
     */
    fun failureOf(code: Int): SpeechFailure? = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> SpeechFailure.Busy
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> SpeechFailure.Network
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.NoPermission
        SpeechRecognizer.ERROR_AUDIO -> SpeechFailure.AudioError
        SpeechRecognizer.ERROR_CLIENT -> SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemFailed)
        else -> SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone)
    }

    /** 一个错误码到了之后怎么办:重开、按已听到的收尾、报失败。 */
    sealed interface Outcome {
        data object Retry : Outcome
        /** 当前入口用不了,改试这次会话的下一个识别服务。 */
        data object NextService : Outcome
        data object Finish : Outcome
        data class Fail(val why: SpeechFailure) : Outcome
    }

    /**
     * [holding] 是手还按着,[heard] 是已经听到过字。已经听到的字不丢:松开后、或按着时中途出错,都按听到的收尾。
     * 松开后有的服务在 stopListening 之后报 ERROR_CLIENT,什么都没听到时那是没听清,不是麦克风坏了。
     */
    fun afterError(code: Int, holding: Boolean, heard: Boolean, canTryNext: Boolean = false): Outcome {
        val failure = failureOf(code)
        return when {
            failure == null && holding -> Outcome.Retry
            failure == null || heard -> Outcome.Finish
            !holding && code == SpeechRecognizer.ERROR_CLIENT -> Outcome.Finish
            code == SpeechRecognizer.ERROR_CLIENT && canTryNext -> Outcome.NextService
            else -> Outcome.Fail(failure)
        }
    }

    /**
     * 开始听之后 [SystemSpeechSession.NO_RESPONSE_MS] 内识别服务一个回调都没给时怎么办。
     * [answered] 是这个识别器之前回过话(说明服务是好的,只是这一段卡住了):按已听到的收尾,什么都没听到就是没听清。
     * 从没回过话就是这台手机的识别服务不理第三方 App 的请求,再等也不会有字,报 [SpeechEngineSpec.Why.SystemNoResponse]。
     */
    fun afterSilence(answered: Boolean): Outcome =
        if (answered) Outcome.Finish else Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse))

    /**
     * 松开后再等 [SystemSpeechSession.FINAL_WAIT_MS] 仍没有最终结果时怎么收。
     * 和 [afterSilence] 一样:回过话按已听到的收尾,从没回过话就是不响应。
     * 不能因为没回过话就什么也不做,否则悬浮胶囊会停在「转文字…」。
     */
    fun afterReleaseWait(answered: Boolean): Outcome = afterSilence(answered)

    /**
     * 手机上登记的识别服务(声明了 RecognitionService 的组件)。[RecognizerService.open] 是它对别的 App 开放(exported);
     * [RecognizerService.permission] 是绑定它要的权限。给诊断信息和 [attempts] 用。
     */
    fun services(context: Context): List<RecognizerService> = runCatching {
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map { r ->
            val s = r.serviceInfo
            RecognizerService(ComponentName(s.packageName, s.name).flattenToShortString(), s.exported, s.permission)
        }
    }.getOrDefault(emptyList())

    /**
     * 一次会话依次去试的识别服务,null 是系统默认的那个(不点名,由系统挑)。
     * 之前点名试过、回了话的([remembered],还在手机上)排最前;默认的一个回调都不给时,再点名试其余的,对别的 App 开放的先试。
     * 兼容模式下即使只登记了一个服务,也保留点名的候选:登记了服务不代表系统已选好默认的。
     * 其他设备保持原有的候选规则;最多试 [MAX_ATTEMPTS] 个,免得按着的人干等。
     */
    fun attempts(services: List<RecognizerService>, remembered: String?, compatibility: Boolean = false): List<String?> {
        val known = remembered?.takeIf { r -> services.any { it.component == r } }
        val others = if (services.size < 2 && !compatibility) emptyList() else
            services.filter { it.component != known }.sortedBy { !it.open }.map { it.component }
        return (listOfNotNull(known) + listOf(null) + others).take(MAX_ATTEMPTS)
    }

    /**
     * 只对已验证的设备系列启用识别服务兼容路径,不根据单个机型列白名单。
     * 用公开的厂商和增量构建号识别,不读取或修改系统的默认识别设置;别的设备仍走原来的调用方式。
     */
    fun serviceCompatibility(
        manufacturer: String = Build.MANUFACTURER,
        incremental: String = Build.VERSION.INCREMENTAL,
        sdk: Int = Build.VERSION.SDK_INT,
    ): Boolean = sdk >= 34 && manufacturer.trim().lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco") &&
        Regex("""^OS[0-9]+\..*""").matches(incremental.trim())

    /** 一次会话最多试几个识别服务(含默认的)。 */
    const val MAX_ATTEMPTS = 3

    /** 本机识别说这个语言用不了(没装语言包等):换成联网识别再试。 */
    internal fun languageMissing(code: Int) = code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE

    /** App 语言对应的识别语言(BCP-47);跟随系统时用系统的语言。选的语言和系统语言相同时(中文除外)沿用系统的地区。 */
    fun speechTag(language: AppLanguage, system: Locale = Locale.getDefault()): String = when {
        language == AppLanguage.System -> languageRegion(system)
        // 中文按选的简繁定地区,不跟系统
        language.language == "zh" -> language.speechTag!!
        // 系统也是这种语言时用系统的地区(英式英语、墨西哥西班牙语…),否则用这种语言最常见的地区。
        // 在 App 里选了语言后进程的默认语言就是它、不带地区(en):这时没有地区可沿用,只送 en 识别服务不认
        AppLanguage.modernCode(system.language) == language.language && system.country.isNotEmpty() -> languageRegion(system)
        else -> language.speechTag!!
    }

    /**
     * 只留「语言-地区」:系统语言常带文字子标签(zh-Hans-CN),识别服务普遍只认 zh-CN 这样的写法,
     * 多一段就可能当成不支持的语言。扩展、变体也一并去掉。
     */
    private fun languageRegion(locale: Locale): String =
        Locale.Builder().setLanguage(locale.language).setRegion(locale.country).build().toLanguageTag()

    /**
     * 本机识别装了的语言([installed],来自 checkRecognitionSupport)里,和 [tag] 同一种语言、同一地区的那个写法;没有就是 null。
     * 各家登记的写法不一:Pixel 的本机识别把台湾中文登记成 zh-Hant-TW 和 cmn-Hant-TW,送 zh-TW 它按「没装」报错。
     * 比的时候不看文字子标签,cmn(普通话)当作 zh;[tag] 不带地区时同一种语言的就算。[tag] 本身装了就用它。
     */
    fun installedTag(tag: String, installed: List<String>): String? {
        if (tag in installed) return tag
        val (language, region) = languageAndRegion(tag)
        return installed.firstOrNull { languageAndRegion(it) == language to region }
            ?: installed.firstOrNull { region.isEmpty() && languageAndRegion(it).first == language }
    }

    private fun languageAndRegion(tag: String): Pair<String, String> {
        val locale = Locale.forLanguageTag(tag)
        val language = AppLanguage.modernCode(locale.language)
        return (if (language == "cmn") "zh" else language) to locale.country
    }

    /** 转写接口要的 ISO-639-1 语言代码。 */
    fun isoCode(tag: String): String = Locale.forLanguageTag(tag).language

    // 安卓 12 起才有的错误码;写成数值,低版本收不到也不影响
    private const val ERROR_TOO_MANY_REQUESTS = 10
    private const val ERROR_SERVER_DISCONNECTED = 11
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13
}

/**
 * 手机自带识别的一次会话。[SpeechRecognizer] 只能在主线程建和调,所以这里所有对它的操作都投到主线程。
 * 按着期间识别服务自己结束一段(出最终结果或「没听到」)就立刻重开,各段由 [Segments] 拼起来;
 * 松开后 stopListening,最多等 [FINAL_WAIT_MS] 拿最终结果,等不到就用最后一次中间结果。
 * 每次 startListening 后 [NO_RESPONSE_MS] 内服务一个回调都没有:手还按着、[attempts] 里还有没试的服务,就换下一个去听;
 * 都试过了按 [SystemSpeech.afterSilence] 收尾。从没回过话时调 [onNoResponse],回过话时调一次 [onAnswered] 并带上回话的服务
 * (null 是系统默认的):设置据此数连着几次不响应,够了才记下,也记住点名试通的那个。
 * 本机识别报这个语言用不了时,先问它装了哪些语言:有同一种语言、只是写法不同的(见 [SystemSpeech.installedTag])
 * 就换那个写法接着用本机识别,没有才换联网识别。
 */
class SystemSpeechSession(
    context: Context,
    private val onDevice: Boolean,
    private val languageTag: String,
    private val onNoResponse: () -> Unit = {},
    private val onAnswered: (String?) -> Unit = {},
    /** 依次去试的识别服务,见 [SystemSpeech.attempts];null 是系统默认的。 */
    private val attempts: List<String?> = listOf(null),
    /** 默认关着:只由入口对已经确认需要兼容的设备开启。 */
    private val compatibility: Boolean = false,
    /**
     * 识别服务说没有权限时问一句:是不是这次用的那个服务自己没有录音权限。给的是这次点名的服务的包名(没点名为 null)、
     * 用的是不是本机识别;确定是它缺权限才返回它(见 [SpeechServiceMic.missing]),别的情况为 null。
     */
    private val serviceWithoutMic: (String?, Boolean) -> SpeechServiceTarget? = { _, _ -> null },
) : SpeechSession {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val out = SessionEvents()
    private val segments = Segments()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true

    // 下面这些只在主线程读写
    private var recognizer: SpeechRecognizer? = null
    private var serviceLease: SpeechServiceLease? = null
    private var bindingPending = false
    /** 换识别器或结束时作废旧连接回调。 */
    private var generation = 0L
    private var usingOnDevice = false
    /** 这次送给识别服务的语言;本机识别装的是另一种写法时换成那个,见 [retag]。 */
    private var tag = languageTag
    /** 这次会话已经问过本机识别装了哪些语言;只问一次。 */
    private var retagged = false
    /** 正在等本机识别回答装了哪些语言。 */
    private var asking = false
    /** 正在试 [attempts] 里的第几个。 */
    private var attempt = 0
    private var holding = true
    private val restarts = Restarts()
    private var restartPending = false
    /** 当前识别器回过话没有(决定收尾方式);错误不据此记为下次优先使用的服务。 */
    private var answered = false
    /** 当前识别器已经报过 [onAnswered];每个识别器只报一次,切换后再记,免得每个音量回调都写设置。 */
    private var reported = false
    /** 这次会话已经因为不响应记过一次。松开后的等待和静默计时可能先后到,不能记成两次。 */
    private var notedSilent = false
    // 松开后再等 [FINAL_WAIT_MS]。回过话按已听到的收尾;一个回调都没有也要结束,不能停在「转文字…」
    private val timeout = Runnable { concludeRelease() }
    private val silence = Runnable {
        if (done()) return@Runnable
        if (!answered && holding && attempt + 1 < attempts.size) {
            // 这个服务一个回调都没给:手还按着,换下一个服务接着听
            attempt++
            open(preferOnDevice = false)
            return@Runnable
        }
        concludeSilence()
    }

    /** 额外绑定也要有等待上限,取消/松开不能留下等连接的会话。 */
    private val bindingTimeout = Runnable {
        if (!bindingPending || done()) return@Runnable
        bindingPending = false
        if (holding && attempt + 1 < attempts.size) {
            attempt++
            open(preferOnDevice = false)
        } else concludeSilence()
    }

    /** 静默到点、且不再换下一个服务:按有没有回过话收尾。不响应同一次会话只记一次。 */
    private fun concludeSilence() {
        if (done()) return
        when (val o = SystemSpeech.afterSilence(answered)) {
            is SystemSpeech.Outcome.Fail -> {
                noteSilent()
                finish(SpeechEvent.Failed(o.why))
            }
            else -> finish(SpeechEvent.Final(segments.full()))
        }
    }
    // 问本机识别装了哪些语言,到点没回答:不再等,换联网识别
    private val askTimeout = Runnable {
        if (!asking || done()) return@Runnable
        asking = false
        toOnline()
    }
    private val restart = Runnable {
        restartPending = false
        if (done()) return@Runnable
        if (holding) listen() else concludeRelease()
    }

    /** 松开后的等待到点,或重开时发现手指已经离开:一定结束这次会话。 */
    private fun concludeRelease() {
        if (done()) return
        when (val o = SystemSpeech.afterReleaseWait(answered)) {
            is SystemSpeech.Outcome.Fail -> {
                noteSilent()
                finish(SpeechEvent.Failed(o.why))
            }
            else -> finish(SpeechEvent.Final(segments.full()))
        }
    }

    private fun noteSilent() {
        if (notedSilent) return
        notedSilent = true
        onNoResponse()
    }

    override fun release() = onMain {
        if (!holding || done()) return@onMain
        holding = false
        if (bindingPending) {
            // 还没连上,也就还没开始听:松开后不再突然打开录音。
            finish(SpeechEvent.Final(segments.full()))
            return@onMain
        }
        if (restartPending) {
            // 上一段已经结束、还没重开:没有在听的,直接按已听到的收尾
            finish(SpeechEvent.Final(segments.full()))
            return@onMain
        }
        try {
            // 正在问本机识别装了哪些语言时没有在听的;问完重新听了再停,见 [afterSwitch]
            if (!asking) recognizer?.stopListening()
        } catch (_: RuntimeException) {
            handleError(SpeechRecognizer.ERROR_CLIENT)
        } finally {
            // stopListening 里同步回调可能已经结束会话,也可能拆掉了等待;没结束就一定再排上
            if (!done()) main.postDelayed(timeout, FINAL_WAIT_MS)
        }
    }

    override fun cancel() {
        // 先关事件出口(任何线程都立刻生效),再到主线程收拾识别器
        if (!out.cancel()) return
        onMain {
            try { recognizer?.cancel() } catch (_: RuntimeException) {
                // 取消失败也必须释放额外绑定。
            } finally { teardown() }
        }
    }

    private fun done() = out.finished || out.cancelled

    private fun open(preferOnDevice: Boolean) {
        if (done()) return
        teardown()
        answered = false
        reported = false
        val thisGeneration = generation
        val local = if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        } else null
        usingOnDevice = local != null
        if (local != null) {
            startRecognizer(local)
            return
        }
        val named = attempts.getOrNull(attempt)?.let(ComponentName::unflattenFromString)
        fun begin() {
            if (done() || generation != thisGeneration) return
            val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(context, named) }.getOrNull()
            if (rec == null) handleError(SpeechRecognizer.ERROR_CLIENT) else startRecognizer(rec)
        }
        // 默认入口照旧由系统绑定。兼容路径点名的公开服务,在本次语音会话期间带上调用方已有的前台能力;
        // 等连接建立才开始听,不改默认服务、不替用户授予权限,结束时由 teardown 一并解绑。
        val lease = if (compatibility && named != null) SpeechServiceLease.forService(context, named,
            onReady = {
                if (generation == thisGeneration && !done() && bindingPending) {
                    bindingPending = false
                    main.removeCallbacks(bindingTimeout)
                    begin()
                }
            },
            onUnavailable = {
                if (generation == thisGeneration && !done()) {
                    bindingPending = false
                    main.removeCallbacks(bindingTimeout)
                    handleError(SpeechRecognizer.ERROR_CLIENT)
                }
            },
        ) else null
        if (lease != null) {
            serviceLease = lease
            bindingPending = true
            main.postDelayed(bindingTimeout, NO_RESPONSE_MS)
            if (lease.start() || done() || generation != thisGeneration) return
            serviceLease = null
            bindingPending = false
            main.removeCallbacks(bindingTimeout)
            lease.close()
        }
        begin()
    }

    private fun startRecognizer(rec: SpeechRecognizer) {
        recognizer = rec
        try {
            rec.setRecognitionListener(listenerFor(rec))
            listen()
        } catch (_: RuntimeException) {
            handleError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /** 初始化失败可在兼容模式内换一个入口;已听到的字、松开后的收尾优先,绝不重新录音。 */
    private fun handleError(error: Int) {
        if (done()) return
        if (usingOnDevice && SystemSpeech.languageMissing(error) && segments.full().isEmpty()) {
            heard(remember = false)
            if (!retag()) toOnline()
            return
        }
        val outcome = SystemSpeech.afterError(error, holding, heard = segments.full().isNotEmpty(),
            canTryNext = compatibility && attempt + 1 < attempts.size)
        if (outcome == SystemSpeech.Outcome.NextService) {
            attempt++
            open(preferOnDevice = false)
            return
        }
        // 仅有错误回调不能证明服务可用,不能据此把组件记成下次优先使用的服务。
        heard(remember = SystemSpeech.failureOf(error) == null)
        when (outcome) {
            SystemSpeech.Outcome.Retry -> emptySegment()
            SystemSpeech.Outcome.Finish -> finish(SpeechEvent.Final(segments.full()))
            is SystemSpeech.Outcome.Fail -> finish(SpeechEvent.Failed(explained(outcome.why)))
            SystemSpeech.Outcome.NextService -> Unit
        }
    }

    /**
     * 识别服务说没有权限:Mika 的录音权限是开会话前查过的,所以多半是识别服务自己的没给。查得出来就照实说是它,
     * 查不出来的照原来的说法。要在收拾识别器之前问:这次用的是哪个入口那时才知道。
     */
    private fun explained(why: SpeechFailure): SpeechFailure {
        if (why != SpeechFailure.NoPermission) return why
        val named = attempts.getOrNull(attempt)?.let(ComponentName::unflattenFromString)?.packageName
        return serviceWithoutMic(named, usingOnDevice)?.let { SpeechFailure.ServiceNoPermission(it) } ?: why
    }

    private fun request(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // 联网识别时:手机有本机识别能力就请服务尽量离线识别
            if (!usingOnDevice && onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun listen() {
        val intent = request()
        main.removeCallbacks(silence)
        main.postDelayed(silence, NO_RESPONSE_MS)
        try {
            recognizer?.startListening(intent)
        } catch (_: RuntimeException) {
            handleError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /**
     * 本机识别报这个语言用不了:问它装了哪些语言(安卓 13 起),有同一种语言、写法不同的就换那个写法再听。
     * 返回 false 是问不了(系统太旧、这次会话问过了、调用失败),由调用方换联网识别。
     */
    private fun retag(): Boolean {
        val rec = recognizer ?: return false
        if (retagged || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        retagged = true
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) = answer(support.installedOnDeviceLanguages)
            override fun onError(error: Int) = answer(emptyList())

            private fun answer(installed: List<String>) {
                if (!asking || recognizer !== rec || done()) return
                asking = false
                main.removeCallbacks(askTimeout)
                val other = SystemSpeech.installedTag(tag, installed)
                if (other == null || other == tag) {
                    toOnline()
                    return
                }
                tag = other
                listen()
                afterSwitch()
            }
        }
        asking = runCatching { rec.checkRecognitionSupport(request(), context.mainExecutor, callback) }.isSuccess
        if (asking) main.postDelayed(askTimeout, NO_RESPONSE_MS)
        return asking
    }

    /** 本机识别用不了这个语言:换联网识别,语言照原来的写法送。 */
    private fun toOnline() {
        tag = languageTag
        open(preferOnDevice = false)
        afterSwitch()
    }

    /** 换了识别器或语言写法重新听之后:已经松开了就只收尾,等待上限重新算。 */
    private fun afterSwitch() {
        if (holding || done()) return
        recognizer?.stopListening()
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, FINAL_WAIT_MS)
    }

    private fun finish(e: SpeechEvent) {
        main.removeCallbacks(timeout)
        out.finish(e)
        teardown()
    }

    /** 按着时这一段空了:按 [Restarts] 决定隔多久重开,或者不再重开、按已听到的收尾。重开总是投到主线程稍后做,不在回调里直接开。 */
    private fun emptySegment() {
        when (val d = restarts.onEmpty(android.os.SystemClock.elapsedRealtime())) {
            Restarts.Decision.GiveUp -> finish(SpeechEvent.Final(segments.full()))
            is Restarts.Decision.Restart -> {
                restartPending = true
                main.postDelayed(restart, d.delayMs)
            }
        }
    }

    /** 服务回了话:这一段不算没响应。 */
    private fun heard(remember: Boolean = true) {
        answered = true
        main.removeCallbacks(silence)
        if (remember && !reported) {
            reported = true
            onAnswered(if (usingOnDevice) null else attempts.getOrNull(attempt))
        }
    }

    private fun teardown() {
        generation++
        bindingPending = false
        main.removeCallbacks(bindingTimeout)
        main.removeCallbacks(timeout)
        main.removeCallbacks(silence)
        main.removeCallbacks(restart)
        main.removeCallbacks(askTimeout)
        asking = false
        restartPending = false
        val rec = recognizer
        recognizer = null
        // 先摘掉再 destroy:destroy 里可能同步回调 onError,这时不能再换引擎,否则会在失败处理里嵌套重开
        usingOnDevice = false
        rec?.let { runCatching { it.destroy() } }
        val lease = serviceLease
        serviceLease = null
        lease?.close()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun firstText(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun listenerFor(rec: SpeechRecognizer) = object : RecognitionListener {
        // 一个默认入口可能连续回两个客户端错误;切换后的旧回调不能结束或污染新识别器。
        private fun current() = recognizer === rec && !done()
        override fun onReadyForSpeech(params: Bundle?) { if (current()) heard() }
        override fun onBeginningOfSpeech() { if (current()) heard() }
        override fun onBufferReceived(buffer: ByteArray?) { if (current()) heard() }
        override fun onEndOfSpeech() { if (current()) heard() }
        override fun onEvent(eventType: Int, params: Bundle?) { if (current()) heard() }

        override fun onRmsChanged(rmsdB: Float) {
            if (!current()) return
            heard()
            // 识别服务给的大致在 -2..10 dB,压到 0..1 画波形
            out.send(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!current()) return
            heard()
            val text = firstText(partialResults)
            if (text.isBlank()) return
            restarts.onSpeech()
            out.send(Segments.split(segments.partial(text)))
        }

        override fun onResults(results: Bundle?) {
            if (!current()) return
            heard()
            val text = firstText(results)
            segments.segmentDone(text)
            if (holding) {
                // 识别服务觉得一句说完了,但手还按着:接着听下一段
                if (text.isBlank()) {
                    emptySegment()
                } else {
                    restarts.onSpeech()
                    out.send(Segments.split(segments.full()))
                    restartPending = true
                    main.post(restart)
                }
            } else {
                finish(SpeechEvent.Final(segments.full()))
            }
        }

        override fun onError(error: Int) {
            if (!current()) return
            handleError(error)
        }
    }

    // 放在所有属性之后:onMain 在主线程上是当场执行,open 用到的计时器等属性此时都已初始化
    init {
        onMain { open(onDevice && attempts.firstOrNull() == null) }
    }

    companion object {
        /** 松开后等最终结果的上限。 */
        const val FINAL_WAIT_MS = 2_000L

        /**
         * 开始听之后等识别服务第一个回调(通常是 onReadyForSpeech,正常几百毫秒内到)的上限。
         * 留出识别服务进程冷启动的时间;超过就当这一段没响应。
         */
        const val NO_RESPONSE_MS = 3_000L
    }
}

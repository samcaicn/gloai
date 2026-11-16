package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 一次按住说话的会话,两种引擎(手机自带识别、服务商转写)对界面长一个样:
 * 界面只收 [events]、在松开时调 [release]、在取消时调 [cancel],不用管背后是哪种引擎。
 */
sealed interface SpeechEvent {
    /** 0..1,画波形。 */
    data class Level(val value: Float) : SpeechEvent

    /** 到此刻听到的全文:[tail] 是末尾还可能变的几个字,其余是 [stable]。 */
    data class Partial(val stable: String, val tail: String) : SpeechEvent

    /** 服务商:松开后在转。 */
    data object Converting : SpeechEvent

    /** 最终文字;空串 = 没听清。 */
    data class Final(val text: String) : SpeechEvent

    data class Failed(val why: SpeechFailure) : SpeechEvent
}

sealed interface SpeechFailure {
    data object NoPermission : SpeechFailure
    data object Busy : SpeechFailure
    data object Network : SpeechFailure
    /** 麦克风起不来(识别服务报客户端出错、开始听时抛错、服务商那条路的录音起不来):多半是被别的应用占着。 */
    data object MicUnavailable : SpeechFailure
    /**
     * 识别服务报录音出错(ERROR_AUDIO):录音权限给了也可能录不到,例如系统的麦克风使用总开关关着,
     * 或系统权限管理里单独关掉了麦克风。界面据此说明该去哪里看。
     */
    data object AudioError : SpeechFailure
    data class Provider(val error: ModelError) : SpeechFailure
    data class Unavailable(val why: SpeechEngineSpec.Why) : SpeechFailure
}

interface SpeechSession {
    /** 只给一个收集者;每个事件只送一次,[SpeechEvent.Final]/[SpeechEvent.Failed] 之后或 [cancel] 之后流就结束。 */
    val events: Flow<SpeechEvent>

    /** 能边说边出字(系统、原生流式 ASR 为 true,整段文件转写为 false)。 */
    val live: Boolean

    /**
     * 按住多久都行:边说边传、不攒整段录音的流式识别,不受按住说话的 60 秒封顶,也不倒数。
     * 默认 false(手机自带识别、整段转写和其余引擎照旧封顶)。
     */
    val unlimited: Boolean get() = false

    /** 松开:收尾并给 Final。 */
    fun release()

    /** 丢掉;幂等。 */
    fun cancel()
}

/**
 * 会话的事件出口:无界缓冲,界面晚一点开始收也不丢;结束事件发出后关上。
 * 取消时连同还没被收走的一起丢掉——用户已经不要这次了,迟到的结果不该再冒出来。
 */
internal class SessionEvents {
    private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)

    @Volatile
    var cancelled = false
        private set

    @Volatile
    var finished = false
        private set

    val flow: Flow<SpeechEvent> = channel.receiveAsFlow().filter { !cancelled }

    fun send(e: SpeechEvent) {
        if (!finished && !cancelled) channel.trySend(e)
    }

    /** 发最后一个事件并关上;已经结束或取消了就什么都不做。返回这次是不是真的发了。 */
    @Synchronized
    fun finish(e: SpeechEvent): Boolean {
        if (finished || cancelled) return false
        finished = true
        channel.trySend(e)
        channel.close()
        return true
    }

    /** 返回这次是不是第一次取消。 */
    @Synchronized
    fun cancel(): Boolean {
        if (cancelled) return false
        cancelled = true
        channel.close()
        return true
    }
}

/** 一开始就用不了的会话(没权限、引擎不可用):只发一个 Failed。 */
internal class FailedSession(why: SpeechFailure) : SpeechSession {
    private val out = SessionEvents().also { it.finish(SpeechEvent.Failed(why)) }
    override val events: Flow<SpeechEvent> = out.flow
    override val live = false
    override fun release() {}
    override fun cancel() { out.cancel() }
}

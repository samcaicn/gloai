package com.wangzan101.mika.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 按住说话的状态机:按下、上滑取消或直接发送、松开、到点收尾、收会话的事件,算出界面此刻该显示什么。
 * 不碰界面也不碰手势,对话输入栏和悬浮胶囊共用;会话由调用方开好再交进来([press]),
 * 事件由调用方收了转进来([onEvent]),[tick] 由调用方按住时定时调(计时、到 [maxMs] 收尾;会话不封顶时不收尾)。
 * 手还按着时会话自己先结束了(手机自带识别一段说完不再重开、中途出错但已听到字):结果先留着,
 * 等手指离开再按那时的落点处理——停在取消上就丢掉,否则才交出去;这样不会在用户想取消时把字发出去。
 * 只在一个线程(主线程)上调。
 */
class HoldToTalk(private val clock: () -> Long, private val maxMs: Long = 60_000, private val minMs: Long = 500) {
    sealed interface State {
        data object Idle : State

        /**
         * 按着(或刚松开、手机自带识别还在收尾):[stable] 已经定下的字,[tail] 末尾还可能变的几个字。
         * [ended]:手还按着,会话却已经结束、结果留着等松开。
         * [sendArmed]:手指在「松开 发送」上(输入框的「在听」卡片里),松开后字直接发出、不进输入框。
         * [unlimited]:这次不封顶(见 [SpeechSession.unlimited]),不倒数。
         */
        data class Listening(
            val stable: String,
            val tail: String,
            val level: Float,
            val elapsedMs: Long,
            val cancelArmed: Boolean,
            val live: Boolean,
            val ended: Boolean = false,
            val sendArmed: Boolean = false,
            val unlimited: Boolean = false,
        ) : State

        /** 服务商:松开后在转文字。 */
        data object Converting : State

        /** 界面拿去填输入框([send] 时直接发出),然后 [reset]。 */
        data class Done(val text: String, val send: Boolean = false) : State

        /** null = 没听清(什么都没认出来,或按得太短)。 */
        data class Problem(val why: SpeechFailure?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var session: SpeechSession? = null
    private var startedAt = 0L
    /** 已经松开(或到点)交给会话收尾;之后再松开、再到点都不重复调。 */
    private var released = false
    /** 手还按着时会话已经给出的结果(Done 或 Problem(null)),等松开再交出去。 */
    private var pending: State? = null
    /** 松开时手指在「松开 发送」上:之后到的最终结果直接发出。 */
    private var sendOnFinal = false

    fun press(session: SpeechSession) {
        // 还有上一段没收完(很少见,例如上一段还在转):用户这次重新按,上一段不要了
        this.session?.cancel()
        this.session = session
        startedAt = clock()
        released = false
        pending = null
        sendOnFinal = false
        _state.value = State.Listening("", "", 0f, 0, cancelArmed = false, live = session.live, unlimited = session.unlimited)
    }

    /** 手指从按下处往上移了 [upPx];超过 [thresholdPx] 就是「松开取消」,移回来又恢复。 */
    fun drag(upPx: Float, thresholdPx: Float) = armCancel(upPx > thresholdPx)

    /** 手指此刻在不在「取消」上;悬浮胶囊的落点不是上滑距离,由它直接告诉这里。 */
    fun armCancel(armed: Boolean) = aim(cancel = armed, send = false)

    /** 手指此刻的落点:取消、直接发送,或都不是(松开填进输入框)。两个不会同时为真,取消优先。 */
    fun aim(cancel: Boolean, send: Boolean) {
        val s = _state.value as? State.Listening ?: return
        if (released) return
        val sending = send && !cancel
        if (cancel != s.cancelArmed || sending != s.sendArmed) _state.value = s.copy(cancelArmed = cancel, sendArmed = sending)
    }

    fun release() {
        val s = _state.value as? State.Listening ?: return
        if (released) return
        val early = pending
        val current = session
        if (current == null && early == null) return
        when {
            s.cancelArmed -> {
                current?.cancel()
                session = null
                pending = null
                _state.value = State.Idle
            }
            // 按得太短多半是误触:不去转(服务商的还要花额度),当没听清
            clock() - startedAt < minMs -> {
                current?.cancel()
                session = null
                pending = null
                _state.value = State.Problem(null)
            }
            // 会话在手还按着时已经结束:现在才把留着的结果交出去
            early != null -> {
                pending = null
                _state.value = if (early is State.Done) early.copy(send = s.sendArmed) else early
            }
            current != null -> {
                released = true
                sendOnFinal = s.sendArmed
                current.release()
                // 服务商的:录音器停下要一小会儿,Converting 事件才到;松开当下就显示在转,免得输入框先闪回占位
                if (!s.live) _state.value = State.Converting
            }
        }
    }

    /** 按着时定时调:更新用时;到 [maxMs] 当松开处理——录音到点会自己停、不另外通知,不收尾字就丢了。 */
    fun tick() {
        val s = _state.value as? State.Listening ?: return
        if (released || (session == null && pending == null)) return
        val elapsed = clock() - startedAt
        if (!s.unlimited && elapsed >= maxMs) {
            _state.value = s.copy(elapsedMs = maxMs)
            release()
        } else if (elapsed != s.elapsedMs) {
            _state.value = s.copy(elapsedMs = elapsed)
        }
    }

    fun onEvent(e: SpeechEvent) {
        if (session == null) return
        val now = _state.value
        when (e) {
            // 转文字以后服务商的引擎可能晚到一个音量:只在听的时候才认
            is SpeechEvent.Level -> if (now is State.Listening) _state.value = now.copy(level = e.value)
            is SpeechEvent.Partial -> if (now is State.Listening) _state.value = now.copy(stable = e.stable, tail = e.tail)
            SpeechEvent.Converting -> _state.value = State.Converting
            is SpeechEvent.Final -> {
                session = null
                val text = e.text.trim()
                val result = if (text.isEmpty()) State.Problem(null) else State.Done(text, send = released && sendOnFinal)
                if (released || now !is State.Listening) {
                    _state.value = result
                } else {
                    // 手还按着:先留着,界面照旧显示听到的字;松开时停在取消上就丢掉,否则才交出去
                    pending = result
                    _state.value = now.copy(stable = text, tail = "", level = 0f, ended = true)
                }
            }
            is SpeechEvent.Failed -> {
                session = null
                val result = State.Problem(e.why)
                // 出错不必等松开(再按着也不会有字了);只有手停在取消上时先留着,松开在取消上就不再提示
                if (!released && now is State.Listening && now.cancelArmed) {
                    pending = result
                    _state.value = now.copy(level = 0f, ended = true)
                } else {
                    _state.value = result
                }
            }
        }
    }

    /** 离开页面、转屏、App 到后台:丢掉这一段,回到平时。可以重复调。 */
    fun dispose() {
        session?.cancel()
        session = null
        released = false
        pending = null
        sendOnFinal = false
        _state.value = State.Idle
    }

    /** 界面用完了 [State.Done]/[State.Problem] 之后回到平时;还在听、在转时不动。 */
    fun reset() {
        val s = _state.value
        if (s is State.Done || s is State.Problem) _state.value = State.Idle
    }

    companion object {
        /**
         * 说的话接在输入框已有的字后面:两边挨着的都是中日韩文字或全角标点时直接接上,
         * 否则中间加一个空格(已有的字以空白结尾时不再加)。
         */
        fun join(existing: String, spoken: String): String {
            if (existing.isEmpty()) return spoken
            if (spoken.isEmpty()) return existing
            val left = existing.last()
            if (left.isWhitespace()) return existing + spoken
            return if (isCjk(left) && isCjk(spoken.first())) existing + spoken else "$existing $spoken"
        }

        private fun isCjk(ch: Char): Boolean {
            val block = Character.UnicodeBlock.of(ch)
            if (block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
            ) return true
            return when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO -> true
                else -> false
            }
        }
    }
}

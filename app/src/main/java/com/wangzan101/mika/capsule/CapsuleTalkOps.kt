package com.wangzan101.mika.capsule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.voice.ui.VoiceCopy
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wangzan101.mika.capsule.CapsuleController.Companion.NOTE_SENT_MS
import com.wangzan101.mika.capsule.CapsuleController.Companion.NOTE_SHORT_MS
import com.wangzan101.mika.capsule.CapsuleController.Companion.NOTE_LONG_MS

// 胶囊:按住说话与说话结果、提示条
/** 此刻能不能在胶囊上说话:有面板窗口、不是「你来处理」那一条、语音输入没设成不用。 */
internal fun CapsuleController.canTalk(): Boolean = panelWindow != null && !isBar() && !edgeHidden &&
    (context.applicationContext as MikaApp).speech.choice.value != SpeechChoice.Off

/** 读屏的「说话」:没在说就开始(没有手指,只能发出),在说就发出。 */
internal fun CapsuleController.toggleTalk() {
    if (talk.active && !talk.byTouch && !talk.released) talk.finish(CapsuleVoice.Drop.Send)
    else beginTalk(byTouch = false)
}

/**
 * 开始说:先等着的单击、双击不算了。说给谁按单击会展开的那张卡片(空闲图标上先去读最近那段对话,读到后面板顶上那句跟着换)。
 * 没有录音权限(胶囊弹不了权限框)、语音输入用不了时不录,胶囊上说一句,点一下打开 Mika。
 */
internal fun CapsuleController.beginTalk(byTouch: Boolean) {
    val v = view ?: return
    // 松开后还在转时可以重新按:上一段由 talk.begin 丢掉
    if (capturing || (talk.active && !talk.released) || !canTalk()) return
    resetTaps()
    wake()
    clearNote()
    val loading = if (v.kind == CapsuleKind.Idle || v.kind == CapsuleKind.Notice) scope.async { loadIdle() } else null
    when (val b = talk.begin(CapsuleVoice.title(CapsuleCards.of(model.cardRuns(), follow, idle)), byTouch)) {
        CapsuleTalk.Begin.Started -> {
            talkIdle?.cancel()
            talkIdle = loading
            loading?.let {
                scope.launch {
                    val chat = it.await()
                    if (talk.active && talkIdle === it) talk.title = CapsuleVoice.title(CapsuleCards.of(model.cardRuns(), follow, chat))
                }
            }
        }
        CapsuleTalk.Begin.NoPermission -> {
            loading?.cancel()
            showNote(tr(R.string.capsule_voice_need_mic), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
        }
        is CapsuleTalk.Begin.Unavailable -> {
            loading?.cancel()
            showNote(tr(R.string.capsule_voice_not_ready), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
        }
    }
}

/** 说完了:按松开时的落点发出或打开卡片;没听清、出错时胶囊上说一句(说法和输入栏一样)。 */
internal fun CapsuleController.spoken(drop: CapsuleVoice.Drop, state: HoldToTalk.State) {
    val idleJob = talkIdle
    talkIdle = null
    when (state) {
        is HoldToTalk.State.Done -> scope.launch {
            idleJob?.let { idle = it.await() }
            if (drop == CapsuleVoice.Drop.Edit) editSpoken(state.text) else sendSpoken(state.text)
        }
        is HoldToTalk.State.Problem -> {
            idleJob?.cancel()
            when (val why = state.why) {
                null -> showNote(VoiceCopy.NO_MATCH, CapsuleKind.Failed, opensMika = false, NOTE_SHORT_MS)
                SpeechFailure.NoPermission -> showNote(tr(R.string.capsule_voice_need_mic), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
                // 缺权限的是识别服务:胶囊上说不清,带到 Mika 里(在那里按住说话会有说明和去它设置页的按钮)
                is SpeechFailure.ServiceNoPermission ->
                    showNote(tr(R.string.capsule_voice_service_need_mic), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
                is SpeechFailure.Unavailable -> showNote(tr(R.string.capsule_voice_not_ready), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
                else -> showNote(VoiceCopy.failure(why), CapsuleKind.Failed, opensMika = false, NOTE_LONG_MS)
            }
        }
        else -> idleJob?.cancel()
    }
}

/**
 * 松开直接发出:发给单击会展开的那张卡片里的对话(和双击发屏幕同一套),胶囊上闪「已插话 / 已回答 / 已发出:…」。
 * 那张卡片没有输入框(要你接管、同意)时不发,展开卡片,字先放进输入框。
 */
internal fun CapsuleController.sendSpoken(text: String) {
    val target = CapsuleCards.of(model.cardRuns(), follow, idle)
    val kind = target?.let(CapsuleCards::input)?.kind
    if (target == null || kind == null) {
        open()
        inputUi = inputUi.withSpoken(text)
        refresh()
        return
    }
    submit(target, kind, text, CapsuleCards.conversationOf(target), inputUi.followUpMode)
    showNote(CapsuleVoice.sent(kind, text), CapsuleKind.Done, opensMika = false, NOTE_SENT_MS)
    refresh()
}

/** 拖到「改一改」松开:展开卡片,字在输入框里、键盘弹出,改完自己发。 */
internal fun CapsuleController.editSpoken(text: String) {
    open()
    inputUi = inputUi.withSpoken(text)
    refresh()
    if (card?.let(CapsuleCards::input) != null) openInput()
}

internal fun CapsuleController.showNote(text: String, kind: CapsuleKind, opensMika: Boolean, ms: Long) {
    resetTaps()
    note = CapsuleNote(text, kind, opensMika, ++noteCount)
    wake()
    noteJob?.cancel()
    noteJob = scope.launch {
        delay(ms)
        note = null
        fade()
    }
}

internal fun CapsuleController.clearNote() {
    noteJob?.cancel()
    note = null
}

package com.wangzan101.mika.calls.briefing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import com.wangzan101.mika.calls.CallPlaybackTruncation
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.calls.VolcRealtimeClient
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class VoiceBriefingActivity { IDLE, CONNECTING, LISTENING, CLOSING, AUTHORIZED }

data class VoiceBriefingState(
    val activity: VoiceBriefingActivity = VoiceBriefingActivity.IDLE,
    val targetKey: String = "",
    val ownerTranscript: String = "",
    val ownerPartial: String = "",
    val assistantTranscript: String = "",
    val message: String? = null,
) {
    val active: Boolean get() = activity in setOf(
        VoiceBriefingActivity.CONNECTING, VoiceBriefingActivity.LISTENING, VoiceBriefingActivity.CLOSING,
    )
}

internal interface VoiceBriefingConnection {
    val playbackTruncation: CallPlaybackTruncation? get() = null
    val events: Flow<VolcRealtimeEvent>
    fun send(bytes: ByteArray): Boolean
    fun greet(): Boolean
    suspend fun closeAndAwait()
}

internal interface VoiceBriefingAudio {
    suspend fun start(
        scope: CoroutineScope,
        mayUseAudio: () -> Boolean,
        send: (ByteArray) -> Boolean,
        onFailure: (String) -> Unit,
    )
    fun enqueue(pcm: ByteArray)
    fun interrupt(): Long
    fun stopImmediately()
    suspend fun close()
}

/** Screen-owned, foreground-only. Call methods on the UI thread; gates may be read on IO.
 * The caller must cancel on lifecycle STOP, a changed number/SIM, or any non-IDLE call state.
 * canContinue must additionally reject those conditions until onAuthorized is invoked.
 */
class VoiceBriefingController internal constructor(
    private val scope: CoroutineScope,
    private val connect: suspend (VolcRealtimeCredentials, VolcRealtimeConfig) -> VoiceBriefingConnection,
    private val newAudio: () -> VoiceBriefingAudio,
    private val localGate: () -> Boolean,
    private val maxSessionMillis: Long = 180_000,
) : AutoCloseable {
    constructor(context: Context, scope: CoroutineScope, endpoint: String = VolcRealtimeProtocol.ENDPOINT) : this(
        scope,
        { credentials, config -> ProviderConnection(VolcRealtimeClient(endpoint).connect(credentials, config)) },
        { AndroidVoiceBriefingAudio(context.applicationContext) },
        {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                kotlin.runCatching { context.getSystemService(TelecomManager::class.java)?.isInCall == false }
                    .getOrDefault(false)
        },
    )

    private val mutable = MutableStateFlow(VoiceBriefingState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var operation: Job? = null
    private var audio: VoiceBriefingAudio? = null
    private var closed = false

    fun start(
        targetKey: String,
        credentials: VolcRealtimeCredentials,
        config: VolcRealtimeConfig,
        initialBrief: CallTaskBrief? = null,
        canContinue: () -> Boolean,
        onAuthorized: (CallTaskBrief) -> Unit,
    ): Boolean {
        if (closed || operation != null) return false
        if (targetKey.isBlank() || !credentials.valid || !safeGate(canContinue)) {
            mutable.value = VoiceBriefingState(message = "请确认号码、麦克风权限及电话空闲状态后重试")
            return false
        }
        val conversation = try { VoiceBriefingConversation(initialBrief) }
        catch (_: IllegalArgumentException) {
            mutable.value = VoiceBriefingState(message = "原有交代无法完整保存，请先修改")
            return false
        }
        val token = ++generation
        mutable.value = VoiceBriefingState(activity = VoiceBriefingActivity.CONNECTING, targetKey = targetKey)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var provider: VoiceBriefingConnection? = null
            var localAudio: VoiceBriefingAudio? = null
            var authorized: CallTaskBrief? = null
            var cleanlyClosed = false
            var failure: String? = null
            // Audio callbacks can run off the UI thread. Latch failure before cancellation so
            // even an already buffered final, or a callback after device cleanup, cannot dial.
            val audioFailure = AtomicReference<String?>(null)
            val valid = { audioFailure.get() == null && token == generation && !closed && safeGate(canContinue) }
            try {
                withTimeout(maxSessionMillis) {
                    coroutineScope {
                        val watcher = launch {
                            while (true) {
                                if (!valid()) throw VoiceBriefingFailure("语音交代已停止，未拨号")
                                delay(50)
                            }
                        }
                        try {
                            val prompt = VoiceBriefingConversation.PROMPT + initialBrief?.let {
                                "\n以下是主人已经填写的交代，只作本通上下文：\n目标：${it.goal}\n" +
                                    "必问：${it.questions.joinToString("；")}\n背景：${it.context}\n限制：${it.constraints}"
                            }.orEmpty()
                            provider = connect(credentials, config.copy(systemPrompt = prompt, enablePhoneDtmf = false))
                            if (!valid()) throw VoiceBriefingFailure("语音交代已停止，未拨号")
                            localAudio = newAudio().also { audio = it }
                            localAudio!!.start(this, valid,
                                send = { bytes -> valid() && provider!!.send(bytes) },
                                onFailure = { message ->
                                    audioFailure.compareAndSet(null, message)
                                    this@coroutineScope.cancel(CancellationException("Local briefing audio ended"))
                                },
                            )
                            if (!valid() || !provider!!.greet()) throw VoiceBriefingFailure("语音连接已中断，请重试")
                            mutable.value = mutable.value.copy(activity = VoiceBriefingActivity.LISTENING)
                            provider!!.events.firstOrNull { event ->
                                if (!valid()) throw VoiceBriefingFailure("语音交代已停止，未拨号")
                                when (event) {
                                    VolcRealtimeEvent.UserSpeaking, VolcRealtimeEvent.AssistantInterrupted -> {
                                        val heardFrames = localAudio!!.interrupt()
                                        if (provider!!.playbackTruncation?.interrupt(heardFrames) == false) {
                                            throw VoiceBriefingFailure("无法同步已播放的语音，未拨号")
                                        }
                                    }
                                    is VolcRealtimeEvent.Audio -> localAudio!!.enqueue(event.pcm)
                                    is VolcRealtimeEvent.UserTranscript -> {
                                        authorized = conversation.accept(event)
                                        mutable.value = mutable.value.copy(
                                            ownerTranscript = conversation.ownerTranscript,
                                            ownerPartial = if (event.isFinal) "" else event.text,
                                        )
                                    }
                                    is VolcRealtimeEvent.AssistantTranscript -> {
                                        val full = mutable.value.assistantTranscript + event.delta
                                        if (full.length > 16_384) throw VoiceBriefingFailure("对话已达长度限制，请重新开始")
                                        mutable.value = mutable.value.copy(assistantTranscript = full)
                                    }
                                    is VolcRealtimeEvent.Failed, VolcRealtimeEvent.Closed ->
                                        throw VoiceBriefingFailure("语音连接已结束，未拨号；请重新开始")
                                    // Blank recognition without prior text can be ordinary silence.
                                    // An unfinished partial may contain a constraint: never drop it
                                    // and then authorize from a later, unrelated complete final.
                                    VolcRealtimeEvent.UserTranscriptionEmpty -> {
                                        if (mutable.value.ownerPartial.isNotBlank()) {
                                            throw VoiceBriefingFailure("这句话未完整识别，未拨号；请重新完整交代")
                                        }
                                    }
                                    VolcRealtimeEvent.UserTranscriptionFailed ->
                                        throw VoiceBriefingFailure("本句未能完整识别，未拨号；请重新交代")
                                    else -> Unit
                                }
                                authorized != null
                            }
                            if (authorized == null) throw VoiceBriefingFailure("语音连接已结束，未拨号")
                        } finally {
                            watcher.cancel()
                            localAudio?.stopImmediately()
                            // Audio workers belong to this coroutineScope and are canceled before it exits.
                            localAudio?.close()
                        }
                    }
                }
            } catch (error: CancellationException) {
                failure = if (token == generation) audioFailure.get() ?: "语音交代已到时限或已取消，未拨号" else null
            } catch (error: VoiceBriefingFailure) {
                failure = error.safeMessage
            } catch (_: Exception) {
                failure = "语音交代中断，未拨号；请检查权限或连接后重试"
            } finally {
                conversation.cancel()
                if (token == generation) mutable.value = mutable.value.copy(
                    activity = VoiceBriefingActivity.CLOSING,
                    // Publish the actual stop reason while shutdown is pending. Provider cleanup
                    // may take several seconds and must not look like successful authorization.
                    message = audioFailure.get() ?: failure,
                )
                withContext(NonCancellable) {
                    // No owner instruction can escape until both devices and the provider have ended.
                    val audioClosed = runCatching { localAudio?.stopImmediately(); localAudio?.close() }.isSuccess
                    // Provider shutdown is attempted even if device cleanup reports a failure.
                    val providerClosed = runCatching { withTimeout(4_500) { provider?.closeAndAwait() } }.isSuccess
                    cleanlyClosed = audioClosed && providerClosed
                }
                if (audio === localAudio) audio = null
            }
            if (token != generation || closed) return@launch
            val brief = authorized
            if (brief != null && cleanlyClosed && failure == null && currentCoroutineContext().isActive && valid()) {
                // Invalidate callbacks/events from this session before entering the ordinary dial path.
                generation++
                mutable.value = mutable.value.copy(activity = VoiceBriefingActivity.AUTHORIZED, ownerPartial = "")
                onAuthorized(brief.validatedSnapshot())
            } else {
                mutable.value = mutable.value.copy(activity = VoiceBriefingActivity.IDLE,
                    message = audioFailure.get() ?: failure ?: "语音交代已停止，未拨号")
            }
        }
        operation = job
        // Cancellation can win before a LAZY job ever enters its body. In that case its
        // finally block cannot release the operation, so completion must own that fallback.
        job.invokeOnCompletion {
            if (operation === job) {
                operation = null
                if (job.isCancelled) mutable.value = mutable.value.copy(
                    activity = VoiceBriefingActivity.IDLE,
                    message = mutable.value.message ?: "语音交代已取消，未拨号",
                )
            }
        }
        job.start()
        return true
    }

    fun cancel(message: String? = null) {
        generation++
        audio?.stopImmediately()
        operation?.cancel()
        mutable.value = mutable.value.copy(activity = if (operation != null) VoiceBriefingActivity.CLOSING else VoiceBriefingActivity.IDLE,
            ownerPartial = "", message = message)
        // A new session cannot start until cleanup (or pre-start completion) releases it.
    }

    override fun close() { closed = true; cancel() }

    private fun safeGate(caller: () -> Boolean): Boolean = runCatching { localGate() && caller() }.getOrDefault(false)
}

private class ProviderConnection(private val session: VolcRealtimeSession) : VoiceBriefingConnection {
    override val playbackTruncation get() = session.playbackTruncation
    override val events get() = session.events
    override fun send(bytes: ByteArray) = session.sendPcm16(bytes)
    override fun greet() = session.sendGreeting(VoiceBriefingConversation.GREETING)
    override suspend fun closeAndAwait() {
        session.close()
        session.awaitClosed()
    }
}

package com.wangzan101.mika.calls

import com.wangzan101.mika.calls.VolcRealtimeProtocol.string
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class VolcRealtimeCredentials(apiKey: String = "", appId: String = "", accessKey: String = "") {
    internal val apiKey = apiKey.trim()
    internal val appId = appId.trim()
    internal val accessKey = accessKey.trim()
    private val usesApiKey = this.apiKey.isNotEmpty()

    val valid: Boolean get() = if (usesApiKey) isHeaderValue(apiKey) else isHeaderValue(appId) && isHeaderValue(accessKey)

    internal fun applyHeaders(builder: Request.Builder) {
        if (!valid) throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH)
        if (usesApiKey) builder.header("X-Api-Key", apiKey)
        else builder.header("X-Api-App-Id", appId)
            .header("X-Api-Access-Key", accessKey)
            .header("X-Api-Resource-Id", VolcRealtimeProtocol.RESOURCE)
            .header("X-Api-App-Key", VolcRealtimeProtocol.LEGACY_APP_KEY)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
    }

    private fun isHeaderValue(value: String): Boolean = value.isNotEmpty() && value.length <= 1024 &&
        value.all { it in '!'..'~' }

    override fun toString(): String = "VolcRealtimeCredentials(redacted)"
}

/** The error text intentionally excludes credentials, response bodies, and transcripts. */
class VolcRealtimeException internal constructor(val kind: Kind, val code: Long? = null) :
    IOException("Realtime voice: ${kind.name.lowercase()}${code?.let { " ($it)" }.orEmpty()}") {
    enum class Kind { AUTH, RATE_LIMITED, TIMEOUT, NETWORK, PROTOCOL, PROVIDER, BACKPRESSURE }
}

sealed interface VolcRealtimeEvent {
    /** PCM s16le, mono, 24 kHz. */
    data class Audio(val pcm: ByteArray, val itemId: String? = null, val contentIndex: Int = 0) : VolcRealtimeEvent
    data object UserSpeaking : VolcRealtimeEvent
    data class UserTranscript(
        val text: String,
        val isFinal: Boolean,
        /** Only a complete, non-blank provider final can authorize call control. */
        val isCompleteFinal: Boolean = isFinal,
        /** A late identified final is history, never control of the current speaking turn. */
        val isCurrentTurn: Boolean = true,
        /** Earlier captured instructions have not received a final or failure yet. */
        val hasUnresolvedPriorTurn: Boolean = false,
    ) : VolcRealtimeEvent
    data object UserSpeechEnded : VolcRealtimeEvent
    /** Provider observations only; these do not start, stop, or retry a turn. */
    data object InputAudioCommitted : VolcRealtimeEvent
    data object UserTranscriptionEmpty : VolcRealtimeEvent
    data object UserTranscriptionFailed : VolcRealtimeEvent
    data object AssistantAudioStarted : VolcRealtimeEvent
    data object AssistantAudioEnded : VolcRealtimeEvent
    data object AssistantInterrupted : VolcRealtimeEvent
    data class AssistantTranscript(val delta: String) : VolcRealtimeEvent
    data object AssistantTextEnded : VolcRealtimeEvent
    /** Only an enabled, validated tool call can reach the phone bridge. */
    data class FunctionCall(val callId: String, val name: String, val arguments: JsonObject) : VolcRealtimeEvent
    data class Failed(val error: VolcRealtimeException) : VolcRealtimeEvent
    data object Closed : VolcRealtimeEvent
}

/** Opens one session and never retries it automatically. */
class VolcRealtimeClient internal constructor(
    http: OkHttpClient,
    private val endpoint: String,
    private val connectTimeoutMs: Long = 10_000,
    private val closeTimeoutMs: Long = 3_000,
) {
    constructor() : this(defaultHttp, VolcRealtimeProtocol.ENDPOINT)
    constructor(endpoint: String) : this(defaultHttp, endpoint)

    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    /** Returns after session.create and, when enabled, the phone tools update are acknowledged. */
    suspend fun connect(
        credentials: VolcRealtimeCredentials,
        config: VolcRealtimeConfig = VolcRealtimeConfig(),
    ): VolcRealtimeSession {
        val request = if (config.protocol == CallVoiceProtocol.OPENAI_REALTIME) {
            require(endpoint == OpenAiCallProtocol.ENDPOINT)
            require(credentials.valid && credentials.apiKey.isNotBlank())
            val url = okhttp3.HttpUrl.Builder().scheme("https").host("api.openai.com")
                .addPathSegments("v1/realtime").addQueryParameter("model", config.model).build()
            Request.Builder().url(url).header("Authorization", "Bearer " + credentials.apiKey).build()
        } else Request.Builder().url(endpoint).apply { credentials.applyHeaders(this) }.build()
        val session = VolcRealtimeSession(config, closeTimeoutMs)
        try {
            session.attach(client.newWebSocket(request, session.listener))
            return withTimeoutOrNull(connectTimeoutMs) { session.awaitReady(); session }
                ?: throw VolcRealtimeException(VolcRealtimeException.Kind.TIMEOUT)
        } catch (error: CancellationException) {
            session.abort(VolcRealtimeException(VolcRealtimeException.Kind.NETWORK))
            throw error
        } catch (error: Exception) {
            val safe = (error as? VolcRealtimeException) ?: VolcRealtimeException(VolcRealtimeException.Kind.NETWORK)
            session.abort(safe)
            throw safe
        }
    }

    companion object {
        const val INPUT_SAMPLE_RATE = VolcRealtimeProtocol.INPUT_RATE
        const val OUTPUT_SAMPLE_RATE = VolcRealtimeProtocol.OUTPUT_RATE
        const val INPUT_CHUNK_BYTES = VolcRealtimeProtocol.CHUNK_BYTES

        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}

class VolcRealtimeSession internal constructor(
    private val config: VolcRealtimeConfig,
    private val closeTimeoutMs: Long,
) : CallRealtimeSession {
    private enum class Phase { CONNECTING, CREATING, CONFIGURING_TOOLS, ACTIVE, ENDING_SESSION, ENDING_SOCKET, ENDED }
    private var phase = Phase.CONNECTING
    private var socket: WebSocket? = null
    private var closeWatchdog: Job? = null
    private var sequence = 0L
    private val openAi = config.protocol == CallVoiceProtocol.OPENAI_REALTIME
    private val resampler = if (openAi) CallPcm16To24() else null
    override val playbackTruncation = if (openAi) CallPlaybackTruncation(::truncateAudio) else null
    private var muted = false
    private class ToolBatch(val ids: List<String>)
    private class ToolCall(val batch: ToolBatch, val responseId: String?) {
        var result: JsonObject? = null
        var sent = false
    }
    // Never evict IDs: eviction would allow an old delivery to press the keys twice.
    private val toolCalls = LinkedHashMap<String, ToolCall>()
    private var currentUserItem: String? = null
    private var userText = StringBuilder()
    private var userTextTruncated = false
    private var userTurnActive = false
    private var unresolvedAnonymousTurn = false
    private class UserItemState {
        val text = StringBuilder()
        var truncated = false
        var started = false
        var ended = false
        var finalEmitted = false
    }
    private val userItems = LinkedHashMap<String, UserItemState>()
    private var audioActive = false
    private var audioId: String? = null
    private val retiredResponseIds = LinkedHashSet<String>()
    private var lastResponseId: String? = null
    private var waitForAudioStart = false
    private val ready = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    private val output = Channel<VolcRealtimeEvent>(32)
    private val collecting = AtomicBoolean(false)
    @Volatile private var discardPending = false
    private var terminal: VolcRealtimeEvent = VolcRealtimeEvent.Closed

    /** Single consumer. Keep the collector responsive; overflowing the buffer ends the session. */
    override val events: Flow<VolcRealtimeEvent> = flow {
        check(collecting.compareAndSet(false, true)) { "Realtime events have one consumer" }
        for (event in output) {
            if (discardPending || event is VolcRealtimeEvent.FunctionCall && !canDeliverFunctionCall(event)) continue
            playbackTruncation?.observe(event)
            emit(event)
        }
        emit(terminal)
    }

    internal val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = opened(webSocket)
        override fun onMessage(webSocket: WebSocket, text: String) = received(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = invalidFrame()
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = closing(webSocket, code)
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = socketClosed(code)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            response?.close()
            val kind = when (code) {
                401, 403 -> VolcRealtimeException.Kind.AUTH
                429 -> VolcRealtimeException.Kind.RATE_LIMITED
                null -> VolcRealtimeException.Kind.NETWORK
                else -> VolcRealtimeException.Kind.PROVIDER
            }
            abort(VolcRealtimeException(kind, code?.toLong()))
        }
    }

    internal suspend fun awaitReady() = ready.await()

    @Synchronized internal fun attach(webSocket: WebSocket) {
        if (phase == Phase.ENDED) webSocket.cancel() else socket = webSocket
    }

    @Synchronized private fun opened(webSocket: WebSocket) {
        if (phase != Phase.CONNECTING) { webSocket.cancel(); return }
        socket = webSocket
        phase = Phase.CREATING
        if (!openAi) send(VolcRealtimeProtocol.create(nextId(), config))
    }

    /** Accepts raw 16 kHz mono PCM16 LE in real time, preferably exactly 640 bytes per 20 ms. */
    @Synchronized override fun sendPcm16(bytes: ByteArray, byteCount: Int): Boolean {
        if (phase != Phase.ACTIVE || muted || byteCount !in 2..VolcRealtimeProtocol.MAX_INPUT_BYTES ||
            byteCount > bytes.size || byteCount % 2 != 0) return false
        val converted = resampler?.convert(bytes, byteCount)
        if (converted != null && converted.isEmpty()) return true
        return send(VolcRealtimeProtocol.audio(nextId(), converted ?: bytes, converted?.size ?: byteCount))
    }

    @Synchronized private fun truncateAudio(itemId: String, contentIndex: Int, playedMs: Int): Boolean =
        phase == Phase.ACTIVE && send(OpenAiCallProtocol.truncate(itemId, contentIndex, playedMs))

    /** Call after phone audio is ready; no greeting is sent by connect(). */
    @Synchronized override fun sendGreeting(text: String): Boolean {
        if (phase != Phase.ACTIVE || text.isBlank() || text.length > 1024) return false
        return send(if (openAi) OpenAiCallProtocol.greeting(text) else VolcRealtimeProtocol.greeting(nextId(), text))
    }

    @Synchronized override fun setMuted(muted: Boolean): Boolean {
        if (phase != Phase.ACTIVE) return false
        if (this.muted == muted) return true
        return (openAi || send(VolcRealtimeProtocol.mute(nextId(), muted))).also { if (it) this.muted = muted }
    }

    /** Once per call ID. A provider batch is returned together after all its tools finish. */
    @Synchronized override fun sendToolResult(callId: String, result: JsonObject): Boolean {
        if (phase != Phase.ACTIVE || !config.enablePhoneDtmf ||
            result.toString().length > VolcRealtimeProtocol.MAX_TOOL_RESULT_CHARS) return false
        val call = toolCalls[callId] ?: return false
        if (call.result != null || call.sent) return false
        call.result = result
        return flushToolBatch(call.batch)
    }

    /** Stops local media immediately; waits briefly for session.closed before closing the socket. */
    @Synchronized override fun close() {
        if (phase == Phase.ENDED) return
        discardPending = true
        if (openAi) {
            phase = Phase.ENDING_SOCKET
            if (socket?.close(1000, null) == true) armCloseWatchdog() else end(null, cancelSocket = true)
            return
        }
        when (phase) {
            Phase.ACTIVE, Phase.CREATING, Phase.CONFIGURING_TOOLS -> {
                phase = Phase.ENDING_SESSION
                if (send(VolcRealtimeProtocol.close(nextId()))) armCloseWatchdog()
            }
            Phase.CONNECTING -> end(null, cancelSocket = true)
            Phase.ENDING_SESSION, Phase.ENDING_SOCKET, Phase.ENDED -> Unit
        }
    }

    suspend fun awaitClosed() = closed.await()

    @Synchronized internal fun abort(error: VolcRealtimeException) {
        end(if (discardPending) null else error, cancelSocket = true)
    }

    @Synchronized private fun invalidFrame() {
        if (phase == Phase.CREATING || phase == Phase.CONFIGURING_TOOLS || phase == Phase.ACTIVE) abort(VolcRealtimeException(VolcRealtimeException.Kind.PROTOCOL))
    }

    @Synchronized private fun received(text: String) {
        if (phase == Phase.ENDED || phase == Phase.ENDING_SOCKET) return
        val frame = VolcRealtimeProtocol.parse(text)
        if (frame == null) { invalidFrame(); return }
        val type = when (val wireType = frame.string("type")) {
            "input_audio_buffer.speech_started" -> if (openAi) VolcRealtimeProtocol.USER_STARTED else wireType
            "response.output_audio_transcript.delta" -> if (openAi) VolcRealtimeProtocol.TEXT_DELTA else wireType
            "response.output_audio_transcript.done" -> if (openAi) VolcRealtimeProtocol.TEXT_DONE else wireType
            else -> wireType
        }
        when (type) {
            VolcRealtimeProtocol.SESSION_CREATED -> if (phase == Phase.CREATING) {
                if (openAi) {
                    phase = Phase.CONFIGURING_TOOLS
                    send(OpenAiCallProtocol.configure(config))
                } else if (config.enablePhoneDtmf) {
                    phase = Phase.CONFIGURING_TOOLS
                    send(VolcRealtimeProtocol.enablePhoneTools(nextId()))
                } else {
                    phase = Phase.ACTIVE
                    ready.complete(Unit)
                }
            }
            VolcRealtimeProtocol.SESSION_UPDATED -> if (phase == Phase.CONFIGURING_TOOLS) {
                // Only one update is outstanding. event_id may identify the server event itself.
                phase = Phase.ACTIVE
                ready.complete(Unit)
            }
            VolcRealtimeProtocol.SESSION_CLOSED -> {
                if (phase == Phase.CREATING || phase == Phase.CONFIGURING_TOOLS) {
                    abort(VolcRealtimeException(VolcRealtimeException.Kind.PROVIDER))
                } else {
                    phase = Phase.ENDING_SOCKET
                    armCloseWatchdog()
                    if (socket?.close(1000, null) != true) end(null, cancelSocket = true)
                }
            }
            VolcRealtimeProtocol.ERROR -> {
                val code = VolcRealtimeProtocol.errorCode(frame)
                val kind = if (openAi) OpenAiCallProtocol.errorKind(frame["error"] as? JsonObject) else when (code) {
                    401L, 403L -> VolcRealtimeException.Kind.AUTH
                    429L -> VolcRealtimeException.Kind.RATE_LIMITED
                    else -> VolcRealtimeException.Kind.PROVIDER
                }
                abort(VolcRealtimeException(kind, code))
            }
        }
        if (phase != Phase.ACTIVE) return
        val responseId = frame.string("response_id")?.takeIf { it.isNotBlank() }
        when (type) {
            "response.created" -> if (openAi) {
                acceptResponseIdentity((frame["response"] as? JsonObject)?.string("id"))
            }
            "response.done" -> if (openAi) handleOpenAiResponse(frame)
            VolcRealtimeProtocol.FUNCTION_CALL -> if (!openAi) receiveFunctionCalls(frame, responseId)
            VolcRealtimeProtocol.INPUT_AUDIO_COMMITTED -> publish(VolcRealtimeEvent.InputAudioCommitted)
            VolcRealtimeProtocol.USER_STARTED -> {
                val itemId = frame.string("item_id")?.takeIf { it.isNotBlank() }
                val item = itemId?.let(::userItem)
                if (item?.ended == true || item?.started == true) return
                item?.started = true
                // An anonymous partial cannot be recovered by item identity once replaced.
                // Keep that uncertainty for the session instead of silently dropping a restriction.
                if (currentUserItem == null && userTurnActive) {
                    unresolvedAnonymousTurn = true
                }
                currentUserItem = itemId
                userText = item?.text ?: StringBuilder()
                userTextTruncated = item?.truncated ?: false
                userTurnActive = true
                interruptAudio()
                publish(VolcRealtimeEvent.UserSpeaking)
            }
            VolcRealtimeProtocol.USER_DELTA -> {
                if (openAi) return // Completed transcription carries the authoritative text.
                val itemId = frame.string("item_id")?.takeIf { it.isNotBlank() }
                val isNewItem = itemId != null && itemId !in userItems
                val item = itemId?.let(::userItem)
                if (item?.ended == true) return
                val snapshot = asrText(frame) ?: return
                if (isNewItem) {
                    // Adopt an explicitly new ID, including a current anonymous turn. Known parked
                    // items can keep accumulating history but must never reclaim the current lane.
                    if (currentUserItem == null && userTurnActive && userText.isNotEmpty()) {
                        item!!.text.append(userText)
                        item.truncated = userTextTruncated
                    }
                    currentUserItem = itemId
                    userText = item!!.text
                }
                val text = item?.text ?: userText
                // Seeduplex's official Web demo replaces the owner question for each ASR
                // delta (setQuestion), while appending assistant text (appendAnswer). ASR
                // events are full, revisable snapshots despite the event's "delta" name.
                // Never infer this by text overlap: genuine repeated words must survive.
                text.setLength(0)
                text.append(snapshot, 0, minOf(MAX_TRANSCRIPT_CHARS, snapshot.length))
                val truncated = snapshot.length > MAX_TRANSCRIPT_CHARS
                item?.truncated = truncated
                // Unidentified events retain the legacy in-order current lane; never guess an ID by delay.
                if (itemId == null || itemId == currentUserItem) {
                    userTurnActive = true
                    userTextTruncated = truncated
                    publish(VolcRealtimeEvent.UserTranscript(text.toString(), isFinal = false))
                }
            }
            VolcRealtimeProtocol.USER_COMPLETED -> {
                val itemId = frame.string("item_id")?.takeIf { it.isNotBlank() }
                val item = itemId?.let(::userItem)
                if (item?.finalEmitted == true) return
                val isCurrent = itemId == null || itemId == currentUserItem
                val explicitFinal = asrText(frame)?.takeIf { it.isNotBlank() }
                val hasFinalText = ASR_TEXT_FIELDS.any { frame.string(it) != null }
                val transcript = explicitFinal ?: (item?.text ?: userText).toString()
                if (transcript.isNotBlank()) publish(VolcRealtimeEvent.UserTranscript(
                    transcript.take(MAX_TRANSCRIPT_CHARS),
                    isFinal = true,
                    // A payload-free completed event finalizes the latest full snapshot.
                    // Explicitly blank results and truncated snapshots cannot authorize.
                    isCompleteFinal = if (explicitFinal != null) explicitFinal.length <= MAX_TRANSCRIPT_CHARS
                        else !hasFinalText && !(item?.truncated ?: userTextTruncated),
                    isCurrentTurn = isCurrent,
                    hasUnresolvedPriorTurn = unresolvedAnonymousTurn || userItems.any { (id, prior) ->
                        id != (itemId ?: currentUserItem) && !prior.ended && (prior.started || prior.text.isNotBlank())
                    },
                ))
                (item ?: currentUserItem?.let(userItems::get))?.let {
                    it.ended = true
                    it.finalEmitted = transcript.isNotBlank()
                    it.text.clear()
                }
                if (isCurrent) {
                    finishCurrentUser()
                    publish(VolcRealtimeEvent.UserSpeechEnded)
                    if (transcript.isBlank()) publish(VolcRealtimeEvent.UserTranscriptionEmpty)
                }
            }
            VolcRealtimeProtocol.USER_FAILED -> {
                val itemId = frame.string("item_id")?.takeIf { it.isNotBlank() }
                val item = itemId?.let(::userItem)
                if (item?.ended == true) return
                (item ?: currentUserItem?.let(userItems::get))?.let { it.ended = true }
                if (itemId == null || itemId == currentUserItem) {
                    finishCurrentUser()
                    publish(VolcRealtimeEvent.UserSpeechEnded)
                }
                publish(VolcRealtimeEvent.UserTranscriptionFailed)
            }
            VolcRealtimeProtocol.TEXT_DELTA -> if (!isRetiredResponse(responseId)) frame.string("delta")?.takeIf { it.isNotEmpty() }?.let {
                acceptResponseIdentity(responseId)
                publish(VolcRealtimeEvent.AssistantTranscript(it))
            }
            VolcRealtimeProtocol.TEXT_DONE -> if (!isRetiredResponse(responseId)) {
                acceptResponseIdentity(responseId)
                publish(VolcRealtimeEvent.AssistantTextEnded)
            }
            VolcRealtimeProtocol.AUDIO_STARTED -> if (!isRetiredResponse(responseId)) {
                acceptResponseIdentity(responseId)
                if (responseId != null && audioId != null && responseId != audioId) retireResponse(audioId)
                waitForAudioStart = false
                if (!audioActive || (responseId != null && responseId != audioId)) {
                    audioId = responseId
                    audioActive = true
                    publish(VolcRealtimeEvent.AssistantAudioStarted)
                }
            }
            VolcRealtimeProtocol.AUDIO_DELTA -> {
                if ((waitForAudioStart && !openAi) || isRetiredResponse(responseId)) return
                val pcm = VolcRealtimeProtocol.pcm(frame) ?: run { invalidFrame(); return }
                if (pcm.isEmpty()) return
                acceptResponseIdentity(responseId)
                if (!audioActive) {
                    audioActive = true
                    publish(VolcRealtimeEvent.AssistantAudioStarted)
                }
                if (responseId != null) {
                    if (audioId != null && responseId != audioId) retireResponse(audioId)
                    audioId = responseId
                }
                if (openAi) {
                    val itemId = frame.string("item_id")
                    val index = (frame["content_index"] as? JsonPrimitive)?.intOrNull
                    if (itemId.isNullOrBlank() || index == null || index < 0) { invalidFrame(); return }
                    publish(VolcRealtimeEvent.Audio(pcm, itemId, index))
                } else publish(VolcRealtimeEvent.Audio(pcm))
            }
            VolcRealtimeProtocol.AUDIO_DONE -> if (!isRetiredResponse(responseId) && audioActive && (responseId == null || responseId == audioId || audioId == null)) {
                audioActive = false
                audioId = null
                publish(VolcRealtimeEvent.AssistantAudioEnded)
            }
            VolcRealtimeProtocol.CANCELED -> {
                // A late cancellation for an older response must not flush the replacement.
                val currentId = audioId
                if (responseId != null && (isRetiredResponse(responseId) ||
                        (currentId != null && responseId != currentId))) return
                interruptAudio()
                publish(VolcRealtimeEvent.AssistantInterrupted)
            }
        }
    }

    private fun handleOpenAiResponse(frame: JsonObject) {
        val response = frame["response"] as? JsonObject ?: return
        val id = response.string("id")
        when (response.string("status")) {
            "completed" -> {
                val calls = (response["output"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    .filter { it.string("type") == "function_call" }
                if (calls.isNotEmpty()) receiveFunctionCalls(buildJsonObject { put("items", JsonArray(calls)) }, id)
            }
            "cancelled" -> if (!isRetiredResponse(id) && (lastResponseId == null || lastResponseId == id)) {
                interruptAudio()
                publish(VolcRealtimeEvent.AssistantInterrupted)
            }
            "failed", "incomplete" -> {
                val details = response["status_details"] as? JsonObject
                abort(VolcRealtimeException(OpenAiCallProtocol.errorKind(details?.get("error") as? JsonObject)))
            }
        }
    }

    private fun receiveFunctionCalls(frame: JsonObject, responseId: String?) {
        if (!config.enablePhoneDtmf) { invalidFrame(); return }
        val items = frame["items"] as? JsonArray
        if (items == null || items.size !in 1..VolcRealtimeProtocol.MAX_FUNCTION_BATCH) {
            invalidFrame(); return
        }
        // Validate the complete envelope before publishing anything that can press a key.
        val parsed = items.map { element ->
            val item = element as? JsonObject ?: run { invalidFrame(); return }
            val id = item.string("call_id") ?: run { invalidFrame(); return }
            if (!VolcRealtimeProtocol.validCallId(id)) { invalidFrame(); return }
            id to item
        }
        if (parsed.groupBy { it.first }.values.any { group -> group.map { it.second }.distinct().size > 1 }) {
            invalidFrame(); return
        }
        val newCalls = parsed.distinctBy { it.first }.filter { it.first !in toolCalls }
        if (toolCalls.size + newCalls.size > VolcRealtimeProtocol.MAX_FUNCTION_CALLS) {
            invalidFrame(); return
        }
        if (newCalls.isEmpty()) return // At-most-once, including failed or still-running calls.
        val batch = ToolBatch(newCalls.map { it.first })
        newCalls.forEach { (id, _) -> toolCalls[id] = ToolCall(batch, responseId) }
        val stale = isRetiredResponse(responseId)
        if (!stale) acceptResponseIdentity(responseId)
        for ((id, item) in newCalls) {
            val name = item.string("name")
            val rawArguments = item.string("arguments")
            val arguments = if (rawArguments != null && rawArguments.length <= VolcRealtimeProtocol.MAX_FUNCTION_ARGUMENT_CHARS) {
                try { Json.parseToJsonElement(rawArguments) as? JsonObject } catch (_: IllegalArgumentException) { null }
            } else null
            val error = when {
                stale -> "stale_response"
                name != VolcRealtimeProtocol.DTMF_FUNCTION -> "unsupported_tool"
                arguments == null || !VolcRealtimeProtocol.validDtmfArguments(arguments) -> "invalid_arguments"
                else -> null
            }
            if (error != null) {
                toolCalls.getValue(id).result = buildJsonObject { put("ok", false); put("error", error) }
            } else {
                publish(VolcRealtimeEvent.FunctionCall(id, name!!, arguments!!))
                if (phase != Phase.ACTIVE) return
            }
        }
        flushToolBatch(batch)
    }

    @Synchronized private fun canDeliverFunctionCall(event: VolcRealtimeEvent.FunctionCall): Boolean {
        if (phase != Phase.ACTIVE) return false
        val call = toolCalls[event.callId] ?: return false
        if (call.sent || call.result != null) return false
        if (!isRetiredResponse(call.responseId)) return true
        // A queued call may become stale while its consumer is handling earlier audio.
        call.result = buildJsonObject { put("ok", false); put("error", "stale_response") }
        flushToolBatch(call.batch)
        return false
    }

    private fun flushToolBatch(batch: ToolBatch): Boolean {
        val calls = batch.ids.map { toolCalls.getValue(it) }
        if (calls.any { it.sent }) return false
        if (calls.any { it.result == null }) return true
        val results = batch.ids.map { it to toolCalls.getValue(it).result!! }
        val sent = if (openAi) {
            results.all { (id, result) -> send(OpenAiCallProtocol.toolResult(id, result)) } &&
                (isRetiredResponse(calls.first().responseId) || send("""{"type":"response.create"}"""))
        } else send(VolcRealtimeProtocol.toolResults(nextId(), results))
        if (sent) calls.forEach { it.sent = true; it.result = null }
        return sent
    }

    private fun userItem(id: String): UserItemState {
        userItems[id]?.let { return it }
        if (userItems.size >= MAX_TRACKED_USER_ITEMS) {
            val oldest = userItems.keys.firstOrNull { it != currentUserItem } ?: userItems.keys.first()
            userItems.remove(oldest)?.let { evicted ->
                if (!evicted.ended && (evicted.started || evicted.text.isNotBlank())) unresolvedAnonymousTurn = true
            }
        }
        return UserItemState().also { userItems[id] = it }
    }

    private fun finishCurrentUser() {
        currentUserItem = null
        userText = StringBuilder()
        userTextTruncated = false
        userTurnActive = false
    }

    // Same field priority as the official Seeduplex Web demo's extractEventText.
    private fun asrText(frame: JsonObject): String? = ASR_TEXT_FIELDS.firstNotNullOfOrNull { key ->
        frame.string(key)?.takeIf { it.isNotEmpty() }
    }

    private fun isRetiredResponse(id: String?): Boolean = id != null && id in retiredResponseIds

    private fun acceptResponseIdentity(id: String?) {
        if (id == null) return
        if (lastResponseId != null && id != lastResponseId) retireResponse(lastResponseId)
        lastResponseId = id
    }

    private fun retireResponse(id: String?) {
        if (id == null) return
        retiredResponseIds.add(id)
        if (retiredResponseIds.size > MAX_RETIRED_RESPONSES) retiredResponseIds.remove(retiredResponseIds.first())
    }

    private fun interruptAudio() {
        // Text can arrive before synthesis starts. Retire that observed identity too.
        retireResponse(lastResponseId)
        lastResponseId = null
        if (!audioActive) return
        retireResponse(audioId)
        audioActive = false
        audioId = null
        waitForAudioStart = true
    }

    @Synchronized private fun closing(webSocket: WebSocket, code: Int) {
        if (phase == Phase.ENDED) return
        if (phase == Phase.CONNECTING || phase == Phase.CREATING || phase == Phase.CONFIGURING_TOOLS || (!discardPending && code != 1000)) {
            abort(VolcRealtimeException(VolcRealtimeException.Kind.NETWORK, code.toLong()))
            return
        }
        phase = Phase.ENDING_SOCKET
        armCloseWatchdog()
        webSocket.close(1000, null)
    }

    @Synchronized private fun socketClosed(code: Int) {
        val expected = phase == Phase.ENDING_SOCKET || phase == Phase.ENDING_SESSION || (phase == Phase.ACTIVE && code == 1000)
        end(if (expected) null else VolcRealtimeException(VolcRealtimeException.Kind.NETWORK, code.toLong()))
    }

    private fun publish(event: VolcRealtimeEvent) {
        if (phase == Phase.ACTIVE && output.trySend(event).isFailure) {
            // Do not replay stale audio if the consumer has fallen behind.
            end(VolcRealtimeException(VolcRealtimeException.Kind.BACKPRESSURE), cancelSocket = true, dropMedia = true)
        }
    }

    private fun send(frame: String): Boolean {
        val connection = socket
        if (connection != null && connection.queueSize() + frame.length <= MAX_SEND_QUEUE_BYTES && connection.send(frame)) return true
        val kind = if (connection != null && connection.queueSize() + frame.length > MAX_SEND_QUEUE_BYTES) {
            VolcRealtimeException.Kind.BACKPRESSURE
        } else VolcRealtimeException.Kind.NETWORK
        abort(VolcRealtimeException(kind))
        return false
    }

    private fun nextId() = "mika_${++sequence}"

    private fun armCloseWatchdog() {
        if (closeWatchdog != null) return
        closeWatchdog = cleanupScope.launch {
            delay(closeTimeoutMs)
            synchronized(this@VolcRealtimeSession) { end(null, cancelSocket = true) }
        }
    }

    private fun end(error: VolcRealtimeException?, cancelSocket: Boolean = false, dropMedia: Boolean = false) {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        closeWatchdog?.cancel()
        val connection = socket
        socket = null
        if (cancelSocket) connection?.cancel()
        if (dropMedia) discardPending = true
        terminal = error?.let(VolcRealtimeEvent::Failed) ?: VolcRealtimeEvent.Closed
        output.close()
        ready.completeExceptionally(error ?: VolcRealtimeException(VolcRealtimeException.Kind.NETWORK))
        closed.complete(Unit)
    }

    private companion object {
        val ASR_TEXT_FIELDS = listOf("text", "delta", "transcript", "content")
        const val MAX_SEND_QUEUE_BYTES = 32 * 1024 // Well below OkHttp's 16 MiB default WebSocket queue.
        const val MAX_TRANSCRIPT_CHARS = 16_384
        const val MAX_TRACKED_USER_ITEMS = 64
        const val MAX_RETIRED_RESPONSES = 64
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

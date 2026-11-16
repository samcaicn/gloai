package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.selects.select
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** A bounded stream: capture must fail rather than silently drop audio when the network stalls. */
interface StreamingRecorder : Recorder {
    val chunks: ReceiveChannel<ShortArray>
}

internal interface DoubaoSocket {
    val incoming: ReceiveChannel<ByteArray>
    fun send(packet: ByteArray)
    fun close()
}

/** Dedicated client: Ark/provider headers, redirects, keys and retry policies are not reused. */
internal class DoubaoTransport(
    private val client: OkHttpClient = http,
    private val endpoint: String = DoubaoProtocol.ENDPOINT,
) {
    suspend fun connect(credentials: DoubaoCredentials, resource: String): DoubaoSocket {
        require(credentials.valid && resource in DoubaoProtocol.resources)
        val ready = CompletableDeferred<Unit>()
        val messages = Channel<ByteArray>(32)
        val request = Request.Builder().url(endpoint)
            .header("X-Api-Resource-Id", resource)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .apply {
                if (credentials.apiKey.isNotBlank()) header("X-Api-Key", credentials.apiKey)
                else {
                    header("X-Api-App-Key", credentials.appId)
                    header("X-Api-Access-Key", credentials.accessKey)
                }
            }.build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > 1024 * 1024 || messages.trySend(bytes.toByteArray()).isFailure) {
                    messages.close(IOException("ASR response buffer exceeded")); webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.close(IOException("Expected binary ASR response")); webSocket.cancel()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                messages.close(); webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = when (response?.code) {
                    401, 403 -> ModelError.Auth()
                    429 -> ModelError.RateLimited(null)
                    else -> ModelError.Network()
                }
                response?.close()
                ready.completeExceptionally(error)
                messages.close(error)
            }
        })
        try { ready.await() } catch (e: Throwable) { socket.cancel(); messages.cancel(); throw e }
        return object : DoubaoSocket {
            override val incoming = messages
            override fun send(packet: ByteArray) {
                if (socket.queueSize() > 256 * 1024 || !socket.send(packet.toByteString())) throw IOException("ASR send failed")
            }
            override fun close() { socket.cancel(); messages.cancel() }
        }
    }

    companion object {
        private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
    }
}

/** Live capture/upload and receive run concurrently; only the server's last packet finalizes text. */
internal class DoubaoSpeech(
    private val recorder: StreamingRecorder,
    private val connect: suspend () -> DoubaoSocket,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val connectTimeoutMs: Long = 10_000,
    private val finalTimeoutMs: Long = 10_000,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true
    private var work: Job? = null
    private var levels: Job? = null
    private var stopping: Job? = null
    private var released = false

    init {
        if (!recorder.start()) out.finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        else {
            levels = scope.launch { recorder.level.collect { out.send(SpeechEvent.Level(it)) } }
            work = scope.launch { runSession() }
        }
    }

    private suspend fun runSession() {
        var socket: DoubaoSocket? = null
        try {
            withTimeout(75_000) {
                val connection = withTimeout(connectTimeoutMs) { connect() }
                socket = connection
                connection.send(DoubaoProtocol.initial())
                // Do not send audio before initialization was acknowledged.
                val first = withTimeout(connectTimeoutMs) { DoubaoProtocol.response(connection.incoming.receive()) }
                var text = first.text.orEmpty()
                if (first.last) { out.finish(SpeechEvent.Final(text)); return@withTimeout }
                if (first.text != null) out.send(SpeechEvent.Partial("", text))
                coroutineScope {
                    val result = async {
                        for (bytes in connection.incoming) {
                            val response = DoubaoProtocol.response(bytes)
                            response.text?.let {
                                // result_type=full is a replacement snapshot, never an append delta.
                                text = it
                                if (!response.last) out.send(SpeechEvent.Partial("", it))
                            }
                            if (response.last) return@async text
                        }
                        throw IOException("ASR closed before final result")
                    }
                    val audioEnded = CompletableDeferred<Unit>()
                    val sender = launch {
                        val buffer = ShortArray(DoubaoProtocol.CHUNK_SAMPLES)
                        var size = 0
                        var nextPacketNanos = 0L
                        suspend fun sendAudio(pcm: ShortArray, last: Boolean) {
                            val remaining = nextPacketNanos - System.nanoTime()
                            if (remaining > 0) delay((remaining + 999_999) / 1_000_000)
                            connection.send(DoubaoProtocol.audio(pcm, last))
                            nextPacketNanos = System.nanoTime() + 200_000_000L
                        }
                        for (chunk in recorder.chunks) {
                            var offset = 0
                            while (offset < chunk.size) {
                                val count = minOf(buffer.size - size, chunk.size - offset)
                                chunk.copyInto(buffer, size, offset, offset + count)
                                size += count; offset += count
                                if (size == buffer.size) {
                                    sendAudio(buffer, last = false); size = 0
                                }
                            }
                        }
                        sendAudio(buffer.copyOf(size), last = true)
                        audioEnded.complete(Unit)
                    }
                    try {
                        val finalText = select<String> {
                            result.onAwait { it }
                            audioEnded.onAwait { withTimeout(finalTimeoutMs) { result.await() } }
                        }
                        out.finish(SpeechEvent.Final(finalText))
                    } finally { sender.cancelAndJoin() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Network))
        } catch (e: CancellationException) {
            throw e
        } catch (e: DoubaoProtocol.ServiceError) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Provider(ModelError.BadResponse("Doubao ASR code ${e.code}"))))
        } catch (e: ModelError) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Provider(e)))
        } catch (e: IOException) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Network))
        } catch (_: Exception) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Provider(ModelError.BadResponse("Invalid Doubao ASR response"))))
        } finally {
            socket?.close()
            levels?.cancel()
            recorder.cancel()
        }
    }

    @Synchronized private fun converting() {
        if (!released) { released = true; out.send(SpeechEvent.Converting) }
    }

    @Synchronized override fun release() {
        if (released || out.finished || out.cancelled) return
        converting()
        levels?.cancel()
        stopping = scope.launch { recorder.stop() }
    }

    @Synchronized override fun cancel() {
        if (!out.cancel()) return
        work?.cancel(); levels?.cancel(); stopping?.cancel()
        recorder.cancel()
    }
}

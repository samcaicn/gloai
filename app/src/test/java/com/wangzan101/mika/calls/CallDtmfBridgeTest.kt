package com.wangzan101.mika.calls

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class CallDtmfBridgeTest {
    @Test fun `key execution leaves audio responsive and rejects simultaneous requests without sending twice`() = runBlocking {
        val fixture = Fixture()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val digits = mutableListOf<String>()
        val job = launch {
            fixture.bridge.run(fixture.session, "", null, { _, _ -> }, sendDtmf = {
                digits += it; started.complete(Unit); finish.await()
                CallDtmfResult("submitted", it.length, it.length)
            })
        }
        try {
            withTimeout(4_000) {
                fixture.request("key1", "1#")
                started.await()
                fixture.receive("""{"type":"response.output_audio.delta","response_id":"reply","delta":"AQI="}""")
                assertArrayEquals(byteArrayOf(1, 2), fixture.audio.receive())
                fixture.request("key2", "0")
                val busy = fixture.results.receive()
                assertEquals("key2", busy["call_id"]!!.jsonPrimitive.content)
                assertEquals("busy", result(busy)["status"]!!.jsonPrimitive.content)
                finish.complete(Unit)
                val done = fixture.results.receive()
                assertEquals("key1", done["call_id"]!!.jsonPrimitive.content)
                assertEquals("submitted", result(done)["status"]!!.jsonPrimitive.content)
                assertEquals(2, result(done)["sent_digits"]!!.jsonPrimitive.int)
                assertFalse(result(done)["remote_confirmed"]!!.jsonPrimitive.boolean)
                assertFalse(result(done).toString().contains("1#"))
                assertEquals(listOf("1#"), digits)
            }
        } finally { job.cancelAndJoin(); fixture.close() }
    }

    @Test fun `ending the media bridge cancels pending keys and never sends a success result`() = runBlocking {
        val fixture = Fixture()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val job = launch {
            fixture.bridge.run(fixture.session, "", null, { _, _ -> }, sendDtmf = {
                try { started.complete(Unit); awaitCancellation() }
                finally { stopped.complete(Unit) }
            })
        }
        try {
            withTimeout(4_000) {
                fixture.request("pending", "123#")
                started.await()
                job.cancelAndJoin()
                stopped.await()
                assertTrue(fixture.results.tryReceive().isFailure)
                assertTrue(fixture.capture.closed.count == 0L)
            }
        } finally { job.cancelAndJoin(); fixture.close() }
    }

    private fun result(item: JsonObject): JsonObject = Json.parseToJsonElement(
        item["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
    ).jsonObject

    private class Capture : InputStream() {
        val closed = CountDownLatch(1)
        override fun read(): Int {
            check(closed.await(5, TimeUnit.SECONDS))
            throw IOException("closed")
        }
        override fun close() { closed.countDown() }
    }

    private class Fixture {
        val results = Channel<JsonObject>(16)
        val audio = Channel<ByteArray>(16)
        val capture = Capture()
        val session = VolcRealtimeSession(VolcRealtimeConfig(enablePhoneDtmf = true), closeTimeoutMs = 100L)
        private val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://localhost/").build()
            override fun queueSize() = 0L
            override fun send(text: String): Boolean {
                val frame = Json.parseToJsonElement(text).jsonObject
                if (frame["type"]?.jsonPrimitive?.content == "conversation.item.create")
                    frame["items"]!!.jsonArray.forEach { check(results.trySend(it.jsonObject).isSuccess) }
                return true
            }
            override fun send(bytes: ByteString) = true
            override fun close(code: Int, reason: String?) = true
            override fun cancel() = Unit
        }
        val bridge = CallPcmBridge(capture, object : OutputStream() {
            override fun write(value: Int) = error("block only")
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                check(audio.trySend(bytes.copyOfRange(offset, offset + count)).isSuccess)
            }
        }) { true }
        init {
            session.listener.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
            receive("""{"type":"session.created"}""")
            receive("""{"type":"session.updated"}""")
        }
        fun receive(frame: String) = session.listener.onMessage(socket, frame)
        fun request(id: String, digits: String) = receive(buildJsonObject {
            put("type", "response.function_call_arguments.done")
            putJsonArray("items") { addJsonObject {
                put("call_id", id); put("name", "send_dtmf")
                put("arguments", buildJsonObject { put("digits", digits) }.toString())
            } }
        }.toString())
        fun close() { session.close(); capture.close() }
    }
}

package com.wangzan101.mika.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Independent server fixtures following the official binary protocol examples. No credentials/audio recordings. */
internal fun asrResponse(json: String = "{}", flags: Int = 1, sequence: Int = 1, gzip: Boolean = false): ByteArray {
    val raw = json.toByteArray(Charsets.UTF_8)
    val data = if (gzip) ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray() else raw
    val seqBytes = if (flags and 1 != 0) 4 else 0
    return ByteBuffer.allocate(8 + seqBytes + data.size)
        .put(0x11).put((0x90 or flags).toByte()).put(if (gzip) 0x11.toByte() else 0x10.toByte()).put(0)
        .apply { if (seqBytes != 0) putInt(sequence) }.putInt(data.size).put(data).array()
}

internal fun clientPayload(packet: ByteArray): ByteArray {
    assertEquals(packet.size - 8, ByteBuffer.wrap(packet, 4, 4).int)
    return GZIPInputStream(packet.copyOfRange(8, packet.size).inputStream()).use { it.readBytes() }
}

class DoubaoProtocolTest {
    @Test fun initialPacketDeclaresRawPcmAndFullResults() {
        val packet = DoubaoProtocol.initial()
        assertArrayEquals(byteArrayOf(0x11, 0x10, 0x11, 0), packet.copyOf(4))
        val json = Json.parseToJsonElement(clientPayload(packet).toString(Charsets.UTF_8)).jsonObject
        val audio = json.getValue("audio").jsonObject
        assertEquals("pcm", audio.getValue("format").jsonPrimitive.content)
        assertEquals(16000, audio.getValue("rate").jsonPrimitive.int)
        assertEquals(16, audio.getValue("bits").jsonPrimitive.int)
        assertEquals(1, audio.getValue("channel").jsonPrimitive.int)
        assertEquals("full", json.getValue("request").jsonObject.getValue("result_type").jsonPrimitive.content)
        assertFalse(json.containsKey("user"))
    }

    @Test fun audioIsLittleEndianWithBigEndianLengthAndExplicitFinalFlag() {
        val normal = DoubaoProtocol.audio(shortArrayOf(0x1234, -2), false)
        assertArrayEquals(byteArrayOf(0x11, 0x20, 0x01, 0), normal.copyOf(4))
        assertArrayEquals(byteArrayOf(0x34, 0x12, -2, -1), clientPayload(normal))
        val last = DoubaoProtocol.audio(shortArrayOf(), true)
        assertEquals(0x22.toByte(), last[1])
        assertArrayEquals(byteArrayOf(), clientPayload(last))
    }

    @Test fun readsUncompressedAndGzipFullSnapshots() {
        for (zip in listOf(false, true)) {
            assertEquals(DoubaoProtocol.Response("你好", false), DoubaoProtocol.response(asrResponse("""{"result":{"text":"你好"}}""", gzip = zip)))
        }
        assertEquals(DoubaoProtocol.Response("AB", false), DoubaoProtocol.response(asrResponse("""{"result":[{"text":"A"},{"text":"B"}]}""")))
    }

    @Test fun finalBitAndNegativeSequenceBothFinalizeAndAckDoesNotEraseText() {
        for (flags in listOf(2, 3)) assertTrue(DoubaoProtocol.response(asrResponse(flags = flags, sequence = -4)).last)
        assertTrue(DoubaoProtocol.response(asrResponse(sequence = -4)).last)
        assertNull(DoubaoProtocol.response(asrResponse()).text)
        assertEquals("", DoubaoProtocol.response(asrResponse("""{"result":{"text":""}}""")).text)
        assertFalse(DoubaoProtocol.response(asrResponse(flags = 0)).last)
    }

    @Test fun errorFrameAndJsonCodesDoNotExposeServerText() {
        val secret = "test-only-sensitive-error"
        val bytes = secret.toByteArray()
        val frame = ByteBuffer.allocate(12 + bytes.size).put(0x11).put(0xf0.toByte()).put(0x10).put(0)
            .putInt(45000001).putInt(bytes.size).put(bytes).array()
        val error = assertThrows(DoubaoProtocol.ServiceError::class.java) { DoubaoProtocol.response(frame) }
        assertEquals(45000001L, error.code)
        assertFalse(error.message!!.contains(secret))
        assertThrows(DoubaoProtocol.ServiceError::class.java) { DoubaoProtocol.response(asrResponse("""{"code":45000002,"message":"$secret"}""")) }
    }

    @Test fun malformedAndOversizedPacketsAreRejected() {
        val valid = asrResponse("""{"result":{"text":"ok"}}""")
        for (length in 0 until valid.size) assertThrows(Exception::class.java) { DoubaoProtocol.response(valid.copyOf(length)) }
        for ((index, value) in listOf(0 to 0x21, 0 to 0x10, 1 to 0x81, 1 to 0x95, 2 to 0x12)) {
            val bad = valid.copyOf().apply { this[index] = value.toByte() }
            assertThrows(Exception::class.java) { DoubaoProtocol.response(bad) }
        }
        assertThrows(Exception::class.java) { DoubaoProtocol.response(asrResponse(" ".repeat(1024 * 1024 + 1), gzip = true)) }
    }

    @Test fun credentialsHaveExclusiveModesAndRedactedDebugRepresentation() {
        assertTrue(DoubaoCredentials(apiKey = "dummy-key").valid)
        assertTrue(DoubaoCredentials(appId = "dummy-id", accessKey = "dummy-token").valid)
        assertFalse(DoubaoCredentials().valid)
        assertFalse(DoubaoCredentials(apiKey = "dummy-key", appId = "dummy-id").valid)
        assertFalse(DoubaoCredentials(appId = "dummy-id").valid)
        assertFalse(DoubaoCredentials(apiKey = "dummy-key").toString().contains("dummy-key"))
        assertEquals(setOf("volc.seedasr.sauc.duration", "volc.seedasr.sauc.concurrent"), DoubaoProtocol.resources)
    }
}

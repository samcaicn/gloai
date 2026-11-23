package com.wangzan101.mika.record

import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.trace.TraceStore
import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException

class OtlpSenderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun validatesEndpointAndHeaderBeforeSending() {
        for (url in listOf("https://example.com/v1/traces", "http://localhost:4318/v1/traces",
            "http://192.168.1.9/v1/traces", "http://[::1]:4318/v1/traces", "http://collector.local/v1/traces")) {
            assertNotNull(url, OtlpSender.endpoint(url))
        }
        for (url in listOf("http://example.com/v1/traces", "http://8.8.8.8", "https://user:password@example.com", "ftp://localhost", "https://host/#fragment")) {
            assertNull(url, OtlpSender.endpoint(url))
        }
        assertTrue(OtlpSender.validHeader("Authorization", "Bearer test"))
        assertTrue(OtlpSender.validHeader("X-Api-Key", "test"))
        assertTrue(OtlpSender.validHeader("", ""))
        for (name in listOf("Host", "Content-Type", "content-LENGTH", " bad", "Connection")) {
            assertFalse(name, OtlpSender.validHeader(name, "test"))
        }
        assertFalse(OtlpSender.validHeader("Authorization", "x\r\nX-Test: y"))
    }

    @Test fun sendsTheExactLocalMetadataAndDistinguishesReceiptsWithoutForwardingCredentials() = runBlocking {
        val store = TraceStore(temp.newFolder())
        val trace = store.start("short")!!
        trace.event(RunEvent.TaskEnded(0, "succeeded", "private answer"))
        store.finish(trace)
        val archive = store.archive("short")!!
        val expected = ByteArrayOutputStream().also { archive.save(it, false) }.toByteArray()
        MockWebServer().use { receiver ->
            val url = receiver.url("/v1/traces").toString()
            suspend fun send() = OtlpSender.send(archive, url, "X-Api-Key", "test-only")
            receiver.enqueue(MockResponse().setHeader("Content-Type", "application/x-protobuf"))
            assertEquals(OtlpReceipt.Accepted, send())
            val request = receiver.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("application/x-protobuf", request.getHeader("Content-Type"))
            assertEquals("test-only", request.getHeader("X-Api-Key"))
            assertArrayEquals(expected, request.body.readByteArray())
            for (count in listOf(0L, 2L)) {
                val ack = ExportTraceServiceResponse.newBuilder().setPartialSuccess(
                    ExportTracePartialSuccess.newBuilder().setRejectedSpans(count).setErrorMessage("receiver detail")).build()
                receiver.enqueue(MockResponse().setHeader("Content-Type", "application/x-protobuf").setBody(Buffer().write(ack.toByteArray())))
                assertEquals(if (count == 0L) OtlpReceipt.AcceptedWithWarning else OtlpReceipt.Rejected(count), send())
            }
            val beforeRetry = receiver.requestCount
            receiver.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            assertEquals(OtlpReceipt.HttpError(503), send())
            assertEquals(beforeRetry + 1, receiver.requestCount)
            receiver.enqueue(MockResponse().setResponseCode(401))
            assertEquals(OtlpReceipt.HttpError(401), send())
            MockWebServer().use { redirect ->
                receiver.enqueue(MockResponse().setResponseCode(307).setHeader("Location", redirect.url("/elsewhere")))
                assertEquals(OtlpReceipt.HttpError(307), send())
                assertEquals(0, redirect.requestCount)
            }
            for (invalid in listOf(
                MockResponse(),
                MockResponse().setHeader("Content-Type", "text/html"),
                MockResponse().setHeader("Content-Type", "text/html").setBody("OK"),
                MockResponse().setHeader("Content-Type", "application/x-protobuf").setBody("not an acknowledgement"),
            )) {
                receiver.enqueue(invalid)
                try { send(); fail("invalid receiver response") } catch (_: IOException) {}
            }
            assertArrayEquals(expected, ByteArrayOutputStream().also { archive.save(it, false) }.toByteArray())
        }
    }
}

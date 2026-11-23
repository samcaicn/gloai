package com.wangzan101.mika.core.model.decision

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.model.usage.RunUsageTally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SystemOneDecisionModelTest {
    private val server = MockWebServer()
    private val clients = mutableListOf<OkHttpClient>()
    private val options = linkedMapOf("open" to "Open the app", "chat" to "Answer in chat", "clarify" to "Ask for missing information")
    private val state = Json.parseToJsonElement("""{"request":"Open settings","context":[]}""")
    private val key = "test-private-api-key"

    @Before fun start() { server.start() }
    @After fun stop() {
        clients.forEach { it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll() }
        server.shutdown()
    }

    private fun model(path: String = "/v1/systemone", model: String = "jev-1.14.0", http: OkHttpClient = OkHttpClient()): SystemOneDecisionModel {
        clients += http
        return SystemOneDecisionModel(server.url(path).toString(), key, model, http)
    }

    private fun response(extra: String = "", applicability: String = "") = """{
        "model":"jev-1.14.0",
        "answers":{
          "choice":{"type":"choice","choice":"open","probabilities":{"open":0.9,"chat":0.06,"clarify":0.04},"confidence":0.85}$applicability
        },
        "usage":{"input_tokens":120,"output_tokens":35}$extra
    }"""

    private fun enqueue(body: String, status: Int = 200) = server.enqueue(
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body),
    )

    private suspend fun failure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected the decision to fail")
    } catch (error: Exception) {
        error
    }

    private fun noSecrets(error: Throwable) {
        assertFalse(error.stackTraceToString().contains(key))
        assertFalse(error.stackTraceToString().contains("private-request-body"))
    }

    @Test fun sendsNativeShapeAndUsesChosenProbabilityInsteadOfConfidence() = runBlocking {
        enqueue(response())
        val answer = model().choose(state, "Classify the request", options, null)
        assertEquals(DecisionChoice("open", 0.9), answer)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/systemone", request.path)
        assertEquals("Bearer $key", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("model", "state", "questions"), body.keys)
        assertEquals("jev-1.14.0", body["model"]!!.jsonPrimitive.content)
        assertEquals(state, body["state"])
        val questions = body["questions"]!!.jsonObject
        assertEquals(setOf("choice"), questions.keys)
        assertEquals("choice", questions["choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(options, questions["choice"]!!.jsonObject["criteria"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
    }

    @Test fun acceptsOpenRouterExtrasAndPairsChoiceWithApplicability() = runBlocking {
        enqueue(response(
            extra = ",\"id\":\"gen-test\",\"provider\":\"TypeSafe\",\"future_field\":{}",
            applicability = ",\"applicable\":{\"type\":\"noul\",\"noul\":0.96}",
        ).replace("\"output_tokens\":35", "\"output_tokens\":35,\"cost\":0.000001"))
        val answer = model("/api/v1/systemone", "typesafe/jev-1.14")
            .choose(state, "Pick a next step", options, "Does any candidate fit?")
        assertEquals(DecisionChoice("open", 0.9, 0.96), answer)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/systemone", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("typesafe/jev-1.14", body["model"]!!.jsonPrimitive.content)
        val question = body["questions"]!!.jsonObject["applicable"]!!.jsonObject
        assertEquals("noul", question["type"]!!.jsonPrimitive.content)
        assertEquals("Does any candidate fit?", question["instructions"]!!.jsonPrimitive.content)
    }

    @Test fun rejectsChoicesOutsideTheSuppliedSetAndInvalidDistributions() = runBlocking {
        val valid = response()
        listOf(
            valid.replace("\"choice\":\"open\"", "\"choice\":\"invented\""),
            valid.replace("\"type\":\"choice\"", "\"type\":\"noul\""),
            valid.replace("\"open\":0.9", "\"open\":1.2"),
            valid.replace("\"open\":0.9", "\"open\":-0.2"),
            valid.replace("\"open\":0.9", "\"open\":1e999"),
            valid.replace("\"open\":0.9", "\"open\":\"0.9\""),
            valid.replace("\"open\":0.9", "\"open\":null"),
            valid.replace("\"open\":0.9", "\"open\":0.1"),
            valid.replace("\"choice\":\"open\"", "\"choice\":\"chat\""),
            valid.replace(",\"clarify\":0.04", ""),
            valid.replace("\"clarify\":0.04", "\"invented\":0.04"),
            """{"answers":{},"private":"private-request-body"}""",
            "private-request-body: not json $key",
        ).forEach { body ->
            enqueue(body)
            val error = failure { model().choose(state, "Choose", options, null) }
            assertTrue(error.javaClass.name, error is DecisionError.InvalidResponse)
            noSecrets(error)
        }
    }

    @Test fun requiredApplicabilityMustBePresentAndAValidNoul() = runBlocking {
        listOf(
            "",
            ",\"applicable\":{\"type\":\"choice\",\"noul\":0.9}",
            ",\"applicable\":{\"type\":\"noul\",\"noul\":\"0.9\"}",
            ",\"applicable\":{\"type\":\"noul\",\"noul\":1.2}",
            ",\"applicable\":{\"type\":\"noul\",\"noul\":1e999}",
        ).forEach { extra ->
            enqueue(response(applicability = extra))
            assertTrue(failure { model().choose(state, "Choose", options, "Any applicable option?") } is DecisionError.InvalidResponse)
        }
    }

    @Test fun httpFailuresExposeStatusWithoutProviderContentAndNeverRetry() = runBlocking {
        val decision = model()
        for (status in listOf(401, 403, 402, 429, 524, 529)) {
            enqueue("""{"error":{"message":"private-request-body $key"}}""", status)
            val error = failure { decision.choose(state, "Choose", options, null) }
            when (status) {
                401, 403 -> assertTrue(error is ModelError.Auth)
                402 -> assertTrue(error is ModelError.QuotaExceeded)
                else -> assertEquals(status, (error as DecisionError.Http).status)
            }
            noSecrets(error)
        }
        assertEquals(6, server.requestCount)
    }

    @Test fun doesNotFollowRedirectsWithCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/unexpected")))
        val error = failure { model().choose(state, "Choose", options, null) }
        assertEquals(307, (error as DecisionError.Http).status)
        assertEquals(1, server.requestCount)
    }

    @Test fun preservesTheConfiguredHttpProxy() = runBlocking {
        enqueue(response())
        val http = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(server.hostName, server.port)))
            .build().also(clients::add)
        val endpoint = "http://127.0.0.2:8123/v1/systemone"
        val decision = SystemOneDecisionModel(endpoint, key, "jev-1.14.0", http)
        assertEquals("open", decision.choose(state, "Choose", options, null).choice)
        assertEquals("POST $endpoint HTTP/1.1", server.takeRequest(2, TimeUnit.SECONDS)!!.requestLine)
    }

    @Test fun timeoutIsBoundedAndDoesNotExposeTransportDetails() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().callTimeout(200, TimeUnit.MILLISECONDS).build()
        val start = System.nanoTime()
        val error = failure { model(http = http).choose(state, "Choose", options, null) }
        assertTrue(error is DecisionError.Network)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000)
        noSecrets(error)
        assertEquals(1, server.requestCount)
    }

    @Test fun coroutineCancellationPropagatesAndCancelsTheHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val stopped = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) { stopped.countDown() }
        }).build()
        val decision = model(http = http)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { decision.choose(state, "Choose", options, null) }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertTrue(failure { pending.await() } is CancellationException)
        assertTrue("Cancelled coroutine left its HTTP call running", stopped.await(2, TimeUnit.SECONDS))
    }

    @Test fun countsDecisionTokensAndFailedHttpAttemptsInRunUsage() = runBlocking {
        val tally = RunUsageTally()
        enqueue(response())
        enqueue("private-request-body $key", 401)
        withContext(tally) {
            val decision = model()
            decision.choose(state, "Choose", options, null)
            failure { decision.choose(state, "Choose", options, null) }
        }
        val event = tally.event(0)
        assertEquals(2, event.calls)
        assertEquals(120L, event.inputTokens)
        assertEquals(35L, event.outputTokens)
        assertEquals(2, event.httpCalls)
    }

    @Test fun usageCallbackReceivesReportedTokensAndCannotBreakTheDecision() = runBlocking {
        val received = mutableListOf<Pair<Usage?, Int>>()
        val http = OkHttpClient().also(clients::add)
        val decision = SystemOneDecisionModel(server.url("/v1/systemone").toString(), key, "jev-1.14.0", http) { usage, attempts ->
            received += usage to attempts
            throw IllegalStateException("Usage store unavailable")
        }
        enqueue(response())
        assertEquals("open", decision.choose(state, "Choose", options, null).choice)
        assertEquals(listOf(Usage(120, 35) to 1), received)
    }

    @Test fun invalidConfigurationDoesNotEchoKeysOrSendARequest() = runBlocking {
        val invalid = listOf(key + "\nX-Header: private-request-body", "")
        invalid.forEach { value ->
            val error = failure { SystemOneDecisionModel(server.url("/").toString(), value, "jev-1.14.0") }
            assertTrue(error is DecisionError.InvalidRequest)
            noSecrets(error)
            assertNull(error.cause)
        }
        val error = failure { model().choose(JsonPrimitive("hello"), "Choose", emptyMap(), null) }
        assertTrue(error is DecisionError.InvalidRequest)
        assertEquals(0, server.requestCount)
    }
}

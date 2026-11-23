package com.wangzan101.mika.core.model.decision

import com.wangzan101.mika.core.agent.IntentGate
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.learn.replay.Route
import com.wangzan101.mika.core.learn.replay.RouteChoice
import com.wangzan101.mika.core.learn.replay.RouteMatcher
import com.wangzan101.mika.core.learn.replay.RouteStore
import com.wangzan101.mika.core.model.usage.RunUsageTally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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

class GoogleDecisionModelTest {
    private val server = MockWebServer()
    private val clients = mutableListOf<OkHttpClient>()
    private val options = linkedMapOf(
        "open" to "Open the app",
        "chat" to "Answer in chat",
        "clarify" to "Ask for missing information",
    )
    private val state = Json.parseToJsonElement("""{"request":"Open settings","context":[]}""")
    private val key = "AIzaSy-test-google-decision-key"

    @Before fun start() {
        server.start()
    }

    @After fun stop() {
        clients.forEach {
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
        server.shutdown()
    }

    private fun model(
        path: String = "/v1beta/decisions",
        model: String = DecisionConfig.GOOGLE_MODEL,
        http: OkHttpClient = OkHttpClient(),
        onUsage: (Usage?, Int) -> Unit = { _, _ -> },
    ): GoogleDecisionModel {
        clients += http
        return GoogleDecisionModel(server.url(path).toString(), key, model, http, onUsage)
    }

    private fun choiceResponse(extra: String = "", applicability: String = "") = """{
        "model":"gemini-decision-1",
        "answers":{
          "choice":{"type":"choice","choice":"open","probabilities":{"open":0.9,"chat":0.06,"clarify":0.04},"confidence":0.85}$applicability
        },
        "usage":{"total_input_tokens":140,"total_output_tokens":20}$extra
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

    @Test fun sendsGoogleApiKeyHeaderAndNativeInputQuestionsShape() = runBlocking {
        enqueue(choiceResponse())
        val answer = model().choose(state, "Classify the request", options, null)
        assertEquals(DecisionChoice("open", 0.9), answer)

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1beta/decisions", request.path)
        assertEquals(key, request.getHeader("x-goog-api-key"))
        assertNull(request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("model", "input", "questions"), body.keys)
        assertEquals("gemini-decision-1", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["input"]!!.jsonPrimitive.isString)
        assertEquals(state.toString(), body["input"]!!.jsonPrimitive.content)

        val questions = body["questions"]!!.jsonObject
        assertEquals(setOf("choice"), questions.keys)
        val choice = questions["choice"]!!.jsonObject
        assertEquals("choice", choice["type"]!!.jsonPrimitive.content)
        assertEquals("Classify the request", choice["instructions"]!!.jsonPrimitive.content)
        assertEquals(options, choice["criteria"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
    }

    @Test fun stringInputIsUnwrappedAndApplicabilityUsesNoul() = runBlocking {
        enqueue(choiceResponse(applicability = ""","applicable":{"type":"noul","noul":0.96,"confidence":0.9}"""))
        val answer = model().choose(JsonPrimitive("Open notes"), "Pick a route", options, "Is any option applicable?")
        assertEquals(DecisionChoice("open", 0.9, 0.96), answer)

        val body = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        assertEquals("Open notes", body["input"]!!.jsonPrimitive.content)
        val applicable = body["questions"]!!.jsonObject["applicable"]!!.jsonObject
        assertEquals("noul", applicable["type"]!!.jsonPrimitive.content)
        assertEquals("Is any option applicable?", applicable["instructions"]!!.jsonPrimitive.content)
    }

    @Test fun chooseAllowsSingleOptionWhileEvaluateRequiresTwoCriteriaOrLevels() = runBlocking {
        enqueue(
            """{
                "answers":{"choice":{"type":"choice","choice":"only","probabilities":{"only":1.0}}},
                "usage":{"total_input_tokens":50}
            }""",
        )
        val single = model().choose(state, "Confirm", mapOf("only" to "Only option"), null)
        assertEquals(DecisionChoice("only", 1.0), single)

        assertTrue(
            failure {
                model().evaluate(state, mapOf("c" to DecisionQuestion.Choice("Pick", mapOf("only" to "Only"))))
            } is DecisionError.InvalidRequest,
        )
        assertTrue(
            failure {
                model().evaluate(state, mapOf("s" to DecisionQuestion.Score("Rate", listOf("only"))))
            } is DecisionError.InvalidRequest,
        )
        assertTrue(
            failure {
                model().evaluate(state, mapOf("s" to DecisionQuestion.Score("Rate", listOf("a", "a"))))
            } is DecisionError.InvalidRequest,
        )
        assertTrue(failure { model().evaluate(state, emptyMap()) } is DecisionError.InvalidRequest)

        val tooMany = (1..1001).associate { "q$it" to DecisionQuestion.Noul("Question $it?") }
        assertTrue(failure { model().evaluate(state, tooMany) } is DecisionError.InvalidRequest)
        assertEquals(1, server.requestCount)
    }

    @Test fun evaluatesNoulChoiceAndScoreQuestionsWithCalibratedProbabilitiesAndConfidence() = runBlocking {
        enqueue(
            """{
                "answers":{
                    "safe":{"type":"noul","noul":0.98,"confidence":0.95},
                    "route":{"type":"choice","choice":"read","probabilities":{"discuss":0.05,"read":0.85,"act":0.10},"confidence":0.91},
                    "risk":{"type":"score","score":"low","probabilities":{"low":0.88,"medium":0.10,"high":0.02},"confidence":0.89}
                },
                "usage":{"total_input_tokens":200,"total_output_tokens":15}
            }""",
        )
        val questions = linkedMapOf(
            "safe" to DecisionQuestion.Noul("Is this request safe?"),
            "route" to DecisionQuestion.Choice(
                "Choose intent",
                linkedMapOf("discuss" to "Discuss", "read" to "Read", "act" to "Act"),
            ),
            "risk" to DecisionQuestion.Score("Rate risk", listOf("low", "medium", "high")),
        )
        val answers = model().evaluate(JsonPrimitive("Check my calendar"), questions)

        assertEquals(DecisionAnswer.Noul(0.98, 0.95), answers["safe"])
        assertEquals(
            DecisionAnswer.Choice("read", mapOf("discuss" to 0.05, "read" to 0.85, "act" to 0.10), 0.91),
            answers["route"],
        )
        assertEquals(
            DecisionAnswer.Score("low", mapOf("low" to 0.88, "medium" to 0.10, "high" to 0.02), 0.89),
            answers["risk"],
        )

        val reqBody = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        val scoreLevels = reqBody["questions"]!!.jsonObject["risk"]!!.jsonObject["levels"]!!.jsonArray
        assertEquals(listOf("low", "medium", "high"), scoreLevels.map { it.jsonObject["label"]!!.jsonPrimitive.content })
    }

    @Test fun scoreSupportsLevelsArrayLevelsObjectAndCriteriaResponses() = runBlocking {
        val levels = listOf("low", "high")
        val question = mapOf("risk" to DecisionQuestion.Score("Rate risk", levels))
        val bodies = listOf(
            """{"answers":{"risk":{"type":"score","score":"low","levels":[{"label":"low","probability":0.8},{"label":"high","probability":0.2}]}}}""",
            """{"answers":{"risk":{"type":"score","score":"low","levels":{"low":0.8,"high":0.2}}}}""",
            """{"answers":{"risk":{"type":"score","score":"low","criteria":{"low":0.8,"high":0.2}}}}""",
        )
        for (body in bodies) {
            enqueue(body)
            val result = model().evaluate(state, question)
            assertEquals(DecisionAnswer.Score("low", mapOf("low" to 0.8, "high" to 0.2)), result["risk"])
        }
    }

    @Test fun evaluatesBatchesGreaterThanTenAndUpToOneThousandQuestions() = runBlocking {
        val fifteenQuestions = (1..15).associate { i -> "q$i" to DecisionQuestion.Noul("Question $i?") }
        val fifteenAnswers = (1..15).joinToString(",") { i ->
            """"q$i":{"type":"noul","noul":0.9,"confidence":0.85}"""
        }
        enqueue("""{"answers":{$fifteenAnswers},"usage":{"total_input_tokens":300,"total_output_tokens":0}}""")
        val fifteenResult = model().evaluate(JsonPrimitive("Batch input"), fifteenQuestions)
        assertEquals(15, fifteenResult.size)
        assertEquals(DecisionAnswer.Noul(0.9, 0.85), fifteenResult["q15"])

        val thousandQuestions = (1..1000).associate { i -> "q$i" to DecisionQuestion.Noul("Question $i?") }
        val thousandAnswers = (1..1000).joinToString(",") { i ->
            """"q$i":{"type":"noul","noul":0.75}"""
        }
        enqueue("""{"answers":{$thousandAnswers},"usage":{"total_input_tokens":5000}}""")
        val thousandResult = model().evaluate(JsonPrimitive("Max batch"), thousandQuestions)
        assertEquals(1000, thousandResult.size)
        assertEquals(DecisionAnswer.Noul(0.75, null), thousandResult["q1"])
        assertEquals(DecisionAnswer.Noul(0.75, null), thousandResult["q1000"])
    }

    @Test fun rejectsInvalidResponsesWithoutLeakingSecrets() = runBlocking {
        val valid = choiceResponse()
        val invalidBodies = listOf(
            valid.replace(""""choice":"open"""", """"choice":"invented""""),
            valid.replace(""""type":"choice"""", """"type":"noul""""),
            valid.replace(""""open":0.9""", """"open":1.2"""),
            valid.replace(""""open":0.9""", """"open":-0.1"""),
            valid.replace(""""open":0.9""", """"open":1e999"""),
            valid.replace(""""open":0.9""", """"open":"0.9""""),
            valid.replace(""""open":0.9""", """"open":null"""),
            valid.replace(""""open":0.9""", """"open":0.1"""),
            valid.replace(""""choice":"open"""", """"choice":"chat""""),
            valid.replace(""","clarify":0.04""", ""),
            valid.replace(""""clarify":0.04""", """"invented":0.04"""),
            valid.replace(""""confidence":0.85""", """"confidence":1.2"""),
            valid.replace(""""confidence":0.85""", """"confidence":-0.01"""),
            valid.replace(""""confidence":0.85""", """"confidence":"0.85""""),
            valid.replace(""""confidence":0.85""", """"confidence":1e999"""),
            """{"answers":{},"private":"private-request-body"}""",
            "private-request-body: not json $key",
        )
        for (body in invalidBodies) {
            enqueue(body)
            val error = failure { model().choose(state, "Choose", options, null) }
            assertTrue(error.javaClass.name, error is DecisionError.InvalidResponse)
            noSecrets(error)
        }
    }

    @Test fun rejectsAiPlatformEndpointsAndInvalidCredentialsWithoutRequests() = runBlocking {
        val aiPlatformEndpoints = listOf(
            "https://aiplatform.googleapis.com/v1/decisions",
            "https://us-central1-aiplatform.googleapis.com/v1/projects/p/locations/us-central1/publishers/google/models/gemini-decision-1:decide",
        )
        for (endpoint in aiPlatformEndpoints) {
            val error = failure { GoogleDecisionModel(endpoint, key) }
            assertTrue(error is DecisionError.InvalidRequest)
            assertNull(error.cause)
            noSecrets(error)
        }
        for (badKey in listOf("", "   ", "$key\nX-Injected: private-request-body", "bad key")) {
            val error = failure { GoogleDecisionModel(server.url("/v1beta/decisions").toString(), badKey) }
            assertTrue(error is DecisionError.InvalidRequest)
            noSecrets(error)
        }
        val blankModelError = failure { GoogleDecisionModel(server.url("/v1beta/decisions").toString(), key, " ") }
        assertTrue(blankModelError is DecisionError.InvalidRequest)
        assertEquals(0, server.requestCount)
    }

    @Test fun mapsHttpFailuresWithoutRetryingOrFollowingRedirects() = runBlocking {
        val decision = model()
        for (status in listOf(401, 403, 402, 429, 500, 503)) {
            enqueue("""{"error":{"message":"private-request-body $key"}}""", status)
            val error = failure { decision.choose(state, "Choose", options, null) }
            when (status) {
                401, 403 -> assertTrue(error is ModelError.Auth)
                402 -> assertTrue(error is ModelError.QuotaExceeded)
                else -> assertEquals(status, (error as DecisionError.Http).status)
            }
            noSecrets(error)
        }
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/redirected")))
        val redirectError = failure { decision.choose(state, "Choose", options, null) }
        assertEquals(307, (redirectError as DecisionError.Http).status)
        assertEquals(7, server.requestCount)
    }

    @Test fun recordsGoogleTokenUsageFallbacksAndRunUsageTally() = runBlocking {
        val received = mutableListOf<Pair<Usage?, Int>>()
        val decision = model { usage, attempts ->
            received += usage to attempts
            if (attempts > 0 && usage != null && usage.inputTokens == 140) {
                throw IllegalStateException("usage callback failure ignored")
            }
        }
        enqueue(choiceResponse())
        enqueue("""{"answers":{"choice":{"type":"choice","choice":"open","probabilities":{"open":1.0,"chat":0.0,"clarify":0.0}}},"usage":{"total_input_tokens":80}}""")
        enqueue("""{"answers":{"choice":{"type":"choice","choice":"open","probabilities":{"open":1.0,"chat":0.0,"clarify":0.0}}},"usage":{"input_tokens":60,"output_tokens":10}}""")
        enqueue("private-request-body $key", 401)

        val tally = RunUsageTally()
        withContext(tally) {
            decision.choose(state, "Choose", options, null)
            decision.choose(state, "Choose", options, null)
            decision.choose(state, "Choose", options, null)
            failure { decision.choose(state, "Choose", options, null) }
        }

        assertEquals(
            listOf(
                Usage(140, 20) to 1,
                Usage(80, 0) to 1,
                Usage(60, 10) to 1,
                null to 1,
            ),
            received,
        )
        val event = tally.event(0)
        assertEquals(4, event.calls)
        assertEquals(280L, event.inputTokens)
        assertEquals(30L, event.outputTokens)
        assertEquals(4, event.httpCalls)
    }

    @Test fun preservesHttpProxyBoundsTimeoutAndPropagatesCancellation() = runBlocking {
        enqueue(choiceResponse())
        val proxyHttp = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(server.hostName, server.port)))
            .build().also(clients::add)
        val proxied = GoogleDecisionModel("http://127.0.0.2:8123/v1beta/decisions", key, DecisionConfig.GOOGLE_MODEL, proxyHttp)
        assertEquals("open", proxied.choose(state, "Choose", options, null).choice)
        assertEquals("POST http://127.0.0.2:8123/v1beta/decisions HTTP/1.1", server.takeRequest(2, TimeUnit.SECONDS)!!.requestLine)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortTimeout = OkHttpClient.Builder().callTimeout(200, TimeUnit.MILLISECONDS).build()
        val start = System.nanoTime()
        val timeoutError = failure { model(http = shortTimeout).choose(state, "Choose", options, null) }
        assertTrue(timeoutError is DecisionError.Network)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000)
        noSecrets(timeoutError)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val stopped = CountDownLatch(1)
        val listeningHttp = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) {
                stopped.countDown()
            }
        }).build()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            model(http = listeningHttp).choose(state, "Choose", options, null)
        }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertTrue(failure { pending.await() } is CancellationException)
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
    }

    @Test fun integratesWithIntentGateAndFallsBackOnFailure() = runBlocking {
        enqueue(
            """{
                "answers":{"choice":{"type":"choice","choice":"DISCUSS","probabilities":{"DISCUSS":0.92,"READ":0.05,"ACT":0.03},"confidence":0.9}},
                "usage":{"total_input_tokens":90,"total_output_tokens":0}
            }""",
        )
        val untouchedJudge = StubChatModel()
        val traces = mutableListOf<String>()
        val gate = IntentGate(untouchedJudge, decision = model(), onDecision = traces::add)
        assertEquals(IntentGate.Intent.Discuss, gate.assess("Can we plan this for later?", listOf(Turn("Hello", "Hi", "succeeded"))))
        assertEquals(0, untouchedJudge.calls)
        assertEquals(listOf("selected:Discuss"), traces)

        enqueue("""{"error":"upstream"}""", 500)
        val fallbackJudge = StubChatModel("READ")
        val fallbackTraces = mutableListOf<String>()
        val fallbackGate = IntentGate(fallbackJudge, decision = model(), onDecision = fallbackTraces::add)
        assertEquals(IntentGate.Intent.Read, fallbackGate.assess("Check order status", emptyList()))
        assertEquals(1, fallbackJudge.calls)
        assertEquals(listOf("fallback"), fallbackTraces)
    }

    @Test fun integratesWithRouteMatcherAndFallsBackOnFailure() = runBlocking {
        val route = Route("memo", "Write {1} in notes", 1, listOf("com.notes"), emptyMap(), emptyList(), lastUsedMs = 0)
        val goal = "Write buy milk in notes"
        enqueue(
            """{
                "answers":{
                    "choice":{"type":"choice","choice":"route_0","probabilities":{"route_0":0.95,"skip":0.05},"confidence":0.93},
                    "applicable":{"type":"noul","noul":0.94}
                },
                "usage":{"total_input_tokens":110,"total_output_tokens":0}
            }""",
        )
        val untouchedModel = StubChatModel()
        val traces = mutableListOf<String>()
        val chosen = RouteMatcher(untouchedModel, InMemoryRoutes(route), null, model(), traces::add).choose(goal)
        assertEquals(RouteChoice(route, listOf("buy milk")), chosen)
        assertEquals(0, untouchedModel.calls)
        assertEquals(listOf("selected"), traces)

        enqueue(
            """{
                "answers":{
                    "choice":{"type":"choice","choice":"route_0","probabilities":{"route_0":0.95,"skip":0.05}},
                    "applicable":{"type":"noul","noul":0.30}
                }
            }""",
        )
        val fallbackModel = StubChatModel("""{"route":1,"slots":["buy milk"]}""")
        val fallbackTraces = mutableListOf<String>()
        val fallbackChosen = RouteMatcher(fallbackModel, InMemoryRoutes(route), null, model(), fallbackTraces::add).choose(goal)
        assertEquals(RouteChoice(route, listOf("buy milk")), fallbackChosen)
        assertEquals(1, fallbackModel.calls)
        assertEquals(listOf("fallback"), fallbackTraces)
    }

    private class StubChatModel(private val responseText: String = "") : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
        var calls = 0

        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls++
            return ModelReply(listOf(Part.Text(responseText)), StopReason.EndTurn, null)
        }
    }

    private class InMemoryRoutes(vararg initial: Route) : RouteStore {
        private val routes = linkedMapOf<String, Route>().apply { initial.forEach { put(it.id, it) } }
        override fun all(): List<Route> = routes.values.toList()
        override fun put(r: Route) { routes[r.id] = r }
        override fun remove(id: String) { routes.remove(id) }
        override fun clear() = routes.clear()
        override fun count(): Int = routes.size
    }
}

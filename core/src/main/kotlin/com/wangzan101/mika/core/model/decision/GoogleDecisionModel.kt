package com.wangzan101.mika.core.model.decision

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelHttp
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.model.usage.RunUsageTally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Typed question specification for the Google Gemini Decisions API. */
sealed interface DecisionQuestion {
    val instructions: String

    data class Noul(override val instructions: String) : DecisionQuestion

    data class Choice(
        override val instructions: String,
        val criteria: Map<String, String>,
    ) : DecisionQuestion

    data class Score(
        override val instructions: String,
        val levels: List<String>,
    ) : DecisionQuestion
}

/** Calibrated answer returned by the Google Gemini Decisions API. */
sealed interface DecisionAnswer {
    val confidence: Double?

    data class Noul(
        val noul: Double,
        override val confidence: Double? = null,
    ) : DecisionAnswer

    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        override val confidence: Double? = null,
    ) : DecisionAnswer

    data class Score(
        val score: String,
        val probabilities: Map<String, Double>,
        override val confidence: Double? = null,
    ) : DecisionAnswer
}

/** Gemini Developer API (`gemini-decision-1`) client supporting native batched decision evaluation. */
class GoogleDecisionModel(
    endpoint: String = DecisionConfig.GOOGLE_ENDPOINT,
    apiKey: String,
    private val model: String = DecisionConfig.GOOGLE_MODEL,
    http: OkHttpClient = NetProxy.shared.client(),
    private val onUsage: (Usage?, Int) -> Unit = { _, _ -> },
) : DecisionModel {
    private val url = DecisionConfig.endpointUrl(endpoint)
        ?.takeUnless { DecisionConfig.isAiPlatformEndpoint(endpoint) || it.host.endsWith("aiplatform.googleapis.com") }
        ?: throw DecisionError.InvalidRequest()
    private val modelName = model.trim()
    private val key = apiKey.trim().also {
        if (it.isEmpty() || it.any { ch -> ch.code !in 33..126 } ||
            modelName.isEmpty() || modelName.any { ch -> ch.isISOControl() }
        ) {
            throw DecisionError.InvalidRequest()
        }
    }
    private val client = http.newBuilder()
        .callTimeout(http.callTimeoutMillis.takeIf { it in 1..TIMEOUT_MS }?.toLong() ?: TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun choose(
        state: JsonElement,
        instructions: String,
        options: Map<String, String>,
        applicability: String?,
    ): DecisionChoice {
        if (applicability?.isBlank() == true) throw DecisionError.InvalidRequest()
        val questions = buildMap {
            put("choice", DecisionQuestion.Choice(instructions, options))
            if (applicability != null) put("applicable", DecisionQuestion.Noul(applicability))
        }
        val answers = execute(state, questions, minCriteria = 1)
        val choice = answers["choice"] as? DecisionAnswer.Choice ?: throw DecisionError.InvalidResponse()
        val applicable = if (applicability != null) {
            (answers["applicable"] as? DecisionAnswer.Noul)?.noul ?: throw DecisionError.InvalidResponse()
        } else null
        return DecisionChoice(choice.choice, choice.probabilities.getValue(choice.choice), applicable)
    }

    suspend fun evaluate(
        input: JsonElement,
        questions: Map<String, DecisionQuestion>,
    ): Map<String, DecisionAnswer> = execute(input, questions, minCriteria = 2)

    private suspend fun execute(
        input: JsonElement,
        questions: Map<String, DecisionQuestion>,
        minCriteria: Int,
    ): Map<String, DecisionAnswer> {
        val context = currentCoroutineContext()
        var issued = false
        var usage: Usage? = null
        try {
            context.ensureActive()
            validateQuestions(questions, minCriteria)
            val payload = buildPayload(input, questions)
            val request = Request.Builder().url(url)
                .header("x-goog-api-key", key)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            issued = true
            val reply = ModelHttp.send(client, request)
            if (!reply.ok) throw when (reply.status) {
                401, 403 -> ModelError.Auth()
                402 -> ModelError.QuotaExceeded()
                else -> DecisionError.Http(reply.status)
            }
            val root = try { Json.parseToJsonElement(reply.body) as? JsonObject }
                catch (_: Exception) { null }
                ?: throw DecisionError.InvalidResponse()
            usage = usage(root)
            return parseAnswers(root, questions)
        } catch (error: CancellationException) {
            throw error
        } catch (error: DecisionError) {
            throw error
        } catch (error: ModelError.Auth) {
            throw error
        } catch (error: ModelError.QuotaExceeded) {
            throw error
        } catch (error: ModelError.Proxy) {
            throw ModelError.Proxy(error.failure)
        } catch (_: Exception) {
            throw DecisionError.Network()
        } finally {
            val attempts = if (issued) 1 else 0
            context[RunUsageTally]?.add(usage, httpAttempts = attempts)
            runCatching { onUsage(usage, attempts) }
        }
    }

    private fun validateQuestions(questions: Map<String, DecisionQuestion>, minCriteria: Int) {
        if (questions.isEmpty() || questions.size > MAX_QUESTIONS || questions.any { it.key.isBlank() }) {
            throw DecisionError.InvalidRequest()
        }
        for (question in questions.values) {
            if (question.instructions.isBlank()) throw DecisionError.InvalidRequest()
            when (question) {
                is DecisionQuestion.Noul -> Unit
                is DecisionQuestion.Choice -> {
                    if (question.criteria.size !in minCriteria..MAX_OPTIONS ||
                        question.criteria.any { it.key.isBlank() || it.value.isBlank() }
                    ) throw DecisionError.InvalidRequest()
                }
                is DecisionQuestion.Score -> {
                    if (question.levels.size !in MIN_SCORE_LEVELS..MAX_OPTIONS ||
                        question.levels.any { it.isBlank() } ||
                        question.levels.distinct().size != question.levels.size
                    ) throw DecisionError.InvalidRequest()
                }
            }
        }
    }

    private fun buildPayload(input: JsonElement, questions: Map<String, DecisionQuestion>): JsonObject {
        val inputText = if (input is JsonPrimitive && input.isString) input.content else input.toString()
        return buildJsonObject {
            put("model", modelName)
            put("input", inputText)
            putJsonObject("questions") {
                questions.forEach { (id, question) ->
                    putJsonObject(id) {
                        when (question) {
                            is DecisionQuestion.Noul -> {
                                put("type", "noul")
                                put("instructions", question.instructions)
                            }
                            is DecisionQuestion.Choice -> {
                                put("type", "choice")
                                put("instructions", question.instructions)
                                putJsonObject("criteria") {
                                    question.criteria.forEach { (key, description) -> put(key, description) }
                                }
                            }
                            is DecisionQuestion.Score -> {
                                put("type", "score")
                                put("instructions", question.instructions)
                                putJsonArray("levels") {
                                    question.levels.forEach { label ->
                                        add(buildJsonObject { put("label", label) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseAnswers(
        root: JsonObject,
        questions: Map<String, DecisionQuestion>,
    ): Map<String, DecisionAnswer> {
        val answers = root["answers"] as? JsonObject ?: throw DecisionError.InvalidResponse()
        val parsed = LinkedHashMap<String, DecisionAnswer>(questions.size)
        for ((id, question) in questions) {
            val answer = answers[id] as? JsonObject ?: throw DecisionError.InvalidResponse()
            val confidence = optionalProbability(answer["confidence"])
            parsed[id] = when (question) {
                is DecisionQuestion.Noul -> {
                    if (string(answer["type"]) != "noul") throw DecisionError.InvalidResponse()
                    DecisionAnswer.Noul(probability(answer["noul"]), confidence)
                }
                is DecisionQuestion.Choice -> {
                    if (string(answer["type"]) != "choice") throw DecisionError.InvalidResponse()
                    val selected = string(answer["choice"])?.takeIf { it in question.criteria }
                        ?: throw DecisionError.InvalidResponse()
                    val raw = (answer["probabilities"] as? JsonObject)
                        ?: (answer["criteria"] as? JsonObject)
                        ?: throw DecisionError.InvalidResponse()
                    DecisionAnswer.Choice(
                        selected,
                        validateProbabilities(raw, question.criteria.keys, selected),
                        confidence,
                    )
                }
                is DecisionQuestion.Score -> {
                    if (string(answer["type"]) != "score") throw DecisionError.InvalidResponse()
                    val selected = string(answer["score"])?.takeIf { it in question.levels }
                        ?: throw DecisionError.InvalidResponse()
                    val raw = scoreProbabilities(answer)
                    DecisionAnswer.Score(
                        selected,
                        validateProbabilities(raw, question.levels.toSet(), selected),
                        confidence,
                    )
                }
            }
        }
        return parsed
    }

    private fun scoreProbabilities(answer: JsonObject): Map<String, JsonElement> {
        (answer["probabilities"] as? JsonObject)?.let { return it }
        (answer["criteria"] as? JsonObject)?.let { return it }
        return when (val levels = answer["levels"]) {
            is JsonObject -> levels
            is JsonArray -> LinkedHashMap<String, JsonElement>(levels.size).apply {
                for (entry in levels) {
                    val obj = entry as? JsonObject ?: throw DecisionError.InvalidResponse()
                    val label = string(obj["label"]) ?: throw DecisionError.InvalidResponse()
                    val prob = obj["probability"] ?: throw DecisionError.InvalidResponse()
                    if (put(label, prob) != null) throw DecisionError.InvalidResponse()
                }
            }
            else -> throw DecisionError.InvalidResponse()
        }
    }

    private fun validateProbabilities(
        raw: Map<String, JsonElement>,
        expectedKeys: Set<String>,
        selected: String,
    ): Map<String, Double> {
        if (raw.keys != expectedKeys) throw DecisionError.InvalidResponse()
        val values = raw.mapValues { (_, value) -> probability(value) }
        if (abs(values.values.sum() - 1.0) > 0.01) throw DecisionError.InvalidResponse()
        val selectedProbability = values.getValue(selected)
        if (values.values.any { it > selectedProbability + 1e-9 }) throw DecisionError.InvalidResponse()
        return values
    }

    private fun optionalProbability(value: JsonElement?): Double? {
        if (value == null || value is JsonNull) return null
        return probability(value)
    }

    private fun probability(value: JsonElement?): Double {
        val primitive = value as? JsonPrimitive ?: throw DecisionError.InvalidResponse()
        return primitive.takeUnless { it.isString }?.doubleOrNull
            ?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: throw DecisionError.InvalidResponse()
    }

    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun usage(root: JsonObject): Usage? {
        val value = root["usage"] as? JsonObject ?: return null
        fun count(key: String) = (value[key] as? JsonPrimitive)?.takeUnless { it.isString }
            ?.intOrNull?.takeIf { it >= 0 }
        val input = count("total_input_tokens") ?: count("input_tokens") ?: return null
        val output = count("total_output_tokens") ?: count("output_tokens") ?: 0
        return Usage(input, output)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val MAX_QUESTIONS = 1_000
        const val MIN_SCORE_LEVELS = 2
        const val MAX_OPTIONS = 255
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

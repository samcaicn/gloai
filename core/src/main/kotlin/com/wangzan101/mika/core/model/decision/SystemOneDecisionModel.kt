package com.wangzan101.mika.core.model.decision

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelHttp
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.model.usage.ConversationMeter
import com.wangzan101.mika.core.model.usage.RunUsageTally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** TypeSafe and OpenRouter's System One endpoint share this text/JSON request and response shape. */
class SystemOneDecisionModel(
    endpoint: String,
    apiKey: String,
    private val model: String,
    http: OkHttpClient = NetProxy.shared.client(),
    private val onUsage: (Usage?, Int) -> Unit = { _, _ -> },
) : DecisionModel {
    private val url = DecisionConfig.endpointUrl(endpoint) ?: throw DecisionError.InvalidRequest()
    private val key = apiKey.trim().also {
        if (it.isEmpty() || it.any { character -> character.code !in 33..126 } || model.isBlank()) {
            throw DecisionError.InvalidRequest()
        }
    }
    // Preserve the caller's proxy, pool and cancellation behavior, but bound the entire optional decision.
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
        val context = currentCoroutineContext()
        var issued = false
        var usage: Usage? = null
        try {
            context.ensureActive()
            if (instructions.isBlank() || options.isEmpty() || options.size > 255 ||
                options.any { it.key.isBlank() || it.value.isBlank() } || applicability?.isBlank() == true
            ) throw DecisionError.InvalidRequest()
            val payload = buildJsonObject {
                put("model", model)
                put("state", state)
                putJsonObject("questions") {
                    putJsonObject("choice") {
                        put("type", "choice")
                        put("instructions", instructions)
                        putJsonObject("criteria") { options.forEach { (id, description) -> put(id, description) } }
                    }
                    if (applicability != null) putJsonObject("applicable") {
                        put("type", "noul")
                        put("instructions", applicability)
                    }
                }
            }
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            // No inference trace tag: a decision must not copy its state or provider reply into chat traces.
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
            return choice(root, options, applicability != null)
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
            // Do not retain a cause: DNS, TLS, JSON and header exceptions can echo sensitive content.
            throw DecisionError.Network()
        } finally {
            val attempts = if (issued) 1 else 0
            context[RunUsageTally]?.add(usage, httpAttempts = attempts)
            runCatching { context[ConversationMeter]?.record(usage, attempts) }
            runCatching { onUsage(usage, attempts) }
        }
    }

    private fun choice(root: JsonObject, options: Map<String, String>, needsApplicability: Boolean): DecisionChoice {
        val answers = root["answers"] as? JsonObject ?: throw DecisionError.InvalidResponse()
        val answer = answers["choice"] as? JsonObject ?: throw DecisionError.InvalidResponse()
        if (string(answer["type"]) != "choice") throw DecisionError.InvalidResponse()
        val selected = string(answer["choice"])?.takeIf { it in options } ?: throw DecisionError.InvalidResponse()
        val probabilities = answer["probabilities"] as? JsonObject ?: throw DecisionError.InvalidResponse()
        if (probabilities.keys != options.keys) throw DecisionError.InvalidResponse()
        val values = probabilities.mapValues { (_, value) -> probability(value) }
        if (abs(values.values.sum() - 1.0) > 0.01) throw DecisionError.InvalidResponse()
        val selectedProbability = values.getValue(selected)
        if (values.values.any { it > selectedProbability + 1e-9 }) throw DecisionError.InvalidResponse()
        val applicable = if (needsApplicability) {
            val answer = answers["applicable"] as? JsonObject ?: throw DecisionError.InvalidResponse()
            if (string(answer["type"]) != "noul") throw DecisionError.InvalidResponse()
            probability(answer["noul"])
        } else null
        return DecisionChoice(selected, selectedProbability, applicable)
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
        return Usage(count("input_tokens") ?: return null, count("output_tokens") ?: return null)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

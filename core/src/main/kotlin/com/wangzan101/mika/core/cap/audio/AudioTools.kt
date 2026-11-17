package com.wangzan101.mika.core.cap.audio

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.TtsError
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Entry
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/** 音乐库审批卡上的 App 字段;不是真实包名。 */
private const val MUSIC_CONFIRM_APP = "mika:music"

internal object AudioGenerate : AgentTool {
    override val capability: String get() = AUDIO
    override val verb = Verb.AudioGenerate
    override val label = "生成语音"
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val wideTarget = true
    override val entry = Entry.Opens
    override val def = ToolDef("audio_generate",
        Prompts.tool("audio_generate"),
        mapOf(
            "text" to str("The script to speak, 1-4000 characters."),
            "voice" to str("Optional voice name; leave out to use the configured voice."),
            "speed" to str("Optional speaking speed, 0.25-4.0; leave out for the service default."),
            "format" to str("Output format: wav or mp3 (default wav).", listOf("wav", "mp3")),
            "name" to str("Optional file name to suggest, without a path."),
        ), listOf("text"))

    override fun available(ctx: ToolContext) = ctx[AudioCapability.KEY]?.port != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val cap = ctx[AudioCapability.KEY] ?: return fail("audio_unavailable", "No voice model is configured.")
        val port = cap.port ?: return fail("audio_unavailable", "No voice model is configured.")
        val text = a.str("text", max = port.capabilities.maxChars)!!.trim()
        val format = (a.str("format") ?: "wav").lowercase()
        if (format !in port.capabilities.formats) throw ArgError("format", "this voice model does not produce $format")
        val speed = a.number("speed")?.let { if (it in 0.25..4.0) it.toFloat() else throw ArgError("speed", "must be between 0.25 and 4.0") }
        val response = try {
            port.synthesize(TtsRequest(text, a.str("voice"), speed, format))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failure(e)
        }
        val stored = try {
            port.store(response, a.str("name", max = 120), text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failure(e)
        }
        return ToolExecution(okObj {
            put("model", port.modelName)
            put("path", stored.path)
            put("mime", stored.mime)
            put("bytes", stored.bytes)
            put("format", format)
        })
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["text"] as? JsonPrimitive)?.content?.take(200)
}

internal class AudioSave(private val ask: Boolean) : AgentTool {
    override val capability: String get() = AUDIO
    override val verb = Verb.AudioSave
    override val label = "保存音频"
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val def = ToolDef("audio_save",
        Prompts.tool("audio_save"),
        mapOf(
            "path" to str("Workspace audio path, e.g. /workspace/out/audio/line.wav."),
            "name" to str("Optional file name to save as, without a path."),
        ), listOf("path"))

    override fun available(ctx: ToolContext) = ctx[AudioCapability.KEY]?.let { it.port != null && it.saveEnabled } == true

    /** 保存是改到音乐库的一步:默认每次都要用户这一次的同意。 */
    override fun approvalRisk(args: JsonObject): ApprovalRisk = if (ask) {
        ApprovalRisk(ActionRisk.Change, ask = ConfirmationRequest(
            IrrevAction.other, "", "保存音频到音乐库:${nameOf(args)}", null, MUSIC_CONFIRM_APP,
        ))
    } else {
        ApprovalRisk(ActionRisk.Change)
    }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val cap = ctx[AudioCapability.KEY] ?: return fail("audio_unavailable", "No voice model is configured.")
        val port = cap.port ?: return fail("audio_unavailable", "No voice model is configured.")
        val path = a.str("path", max = 500)!!
        return try {
            val saved = port.saveToMusic(path, a.str("name", max = 120))
            ToolExecution(okObj {
                put("path", path)
                put("savedAs", saved.savedAs)
                put("uri", saved.uri)
                put("bytes", saved.bytes)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure(e)
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["path"] as? JsonPrimitive)?.content

    private fun nameOf(args: JsonObject): String =
        ((args["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (args["path"] as? JsonPrimitive)?.content?.substringAfterLast('/')
            ?: "audio").take(120)
}

private fun failure(e: Throwable): ToolExecution = when (e) {
    is TtsError.Unsupported -> fail("audio_unsupported", e.message ?: "This voice model does not support that request.")
    is TtsError.BadAudio -> fail("audio_bad_response", e.message ?: "The voice service returned something that is not audio.")
    is TtsError.TooLarge -> fail("audio_too_large", "The audio is larger than 20 MB.")
    is ModelError.Auth -> fail("audio_auth_failed", "The voice service rejected the API key. Check Settings › Voice model.", recoverable = false)
    is ModelError.QuotaExceeded -> fail("audio_quota_exceeded", "The voice service's quota is used up. Tell the user; do not retry.", recoverable = false)
    is ModelError.RateLimited -> fail("audio_rate_limited", "The voice service is rate limiting. Wait a little, then try once more.")
    is ModelError.ContentRefused -> fail("audio_content_filtered", "The voice service refused this text. Rephrase it or ask the user.")
    is ModelError.ModelNotFound -> fail("audio_model_missing", "The voice service does not have this model. Check Settings › Voice model.", recoverable = false)
    is ModelError.ModelNotAvailable -> fail("audio_model_unavailable", "This account cannot use this voice model.", recoverable = false)
    is ModelError.Network, is ModelError.Proxy -> fail("audio_network_failed", "Could not reach the voice service. Check the network or proxy.")
    is ModelError.Server -> fail("audio_server_error", "The voice service had a server error. Try again later.")
    is ModelError.BadResponse -> fail("audio_bad_response", "The voice service returned something unreadable.")
    else -> fail("audio_failed", e.message?.take(300) ?: "Speech generation failed.")
}

/** 让 Mika 在本机主动说一句;默认不提供,用户在声音设置里打开后才给。 */
internal object Speak : AgentTool {
    override val capability: String get() = AUDIO
    override val verb = Verb.Speak
    override val label = "说话"
    override val risk = Risk.Read
    override val offScreen = true
    override val entry = Entry.Opens
    override val def = ToolDef("speak",
        Prompts.tool("speak"),
        mapOf(
            "text" to str("The line to say, 1-2000 characters."),
            "voice" to str("Optional voice name; leave out to use the configured voice."),
        ), listOf("text"))

    override fun available(ctx: ToolContext) = ctx[AudioCapability.KEY]?.let { it.port != null && it.speakTool } == true

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val cap = ctx[AudioCapability.KEY] ?: return fail("audio_unavailable", "No voice model is configured.")
        val port = cap.port ?: return fail("audio_unavailable", "No voice model is configured.")
        val text = a.str("text", max = port.capabilities.maxChars)!!.trim()
        return try {
            port.speak(TtsRequest(text, a.str("voice"), null, "wav"))
            ToolExecution(okObj { put("played", true) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure(e)
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["text"] as? JsonPrimitive)?.content?.take(200)
}

package com.wangzan101.mika.core.cap.transcribe

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.put

/** 把工作区里的一段音频转成文字;只读,不动屏幕。 */
internal object AudioTranscribe : AgentTool {
    override val capability: String get() = TRANSCRIBE
    override val verb = Verb.AudioTranscribe
    override val label = "转写音频"
    override val risk = Risk.Read
    override val offScreen = true
    override val concurrency = Concurrency.ParallelSafe
    override val entry = com.wangzan101.mika.core.tool.Entry.Opens
    override val def = ToolDef("audio_transcribe",
        Prompts.tool("audio_transcribe"),
        mapOf(
            "path" to str("Workspace audio path, e.g. /workspace/inbox/voice.m4a or /workspace/out/audio/line.wav."),
            "language" to str("Optional language hint, e.g. zh or en."),
        ), listOf("path"))

    override fun available(ctx: ToolContext) = ctx[TranscribeCapability.KEY]?.port != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx[TranscribeCapability.KEY]?.port ?: return fail("transcribe_unavailable", "No transcription model is configured.")
        val path = a.str("path", max = 500)!!.trim()
        val prefix = SandboxPaths.WORKSPACE + "/"
        if (!path.startsWith(prefix) || path.removePrefix(prefix).split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw ArgError("path", "must be a workspace audio path under $prefix")
        }
        val language = a.str("language", max = 20)
        val transcript = try {
            port.transcribe(path, language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failure(e)
        }
        return ToolExecution(okObj {
            put("path", path)
            put("text", transcript.text)
            transcript.language?.let { put("language", it) }
        })
    }

    override fun observations(o: kotlinx.serialization.json.JsonObject, r: ToolExecution, log: com.wangzan101.mika.core.tool.EvidenceLog): List<Observation> {
        val text = (r.content["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return emptyList()
        if (text.isBlank()) return emptyList()
        val path = (o["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return emptyList()
        return listOf(Observation(ObsKind.Output, text, place = Place.file("audio_transcribe $path")))
    }
}

private fun failure(e: Throwable): ToolExecution = when (e) {
    is TranscribeError.Unsupported -> fail("transcribe_unsupported", e.message ?: "No file transcription model is configured.")
    is TranscribeError.TooLarge -> fail("transcribe_too_large", "The audio is larger than 25 MB; split it into parts first.")
    is TranscribeError.BadAudio -> fail("transcribe_bad_audio", e.message ?: "The file is not an audio file Mika can read.")
    is ModelError.Auth -> fail("transcribe_auth_failed", "The transcription service rejected the API key. Check Settings › Speech to text.", recoverable = false)
    is ModelError.QuotaExceeded -> fail("transcribe_quota_exceeded", "The transcription service's quota is used up. Tell the user; do not retry.", recoverable = false)
    is ModelError.RateLimited -> fail("transcribe_rate_limited", "The transcription service is rate limiting. Wait a little, then try once more.")
    is ModelError.ContentRefused -> fail("transcribe_refused", "The transcription service refused this audio.")
    is ModelError.ModelNotFound -> fail("transcribe_model_missing", "The transcription service does not have this model.", recoverable = false)
    is ModelError.ModelNotAvailable -> fail("transcribe_model_unavailable", "This account cannot use this transcription model.", recoverable = false)
    is ModelError.Network, is ModelError.Proxy -> fail("transcribe_network_failed", "Could not reach the transcription service. Check the network or proxy.")
    is ModelError.Server -> fail("transcribe_server_error", "The transcription service had a server error. Try again later.")
    is ModelError.BadResponse -> fail("transcribe_bad_response", "The transcription service returned something unreadable.")
    else -> fail("transcribe_failed", e.message?.take(300) ?: "Transcription failed.")
}

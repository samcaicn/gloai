package com.wangzan101.mika.media

import android.content.Context
import android.webkit.MimeTypeMap
import com.wangzan101.mika.cap.audio.AudioExporter
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.cap.audio.AudioPort
import com.wangzan101.mika.core.model.SavedAudio
import com.wangzan101.mika.core.model.StoredAudio
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 音频能力的手机实现:模型调用直接用 [model];合成结果经工作区文件闸门写进 `out/audio/`;
 * 存音乐库走 [AudioExporter](MediaStore Music/Mika,不需要存储权限)。
 */
class AndroidAudioPort(
    private val context: Context,
    private val conversationId: String,
    private val model: com.wangzan101.mika.core.model.TtsModel,
    override val capabilities: TtsCapabilities,
    private val onSaved: (SavedAudio) -> Unit = {},
    private val onGenerated: (com.wangzan101.mika.core.chat.GeneratedAudio) -> Unit = {},
    private val onSpeak: suspend (TtsResponse) -> Unit = {},
    private val defaultVoice: String? = null,
    private val defaultSpeed: Float? = null,
    private val resolveWorkspace: (String) -> WorkspaceKey = WorkspaceKey::Conversation,
) : AudioPort {
    override val modelName: String get() = model.name

    private val dirs by lazy { SandboxDirs(context) }
    private val workspace: File by lazy { dirs.workspace(resolveWorkspace(conversationId)) }

    override suspend fun synthesize(request: TtsRequest): TtsResponse = model.synthesize(request.copy(
        voice = request.voice ?: defaultVoice,
        speed = request.speed ?: defaultSpeed,
    ))

    override suspend fun speak(request: TtsRequest) = onSpeak(synthesize(request))

    override suspend fun store(response: TtsResponse, nameHint: String?, text: String): StoredAudio {
        val name = baseName(nameHint, text, response.mime)
        return dirs.tree.shared {
            withContext(Dispatchers.IO) {
                val (actual, g) = dirs.gate.createNumbered(workspace, DIRECTORY, name)
                try {
                    g.write(response.bytes)
                    val path = "${SandboxPaths.WORKSPACE}/$DIRECTORY/$actual"
                    onGenerated(com.wangzan101.mika.core.chat.GeneratedAudio(path, response.durationMs, text, System.currentTimeMillis()))
                    StoredAudio(path, response.bytes.size, response.mime, response.durationMs)
                } finally {
                    g.close()
                }
            }
        }
    }

    override suspend fun saveToMusic(path: String, name: String?): SavedAudio {
        val rel = relative(path) ?: throw com.wangzan101.mika.core.model.TtsError.Unsupported("the path must be under ${SandboxPaths.WORKSPACE}")
        return dirs.tree.shared {
            withContext(Dispatchers.IO) {
                val bytes = dirs.gate.openRead(workspace, rel).use { g ->
                    if (g.size > MAX_AUDIO_BYTES) throw com.wangzan101.mika.core.model.TtsError.TooLarge(g.size)
                    g.read(0, g.size.toInt())
                }
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase())
                    ?.takeIf { it.startsWith("audio/") } ?: "audio/wav"
                val saved = AudioExporter(context).save(bytes, mime, name ?: path.substringAfterLast('/'))
                onSaved(saved)
                saved
            }
        }
    }

    private fun relative(path: String): String? {
        val prefix = SandboxPaths.WORKSPACE + "/"
        val p = path.trim()
        if (!p.startsWith(prefix)) return null
        val rel = p.removePrefix(prefix)
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return rel
    }

    private fun baseName(hint: String?, text: String, mime: String): String {
        val raw = hint?.takeIf { it.isNotBlank() } ?: text.lineSequence().firstOrNull().orEmpty().take(24)
        val safe = Attachments.safeName(raw).ifBlank { "audio" }
        return if (safe.substringAfterLast('.', "").isEmpty()) "$safe.${extensionOf(mime)}" else safe
    }

    private fun extensionOf(mime: String): String = when (mime.substringAfter('/', "").lowercase()) {
        "mpeg", "mp3" -> "mp3"
        "ogg", "opus" -> "ogg"
        "aac" -> "aac"
        "flac" -> "flac"
        else -> "wav"
    }

    private companion object {
        const val DIRECTORY = "out/audio"
        const val MAX_AUDIO_BYTES = 20L * 1024 * 1024
    }
}

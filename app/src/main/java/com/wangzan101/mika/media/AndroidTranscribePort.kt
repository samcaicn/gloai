package com.wangzan101.mika.media

import android.content.Context
import android.webkit.MimeTypeMap
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.cap.transcribe.TranscribeError
import com.wangzan101.mika.core.cap.transcribe.TranscribePort
import com.wangzan101.mika.core.cap.transcribe.Transcript
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.providerKey
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.Transcriber
import com.wangzan101.mika.voice.model.ResolvedAccount
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 转写能力的手机实现:从工作区经文件闸门读音频。
 * 迁移后只使用目录里当前批量 STT 的显式协议;系统识别和流式 ASR 仍不接文件。
 */
class AndroidTranscribePort(
    private val context: Context,
    private val conversationId: String,
    private val choice: SpeechChoice.Provider?,
    private val stt: SttSelectionController = VoiceModelEnvironment.get(context).stt,
    private val readFile: (suspend (String) -> ByteArray)? = null,
    private val resolveWorkspace: (String) -> WorkspaceKey = WorkspaceKey::Conversation,
) : TranscribePort {
    private val dirs by lazy { SandboxDirs(context) }
    private val workspace: File by lazy { dirs.workspace(resolveWorkspace(conversationId)) }

    override suspend fun transcribe(path: String, language: String?): Transcript {
        val migrated = stt.migrated
        val batch = if (migrated) stt.batchModel() else null
        if (migrated && batch == null) {
            throw TranscribeError.Unsupported("the selected STT model cannot transcribe files; choose a batch speech model")
        }
        val bytes = readFile?.invoke(path) ?: readAudio(path)
        if (migrated) return transcribeMigrated(requireNotNull(batch), bytes, path, language)
        return transcribeLegacy(requireNotNull(choice), bytes, path, language)
    }

    private suspend fun transcribeMigrated(
        model: ResolvedVoiceModel,
        bytes: ByteArray,
        path: String,
        language: String?,
    ): Transcript {
        val spec = stt.spec(model) as? SpeechEngineSpec.Provider
            ?: throw TranscribeError.Unsupported("the selected STT model cannot transcribe files")
        val headers = (model.account as? ResolvedAccount.ExistingProvider)?.headers
            ?: ClientHeaders.of("Mika", spec.provider.sessionHeader, ModelSession.fresh())
        val transcriber = Transcriber(ProviderStore.modelHttp, headers)
        val text = transcriber.transcribe(
            spec.provider,
            model.account.credentials.apiKey.ifBlank { null },
            model.model.id,
            spec.method,
            bytes,
            language,
            mimeOf(path.substringAfterLast('.', "")),
            path.substringAfterLast('/'),
        )
        return Transcript(text, language)
    }

    private suspend fun transcribeLegacy(
        choice: SpeechChoice.Provider,
        bytes: ByteArray,
        path: String,
        language: String?,
    ): Transcript {
        val store = ProviderStore.get(context)
        val provider = store.provider(choice.providerId) ?: throw TranscribeError.Unsupported("the configured speech provider was removed")
        if (!Transcriber.supports(provider)) throw TranscribeError.Unsupported("this provider cannot transcribe files; choose a provider speech model in Settings › Speech to text")
        val transcriber = Transcriber(ProviderStore.modelHttp, ClientHeaders.of("Mika", provider.sessionHeader, ModelSession.fresh()))
        val text = transcriber.transcribe(
            provider, store.providerKey(provider.id), choice.model, choice.method, bytes, language,
            mimeOf(path.substringAfterLast('.', "")), path.substringAfterLast('/'),
        )
        return Transcript(text, language)
    }

    private suspend fun readAudio(path: String): ByteArray {
        val rel = relative(path) ?: throw TranscribeError.Unsupported("the path must be under ${SandboxPaths.WORKSPACE}")
        return dirs.tree.shared {
            withContext(Dispatchers.IO) {
                dirs.gate.openRead(workspace, rel).use { g ->
                    if (g.size > MAX_AUDIO_BYTES) throw TranscribeError.TooLarge(g.size)
                    g.read(0, g.size.toInt())
                }
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

    private fun mimeOf(ext: String): String {
        val e = ext.lowercase()
        return when (e) {
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "amr" -> "audio/amr"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "webm" -> "audio/webm"
            "wav" -> "audio/wav"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)?.takeIf { it.startsWith("audio/") } ?: "audio/wav"
        }
    }

    private companion object {
        const val MAX_AUDIO_BYTES = 25L * 1024 * 1024
    }
}

package com.wangzan101.mika.media

import android.content.Context
import android.webkit.MimeTypeMap
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.cap.transcribe.TranscribeError
import com.wangzan101.mika.core.cap.transcribe.TranscribePort
import com.wangzan101.mika.core.cap.transcribe.Transcript
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 转写能力的手机实现:从工作区经文件闸门读音频,用「设置 › 语音转文字」里选的服务商模型转成文字。
 * 系统自带识别、豆包/实时 ASR 只处理麦克风输入,不接文件;选的是这些时明确说需要配置服务商模型。
 */
class AndroidTranscribePort(
    private val context: Context,
    private val conversationId: String,
    private val choice: SpeechChoice.Provider,
) : TranscribePort {
    private val dirs by lazy { SandboxDirs(context) }
    private val workspace: File by lazy { dirs.workspace(conversationId) }

    override suspend fun transcribe(path: String, language: String?): Transcript {
        val rel = relative(path) ?: throw TranscribeError.Unsupported("the path must be under ${SandboxPaths.WORKSPACE}")
        val bytes = dirs.tree.shared {
            withContext(Dispatchers.IO) {
                dirs.gate.openRead(workspace, rel).use { g ->
                    if (g.size > MAX_AUDIO_BYTES) throw TranscribeError.TooLarge(g.size)
                    g.read(0, g.size.toInt())
                }
            }
        }
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

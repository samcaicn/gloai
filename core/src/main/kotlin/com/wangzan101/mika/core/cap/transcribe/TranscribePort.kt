package com.wangzan101.mika.core.cap.transcribe

/** 把一个工作区音频转成文字。 */
interface TranscribePort {
    /** 路径是 `/workspace/...`；读文件、选服务商、发请求都由 App 侧做。 */
    suspend fun transcribe(path: String, language: String?): Transcript
}

/** 转写结果；[language] 是服务商/设置给的语言，不知道为 null。 */
class Transcript(val text: String, val language: String? = null)

/** 转写自己的错误；服务商的 HTTP 错误仍用 [com.wangzan101.mika.core.model.ModelError]。 */
sealed class TranscribeError(message: String) : Exception(message) {
    class Unsupported(message: String) : TranscribeError(message)
    class TooLarge(val bytes: Long) : TranscribeError("audio is too large")
    class BadAudio(message: String) : TranscribeError(message)
}

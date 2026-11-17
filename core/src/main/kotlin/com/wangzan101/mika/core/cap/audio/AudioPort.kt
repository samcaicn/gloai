package com.wangzan101.mika.core.cap.audio

import com.wangzan101.mika.core.model.SavedAudio
import com.wangzan101.mika.core.model.StoredAudio
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse

/**
 * 音频能力的 App 侧实现:调 TTS、把合成结果写进工作区、把工作区音频存进音乐库。
 * 路径的规范化、符号链接检查、文件句柄核实都由 App 侧做;核心只认 `/workspace/...`。
 */
interface AudioPort {
    val modelName: String
    val capabilities: TtsCapabilities

    /** 合成一段话;失败抛 [com.wangzan101.mika.core.model.ModelError] 或 [com.wangzan101.mika.core.model.TtsError]。 */
    suspend fun synthesize(request: TtsRequest): TtsResponse

    /** 把合成结果写进工作区 `/workspace/out/audio/`，返回最终路径；写入经工作区 gate。 */
    suspend fun store(response: TtsResponse, nameHint: String?, text: String): StoredAudio

    /** 把工作区里的音频存进 `Music/Mika`（MediaStore.Audio）。 */
    suspend fun saveToMusic(path: String, name: String?): SavedAudio

    /** 合成并在本机播放一段话（不落工作区、不发送）；失败时抛同样的错误。 */
    suspend fun speak(request: TtsRequest)
}

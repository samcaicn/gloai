package com.wangzan101.mika.core.cap.transcribe

import com.wangzan101.mika.core.tool.AgentTool

internal object TranscribeGroup {
    val tools: List<AgentTool> = listOf(AudioTranscribe)
}

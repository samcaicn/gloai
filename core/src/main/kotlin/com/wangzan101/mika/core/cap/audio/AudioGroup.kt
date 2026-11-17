package com.wangzan101.mika.core.cap.audio

import com.wangzan101.mika.core.tool.AgentTool

/** 音频一组:合成一段话成文件、把工作区音频存进音乐库、让 Mika 主动说一句。保存/说话只在用户允许时提供。 */
internal object AudioGroup {
    val tools: List<AgentTool> = listOf(AudioGenerate, AudioSave(ask = true), Speak)

    fun forTask(saveEnabled: Boolean, askBeforeSave: Boolean, speakTool: Boolean): List<AgentTool> = buildList {
        add(AudioGenerate)
        if (speakTool) add(Speak)
        if (saveEnabled) add(AudioSave(askBeforeSave))
    }
}

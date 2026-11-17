package com.wangzan101.mika.core.cap.audio

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext

/** 音频能力:把文字合成成音频文件、把音频存进音乐库。没有配置声音模型时整个能力不装。 */
const val AUDIO = "audio"

internal class AudioCapability(
    val port: AudioPort?,
    val saveEnabled: Boolean,
    private val askBeforeSave: Boolean,
    val speakTool: Boolean,
) : Capability {
    override val id = AUDIO
    override val folds = true

    override fun status(): CapabilityStatus =
        if (port != null) CapabilityStatus.Available else CapabilityStatus.Unavailable("no voice model")

    override fun tools(): List<AgentTool> = AudioGroup.forTask(saveEnabled, askBeforeSave, speakTool)

    override fun sections(env: PromptEnv): List<String> =
        if (env.isOpen(id) && tools().any { it.name in env.offered }) listOf(env.gate(Prompts.capability(AUDIO))) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    companion object {
        val KEY = CapabilityKey<AudioCapability>(AUDIO)
    }
}

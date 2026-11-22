package com.wangzan101.mika.core.cap.transcribe

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext

/** 转写能力:把工作区里的音频文件转成文字;动态端口由目录和执行按当前选择过滤。 */
const val TRANSCRIBE = "transcribe"

internal class TranscribeCapability(
    private val fixedPort: TranscribePort? = null,
    private val portProvider: (() -> TranscribePort?)? = null,
) : Capability {
    override val id = TRANSCRIBE
    override val folds = true
    override val dynamicAvailability: Boolean get() = portProvider != null

    val port: TranscribePort? get() = if (portProvider != null) portProvider.invoke() else fixedPort

    override fun status(): CapabilityStatus =
        if (port != null) CapabilityStatus.Available else CapabilityStatus.Unavailable("no transcription model")

    override fun tools(): List<AgentTool> = TranscribeGroup.tools

    override fun sections(env: PromptEnv): List<String> =
        if (env.isOpen(id) && tools().any { it.name in env.offered }) listOf(env.gate(Prompts.capability(TRANSCRIBE))) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    companion object {
        val KEY = CapabilityKey<TranscribeCapability>(TRANSCRIBE)
    }
}

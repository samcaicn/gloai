package com.wangzan101.mika.core.cap.image

import com.wangzan101.mika.core.tool.AgentTool

/** 图片一组:花一次钱生成/改图、把工作区里的图存进相册。保存的工具只在用户允许时提供。 */
internal object ImageGroup {
    /** 全部声明(不认识本任务的开关);给 knownTools、运行记录认名字用。 */
    val tools: List<AgentTool> = listOf(ImageGenerate, ImageEdit, ImageSave(ask = true))

    /** 本任务实际给的工具:保存没打开时连声明都不发。 */
    fun forTask(saveEnabled: Boolean, askBeforeSave: Boolean): List<AgentTool> = buildList {
        add(ImageGenerate)
        add(ImageEdit)
        if (saveEnabled) add(ImageSave(askBeforeSave))
    }
}

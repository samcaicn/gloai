package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.ManualControl

/** 界面只依赖本次运行的控制入口，设备测试可替换输入端口。 */
internal interface ScreenControl {
    val key: String
    val manualControl: ManualControl
    suspend fun beginScreenControl(): Boolean
    suspend fun continueAfterScreenControl()
    suspend fun resizeScreen(width: Int, height: Int): Boolean
    suspend fun touchScreen(width: Int, height: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, swipe: Boolean): Boolean
    suspend fun screenBack(width: Int, height: Int): Boolean
    suspend fun screenText(width: Int, height: Int, text: String): Boolean
}

/** 手机预览经过缩放，输入端口使用完整虚拟屏像素；网页也走同一比例转换。 */
internal fun controlPoint(x: Int, y: Int, previewWidth: Int, previewHeight: Int, inputWidth: Int, inputHeight: Int): Pair<Int, Int> {
    require(previewWidth > 0 && previewHeight > 0 && inputWidth > 0 && inputHeight > 0)
    require(x in 0 until previewWidth && y in 0 until previewHeight)
    return (x.toLong() * inputWidth / previewWidth).toInt() to (y.toLong() * inputHeight / previewHeight).toInt()
}

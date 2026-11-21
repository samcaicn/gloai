package com.wangzan101.mika.cap.phone.display

/** ROTATION_0/90/180/270 分别为 0/1/2/3。物理缓冲不变,逻辑屏在四分之一圈时交换宽高。 */
internal data class DisplayFrameGeometry(val bufferWidth: Int, val bufferHeight: Int, val rotation: Int) {
    private val sideways get() = rotation == 1 || rotation == 3
    val width: Int get() = if (sideways) bufferHeight else bufferWidth
    val height: Int get() = if (sideways) bufferWidth else bufferHeight

    /** 视口使用旋转后的逻辑坐标；调整缓冲区时保留当前旋转方向。 */
    fun resized(width: Int, height: Int): DisplayFrameGeometry =
        if (sideways) copy(bufferWidth = height, bufferHeight = width)
        else copy(bufferWidth = width, bufferHeight = height)

    /** 按短边限制预览:横竖屏保持相近的像素量,避免横屏只剩一条低分辨率画面。 */
    fun previewWidth(shortSide: Int): Int =
        if (width > height) maxOf(1, (width.toLong() * shortSide / height).toInt()) else shortSide

    fun previewSize(maxWidth: Int): Pair<Int, Int> {
        val w = minOf(maxWidth, width)
        return w to maxOf(1, (height.toLong() * w / width).toInt())
    }
}

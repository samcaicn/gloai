package com.wangzan101.mika.core.chat

/**
 * 一轮对话里 Mika 生成并留在工作区的一张图。路径是沙箱路径 `/workspace/out/images/…`；
 * [width]/[height] 读不出来时为 0；[prompt] 是当时给模型的提示词（只给界面看）；[atMs] 是生成时刻。
 * 只记路径，不把图片字节存进对话文件。
 */
data class GeneratedPhoto(
    val path: String,
    val width: Int = 0,
    val height: Int = 0,
    val prompt: String? = null,
    val atMs: Long? = null,
)

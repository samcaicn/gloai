package com.wangzan101.mika.core.chat

/**
 * 一轮对话里 Mika 生成并留在工作区的一段音频。路径是沙箱路径 `/workspace/out/audio/…`；
 * [durationMs] 读不出来时为 null；[text] 是当时合成的稿子（只给界面看）；[atMs] 是生成时刻。
 * 只记路径，不把音频字节存进对话文件。
 */
data class GeneratedAudio(
    val path: String,
    val durationMs: Long? = null,
    val text: String? = null,
    val atMs: Long? = null,
)

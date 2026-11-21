package com.wangzan101.mika.voice

/**
 * 播放请求的代次令牌:每次新播放请求(begin)使之前所有代次作废,主动停下(invalidate)同理。
 * 旧请求的写文件完成回调和 MediaPlayer 回调(prepared/completion/error/focus)先用 [owns] 比对,
 * 对不上就直接丢弃——跨页面、快速切换时旧请求不会串到新请求上。
 * 只在主线程读写。
 */
internal class PlaybackTickets {
    private var generation = 0L

    /** 新播放请求:作废之前所有代次,返回本次的代次。 */
    fun begin(): Long = ++generation

    /** 主动停下:已经发出、还在路上的回调(如 prepare 完成)一律作废。 */
    fun invalidate() {
        generation++
    }

    /** [ticket] 是不是当前代次:是才允许它改播放器状态。 */
    fun owns(ticket: Long): Boolean = ticket == generation
}

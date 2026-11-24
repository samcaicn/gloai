package com.wangzan101.mika.core.frontdesk

/**
 * 前台工具里用户原话(words)的核对,由入口(聊天页、通话)给。前台给的那段只用来核对:落在用户亲口说过的哪一句里,
 * 交给后台的就是那一整句——摘片段改不了用户的意思(「不要删除那些照片」摘成「删除那些照片」也还是交整句)。
 * 一句话可以办几件事:各件都带整句,各办哪部分由前台的标题、备注说明。
 */
interface DeskWords {
    /** [fragment] 落在用户说过的哪一句里(这一轮的或之前的),返回那整句;对不上为 null。派单、补话、回答用。 */
    fun said(fragment: String): String?

    /** 只认用户这一轮说的:[fragment] 落在这一轮的话里时返回那整句,否则为 null。取消用:停一件任务得是用户此刻要停。 */
    fun saidNow(fragment: String): String?

    companion object {
        /** 这一轮不是用户开的口(前台说汇报):什么话都不认,用户的旧话不能拿来派单、补话、回答或取消。 */
        val None: DeskWords = object : DeskWords {
            override fun said(fragment: String): String? = null
            override fun saidNow(fragment: String): String? = null
        }
    }
}

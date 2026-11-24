package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.core.frontdesk.DeskWords

/** 测试用:什么原话都认,原样交出(测的不是核对的时候用)。 */
object AnyWords : DeskWords {
    override fun said(fragment: String): String = fragment
    override fun saidNow(fragment: String): String = fragment
}

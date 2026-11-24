package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskBackgroundTest {
    private val asked = mutableListOf<String>()
    private val background = deskBackground({ emptyList() }) { q -> asked += q; "- 用户不吃辣" }

    // 实时通话没有每轮的背景附注:开场画一次,任务板之外带上按这条会话最近一句用户原话检索的记忆
    @Test fun theOpeningCarriesMemoryForTheLatestUserWords() = runBlocking {
        val prior = listOf(
            Turn("帮我订日料", "好", DeskTurn.STATUS),
            Turn("明天订个川菜馆", "好", DeskTurn.STATUS),
            Turn("", "订好了", DeskTurn.REPORT_STATUS),
        )
        val opening = background.opening(prior)!!
        assertEquals(listOf("明天订个川菜馆"), asked)
        assertTrue(opening, opening.contains(DeskBackground.MEMORY_HEADING))
        assertTrue(opening.contains("- 用户不吃辣"))
    }

    @Test fun aNewConversationOpensWithoutMemory() = runBlocking {
        assertNull(background.opening(emptyList()))
        assertTrue(asked.isEmpty())
    }
}

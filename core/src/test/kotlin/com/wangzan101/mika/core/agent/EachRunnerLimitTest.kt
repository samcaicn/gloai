package com.wangzan101.mika.core.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EachRunnerLimitTest {
    private fun page(name: String) = listOf(buildJsonObject {
        put("role", "Button")
        put("name", name)
        put("ref", "@e1")
    })

    @Test fun scrollingDoesNotConsumeItemBudget() = runBlocking {
        var page = 1
        val summary = EachRunner("Button", null, EachRunner.OnError.Stop).run(
            read = { page("item$page") },
            scroll = { page++ },
            doItem = { true },
            maxItems = 2,
        )
        assertEquals(2, summary.done)
        assertEquals(listOf("Button|item1", "Button|item2"), summary.perItem.map { it.contentKey })
        assertEquals("__guard__", summary.stoppedAt)
        assertEquals(2, page)
    }

    @Test fun skippedFailuresCountTowardItemBudget() = runBlocking {
        var page = 1
        val summary = EachRunner("Button", null, EachRunner.OnError.Skip).run(
            read = { page("item$page") },
            scroll = { page++ },
            doItem = { false },
            maxItems = 2,
        )
        assertEquals(0, summary.done)
        assertEquals(2, summary.failed)
        assertEquals(2, summary.perItem.size)
        assertEquals("__guard__", summary.stoppedAt)
        assertEquals(2, page)
    }

    @Test fun noNewItemsAfterScrollStopsBelowItemBudget() = runBlocking {
        var reads = 0
        var scrolls = 0
        val summary = EachRunner("Button", null, EachRunner.OnError.Stop).run(
            read = { reads++; page("item") },
            scroll = { scrolls++ },
            doItem = { true },
            maxItems = 20,
        )
        assertEquals(1, summary.done)
        assertEquals(1, scrolls)
        assertEquals(3, reads)
        assertNull(summary.stoppedAt)
    }

    @Test fun growingListStillStopsAtItemBudget() = runBlocking {
        var reads = 0
        var scrolls = 0
        val summary = EachRunner("Button", null, EachRunner.OnError.Stop).run(
            read = { page("item${++reads}") },
            scroll = { scrolls++ },
            doItem = { true },
            maxItems = 3,
        )
        assertEquals(3, summary.done)
        assertEquals(3, reads)
        assertEquals(0, scrolls)
        assertEquals("__guard__", summary.stoppedAt)
    }
}

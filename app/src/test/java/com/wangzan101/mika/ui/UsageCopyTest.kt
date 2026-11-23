package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.core.model.usage.UsageCounts
import com.wangzan101.mika.core.model.usage.UsageKey
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageQuery
import com.wangzan101.mika.core.model.usage.UsageRange
import com.wangzan101.mika.core.model.usage.UsageRow
import com.wangzan101.mika.core.model.usage.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import com.wangzan101.mika.settings.ui.UsageCopy

class UsageCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val today = LocalDate.of(2026, 10, 27)

    private val now = Instant.parse("2026-10-27T10:00:00Z")
    private val h = now.toEpochMilli() / 3_600_000
    private val snap = UsageSnapshot(
        listOf(
            UsageRow(h, UsageKey("p1", "a", UsagePurpose.Main), UsageCounts(input = 100, calls = 1)),
            UsageRow(h, UsageKey("p1", "b", UsagePurpose.Light), UsageCounts(input = 10, calls = 1)),
            UsageRow(h, UsageKey("p2", "c", UsagePurpose.Main), UsageCounts(input = 50, calls = 1)),
            UsageRow(h, UsageKey("p3", "d", UsagePurpose.Main), UsageCounts(input = 40, calls = 1)),
            UsageRow(h, UsageKey("p4", "e", UsagePurpose.Main), UsageCounts(input = 1, calls = 1)),
        ),
        mapOf("p2" to "旧服务商"),
    )
    private val report = UsageQuery.report(snap, UsageRange.LastDays(7), now, ZoneOffset.UTC, linkedMapOf("p1" to "甲", "p3" to "丙", "p4" to "丁"))

    @Test fun modelRankingIsAllProvidersOrJustTheSelectedOne() {
        val all = UsageCopy.modelRanking(report, null)
        assertEquals(listOf("a", "c", "d", "b", "e"), all.map { it.model.model })
        assertEquals(listOf("p1", "p2", "p3", "p1", "p4"), all.map { it.provider.id })
        assertEquals(100.0 / 201, all.first().share, 1e-9)
        val p1 = UsageCopy.modelRanking(report, "p1")
        assertEquals(listOf("a", "b"), p1.map { it.model.model })
        assertEquals("占比按这一排算", 100.0 / 110, p1.first().share, 1e-9)
    }

    @Test fun percentLabelsPositiveSharesBelowOnePercentBeforeRounding() {
        assertEquals("0%", UsageCopy.percent(0.0))
        assertEquals("<1%", UsageCopy.percent(0.007))
        assertEquals("1%", UsageCopy.percent(0.01))
        assertEquals("2%", UsageCopy.percent(0.016))
    }

    @Test fun colorsFollowTheFixedOrderAndFoldBeyondThree() {
        // 连着的按连接先后:甲 0、丙 1、丁 2,删掉的排在后面
        assertEquals(mapOf("p1" to 0, "p3" to 1, "p4" to 2, "p2" to null), report.providers.associate { it.id to UsageCopy.colorSlot(it) })
    }

    @Test fun niceMaxGivesRoundGridlines() {
        assertEquals(1, UsageCopy.niceMax(0))
        assertEquals(1, UsageCopy.niceMax(1))
        assertEquals(10, UsageCopy.niceMax(9))
        assertEquals(20, UsageCopy.niceMax(11))
        assertEquals(400_000, UsageCopy.niceMax(312_345))
        assertEquals(1_000_000, UsageCopy.niceMax(812_345))
        assertEquals(1_000_000, UsageCopy.niceMax(1_000_000))
    }

    @Test fun conversationUsesKMBAndPromotesRoundedBoundaries() {
        val cases = mapOf(0L to "0", 999L to "999", 1_000L to "1K", 1_249L to "1.2K",
            1_250L to "1.3K", 999_949L to "999.9K", 999_950L to "1M", 1_200_000L to "1.2M",
            999_950_000L to "1B", 1_200_000_000L to "1.2B", 3_000_000_000L to "3B")
        cases.forEach { (tokens, expected) -> assertEquals(expected, UsageCopy.compactTokens(tokens)) }
    }

    @Test fun tokensUseWanAndYiWithThreeSignificantDigits() {
        assertEquals("0", UsageCopy.tokens(0))
        assertEquals("9999", UsageCopy.tokens(9_999))
        assertEquals("1 万", UsageCopy.tokens(10_000))
        assertEquals("1.23 万", UsageCopy.tokens(12_345))
        assertEquals("1.24 万", UsageCopy.tokens(12_350))
        assertEquals("12.3 万", UsageCopy.tokens(123_456))
        assertEquals("123 万", UsageCopy.tokens(1_234_567))
        assertEquals("1235 万", UsageCopy.tokens(12_345_678))
        assertEquals("10 万", UsageCopy.tokens(99_999))
        assertEquals("进位到一亿时改写成亿", "1 亿", UsageCopy.tokens(99_999_999))
        assertEquals("1.5 亿", UsageCopy.tokens(150_000_000))
        assertEquals("12.3 亿", UsageCopy.tokens(1_234_567_890))
    }

    @Test fun traditionalTokensUseTraditionalUnits() {
        UiText.source = ResFileTexts.traditional
        assertEquals("1.23 萬", UsageCopy.tokens(12_345))
        assertEquals("1.5 億", UsageCopy.tokens(150_000_000))
    }
}

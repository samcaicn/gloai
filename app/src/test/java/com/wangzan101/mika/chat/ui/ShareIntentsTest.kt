package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.chat.SharedDraftStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareIntentsTest {

    @Test fun singleShareAcceptsTextOrOneFileAndRejectsMultipleClipItems() {
        assertEquals(
            ParsedShare.Text("hello"),
            parseShareInput<String>(
                multiple = false,
                type = "text/plain; charset=utf-8",
                text = "hello",
                streamUris = emptyList(),
                clipUris = emptyList(),
                clipItemCount = 0,
                malformedStream = false,
            ),
        )
        assertEquals(
            ParsedShare.Files("caption", listOf("content://a/1"), "image/png", setOf("content://a/1")),
            parseShareInput(
                multiple = false,
                type = "image/png",
                text = "caption",
                streamUris = listOf("content://a/1"),
                clipUris = listOf("content://a/1"),
                clipItemCount = 1,
                malformedStream = false,
            ),
        )
        assertEquals(
            ParsedShare.Rejected,
            parseShareInput(
                multiple = false,
                type = "image/png",
                text = "",
                streamUris = listOf("content://a/1"),
                clipUris = listOf("content://a/1", "content://a/2"),
                clipItemCount = 2,
                malformedStream = false,
            ),
        )
        assertEquals(
            ParsedShare.Rejected,
            parseShareInput(
                multiple = false,
                type = "image/png",
                text = "",
                streamUris = listOf("content://a/1"),
                clipUris = listOf("content://a/2"),
                clipItemCount = 1,
                malformedStream = false,
            ),
        )
    }

    @Test fun multipleShareAcceptsStreamsAndClipOnlyAndDeduplicates() {
        val uris = listOf("content://a/1", "content://a/2", "content://a/1")
        assertEquals(
            ParsedShare.Files("一起看", listOf("content://a/1", "content://a/2"), "*/*", setOf("content://a/1", "content://a/2"), overLimit = 0),
            parseShareInput(
                multiple = true,
                type = "*/*",
                text = "一起看",
                streamUris = uris,
                clipUris = uris,
                clipItemCount = 3,
                malformedStream = false,
            ),
        )
        assertEquals(
            ParsedShare.Files("", listOf("content://a/1", "content://a/2"), "application/pdf", setOf("content://a/1", "content://a/2"), overLimit = 0),
            parseShareInput(
                multiple = true,
                type = "application/pdf",
                text = "",
                streamUris = emptyList(),
                clipUris = listOf("content://a/1", "content://a/2"),
                clipItemCount = 2,
                malformedStream = false,
            ),
        )
    }

    @Test fun multipleShareCapsAtLimitAndReportsOverLimit() {
        val uris = (1..12).map { "content://a/$it" }
        val parsed = parseShareInput(
            multiple = true,
            type = "image/*",
            text = "batch",
            streamUris = uris,
            clipUris = uris,
            clipItemCount = 12,
            malformedStream = false,
        )
        assertEquals(
            ParsedShare.Files("batch", uris.take(AttachTray.LIMIT), "image/*", uris.toSet(), overLimit = 3),
            parsed,
        )
    }

    @Test fun multipleShareRejectsMismatchedOrMalformedOrOversizedPayloads() {
        assertEquals(
            ParsedShare.Rejected,
            parseShareInput(
                multiple = true,
                type = "*/*",
                text = "",
                streamUris = listOf("content://a/1", "content://a/2"),
                clipUris = listOf("content://a/1", "content://a/3"),
                clipItemCount = 2,
                malformedStream = false,
            ),
        )
        assertEquals(
            ParsedShare.Rejected,
            parseShareInput<String>(
                multiple = true,
                type = "*/*",
                text = "",
                streamUris = emptyList(),
                clipUris = emptyList(),
                clipItemCount = 0,
                malformedStream = true,
            ),
        )
        assertEquals(
            ParsedShare.Rejected,
            parseShareInput(
                multiple = true,
                type = "*/*",
                text = "x".repeat(SharedDraftStore.MAX_TEXT_CHARS + 1),
                streamUris = listOf("content://a/1"),
                clipUris = listOf("content://a/1"),
                clipItemCount = 1,
                malformedStream = false,
            ),
        )
        assertEquals(
            ParsedShare.Rejected,
            parseShareInput<String>(
                multiple = true,
                type = "*/*",
                text = "only text with wildcard type",
                streamUris = emptyList(),
                clipUris = emptyList(),
                clipItemCount = 0,
                malformedStream = false,
            ),
        )
        assertEquals(
            ParsedShare.Text("multi text"),
            parseShareInput<String>(
                multiple = true,
                type = "text/plain",
                text = "multi text",
                streamUris = emptyList(),
                clipUris = emptyList(),
                clipItemCount = 0,
                malformedStream = false,
            ),
        )
    }
}

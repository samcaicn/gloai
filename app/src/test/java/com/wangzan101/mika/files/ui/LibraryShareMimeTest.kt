package com.wangzan101.mika.files.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryShareMimeTest {
    @Test fun imagesUseImageWildcard() {
        assertEquals("image/*", shareMime(listOf("image/jpeg", "image/png")))
    }

    @Test fun sameTypeUsesExactType() {
        assertEquals("image/jpeg", shareMime(listOf("image/jpeg")))
        assertEquals("application/pdf", shareMime(listOf("application/pdf", "application/pdf")))
    }

    @Test fun mixedOrUnknownTypesFallBackToAny() {
        assertEquals("*/*", shareMime(listOf("image/png", "application/pdf")))
        assertEquals("*/*", shareMime(listOf("image/png", null)))
        assertEquals("*/*", shareMime(emptyList()))
    }
}

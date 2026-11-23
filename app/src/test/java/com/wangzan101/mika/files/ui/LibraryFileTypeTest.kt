package com.wangzan101.mika.files.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFileTypeTest {
    @Test fun categorizesKnownTypesCaseInsensitively() {
        val files = mapOf(
            "photo.HEIF" to LibraryFileType.Image,
            "logo.svg" to LibraryFileType.Image,
            "report.final.PDF" to LibraryFileType.Pdf,
            "recording.MP3" to LibraryFileType.Audio,
            "movie.webm" to LibraryFileType.Video,
            "notes.txt" to LibraryFileType.Other,
        )
        files.forEach { (name, type) -> assertEquals(name, type, LibraryFileType.of(name)) }
    }

    @Test fun usesOnlyTheFinalExtensionOfAFileName() {
        listOf("photo.jpg.zip", ".pdf", "pdf", "report.", "movie.unknown").forEach { name ->
            assertEquals(name, LibraryFileType.Other, LibraryFileType.of(name))
        }
    }
}

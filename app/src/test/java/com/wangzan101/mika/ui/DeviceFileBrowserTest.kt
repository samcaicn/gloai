package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceFileBrowserTest {
    @Test
    fun relativeSubdirReturnsNestedSubdirectoryWithoutRootPrefix() {
        assertEquals(
            "2026/Invoices",
            relativeSubdir("/storage/emulated/0/Download/2026/Invoices/report.pdf", "/storage/emulated/0/Download"),
        )
        assertEquals(
            "2026/Invoices",
            relativeSubdir("/storage/emulated/0/Download/2026/Invoices/report.pdf", "/storage/emulated/0/Download/"),
        )
    }

    @Test
    fun relativeSubdirReturnsNullForDirectChildOrOutsideRoot() {
        assertNull(relativeSubdir("/storage/emulated/0/Download/report.pdf", "/storage/emulated/0/Download"))
        assertNull(relativeSubdir("/storage/emulated/0/Documents/report.pdf", "/storage/emulated/0/Download"))
        assertNull(relativeSubdir("/storage/emulated/0/DownloadOther/a.pdf", "/storage/emulated/0/Download"))
        assertNull(relativeSubdir("/storage/emulated/0/Download/a.pdf", null))
    }

    @Test
    fun crumbsIntoDirectoryBuildsIntermediateSegmentsFromSearchResult() {
        val base = listOf(DeviceBreadcrumb("Download", "/sdcard/Download"))
        val entry = com.wangzan101.mika.core.cap.devicefiles.DeviceFileEntry(
            name = "Invoices",
            path = "/sdcard/Download/Work/Invoices",
            isDirectory = true,
        )
        assertEquals(
            listOf(
                DeviceBreadcrumb("Download", "/sdcard/Download"),
                DeviceBreadcrumb("Work", "/sdcard/Download/Work"),
                DeviceBreadcrumb("Invoices", "/sdcard/Download/Work/Invoices"),
            ),
            crumbsIntoDirectory(base, "/sdcard/Download", entry),
        )
    }
}

package com.wangzan101.mika.cap.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrantedFolderLocationTest {
    private val external = "com.android.externalstorage.documents"
    private val downloads = "com.android.providers.downloads.documents"
    private val volumes = mapOf("primary" to "/storage/emulated/10", "ABCD-1234" to "/storage/ABCD-1234")
    private fun location(authority: String, id: String) = grantedFolderLocation(authority, id, volumes::get)

    @Test fun sameNameInDifferentParentsAndVolumesHasDistinctLocations() {
        assertEquals("/storage/emulated/10/Download/Mika", location(external, "primary:Download/Mika"))
        assertEquals("/storage/emulated/10/Documents/Mika", location(external, "primary:Documents/Mika"))
        assertEquals("/storage/ABCD-1234/Download/Mika", location(external, "ABCD-1234:Download/Mika"))
        assertEquals("/storage/emulated/10/Download/学习 %2F", location(downloads, "raw:/storage/emulated/10/Download/学习 %2F"))
    }

    @Test fun opaqueIdentifiersAndMalformedPathsDoNotBecomeLocations() {
        assertNull(location("example.cloud", "primary:Download/Mika"))
        assertNull(location(downloads, "msd:123"))
        assertNull(location(external, "unmounted:Download/Mika"))
        assertNull(location(external, "primary:/Download/Mika"))
        assertNull(location(external, "primary:Download/../Mika"))
        assertNull(location(downloads, "raw:Download/Mika"))
    }
}

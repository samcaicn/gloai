package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 单文件与工作区两道存储限额:默认与历史写死值一致,只认档位内的值。 */
class FeatureFlagsStorageLimitsTest {
    @Test fun defaultsMatchLegacyConstantsAndKeepOnlyKnownTiers() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertEquals(com.wangzan101.mika.cap.sandbox.Attachments.MAX_BYTES, flags.attachmentMaxBytes())
        assertEquals(com.wangzan101.mika.cap.sandbox.Attachments.WORKSPACE_MAX, flags.workspaceMaxBytes())
        flags.setAttachmentMaxMb(1024)
        assertEquals(1024, flags.attachmentMaxMb())
        assertEquals(1024L shl 20, flags.attachmentMaxBytes())
        // 不在几档里的(手改的、以后去掉的一档)按默认算
        flags.setAttachmentMaxMb(300)
        assertEquals(200, flags.attachmentMaxMb())
        prefs.edit().putInt("attachment_file_limit_mb", 7).commit()
        assertEquals(200, flags.attachmentMaxMb())
        flags.setWorkspaceMaxGb(16)
        assertEquals(16, flags.workspaceMaxGb())
        assertEquals(16L shl 30, flags.workspaceMaxBytes())
        flags.setWorkspaceMaxGb(6)
        assertEquals(4, flags.workspaceMaxGb())
        prefs.edit().putInt("workspace_limit_gb", 3).commit()
        assertEquals(4, flags.workspaceMaxGb())
    }
}

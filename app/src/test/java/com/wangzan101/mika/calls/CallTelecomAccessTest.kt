package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CallTelecomAccessTest {
    @Test fun recognizesExactShellPermissionDenialWithoutLeakingRawOutput() {
        val privateSuffix = "private-device-data"
        val output = """
            Exception occurred while executing 'set':
            java.lang.SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES. $privateSuffix
        """.trimIndent()
        for (exitCode in listOf(255, 0)) {
            val result = CallTelecomCommandOutcome.classify(exitCode, output)
            assertEquals(CallTelecomCommandOutcome.SHELL_APP_OPS_DENIED, result)
            assertEquals(
                "系统限制 ADB 修改通话管理权限（缺少 MANAGE_APP_OPS_MODES），无法启用电话伴侣",
                result.appOpsFailureMessage,
            )
            assertFalse(result.appOpsFailureMessage.contains(privateSuffix))
        }
    }

    @Test fun successfulCommandWithDefaultReadbackReportsSystemRejection() {
        val result = CallTelecomCommandOutcome.classify(0, "")
        val readBack = CallPolicy.previousAppOpMode("No operations.\nDefault mode: default")
        assertEquals("已尝试授权，但系统读回的通话管理权限仍为 default，无法启用电话伴侣",
            result.verificationFailure(readBack, false))
    }

    @Test fun defaultAppOpAcceptsAnEffectivePackageGrant() {
        assertEquals(null, CallTelecomCommandOutcome.OK.verificationFailure("default", true))
    }

    @Test fun failedReadbackIsNotReportedAsPermissionDenied() {
        assertEquals("授权命令已执行，但无法读回通话管理权限状态，请重试",
            CallTelecomCommandOutcome.OK.verificationFailure(null, false))
    }

    @Test fun allowedAppOpStillRequiresTelecomPermission() {
        assertEquals("通话管理权限已读回 allow，但系统通话服务仍未授权，无法启用电话伴侣",
            CallTelecomCommandOutcome.OK.verificationFailure("allow", false))
        assertEquals(null, CallTelecomCommandOutcome.OK.verificationFailure("allow", true))
    }

    @Test fun commandFailuresTakePriorityAndUnknownReadbackNeverLeaks() {
        for (outcome in listOf(CallTelecomCommandOutcome.FAILED, CallTelecomCommandOutcome.SHELL_APP_OPS_DENIED)) {
            assertEquals(outcome.appOpsFailureMessage, outcome.verificationFailure("allow", true))
        }
        assertEquals("授权命令已执行，但无法读回通话管理权限状态，请重试",
            CallTelecomCommandOutcome.OK.verificationFailure("private-device-data", false))
        for (mode in listOf("ignore", "deny", "errored", "foreground")) {
            assertEquals("已尝试授权，但系统读回的通话管理权限仍为 $mode，无法启用电话伴侣",
                CallTelecomCommandOutcome.OK.verificationFailure(mode, true))
        }
    }

    @Test fun unknownFailuresDoNotSuggestShellAppOpsPermissionDenial() {
        listOf(
            "java.lang.SecurityException: uid 2000 does not have android.permission.GRANT_RUNTIME_PERMISSIONS.",
            "java.lang.SecurityException: uid 10123 does not have android.permission.MANAGE_APP_OPS_MODES.",
            "log: read MANAGE_APP_OPS_MODES",
            "Unknown command: set",
        ).forEach {
            val result = CallTelecomCommandOutcome.classify(255, it)
            assertEquals(CallTelecomCommandOutcome.FAILED, result)
            assertEquals("系统未授予管理通话权限", result.appOpsFailureMessage)
        }
        assertEquals(CallTelecomCommandOutcome.OK, CallTelecomCommandOutcome.classify(0, ""))
    }
}

package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 按键注入的路由：解析到虚拟屏顶部任务的 uid 时走定向注入(直接投给那个窗口，不受全局输入焦点影响，
 * 否则返回键会被投到主屏焦点、虚拟屏上的 App 收不到)；解析不到时退回非定向注入。
 *
 * 修的是「back 在虚拟屏上不生效」：injectKey 之前从不解析目标 uid，一直走非定向注入。
 */
class KeyInjectionRouteTest {
    @Test fun resolvesUidOnAndroid13Plus() {
        assertEquals(10086, ShellService.resolveTargetUid(sdkInt = 33, packageName = "com.foo", userId = 0) { _, _ -> 10086 })
    }

    @Test fun belowAndroid13HasNoTargetedRoute() {
        assertNull(ShellService.resolveTargetUid(sdkInt = 32, packageName = "com.foo", userId = 0) { _, _ -> 10086 })
    }

    @Test fun nullPackageHasNoTargetedRoute() {
        assertNull(ShellService.resolveTargetUid(sdkInt = 33, packageName = null, userId = 0) { _, _ -> 10086 })
    }

    @Test fun missingPackageHasNoTargetedRoute() {
        assertNull(ShellService.resolveTargetUid(sdkInt = 33, packageName = "com.foo", userId = 0) { _, _ -> -1 })
    }
}

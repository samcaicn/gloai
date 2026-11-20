package com.wangzan101.mika.cap

import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.web.LazyWebPort
import com.wangzan101.mika.core.cap.web.WebPort
import com.wangzan101.mika.core.uistate.ScreenPort
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

class LazyPortForwardingTest {
    @Test fun lazyPhoneScreenDeclaresEveryScreenPortMember() {
        assertOverrides(LazyPhoneScreen::class.java, ScreenPort::class.java)
    }

    @Test fun lazyWebPortDeclaresEveryScreenAndWebPortMember() {
        assertOverrides(LazyWebPort::class.java, ScreenPort::class.java, WebPort::class.java)
    }

    private fun assertOverrides(implementation: Class<*>, vararg interfaces: Class<*>) {
        val declared = implementation.declaredMethods
            .filterNot { it.isSynthetic || it.isBridge }
            .map(::signature)
            .toSet()
        val required = interfaces
            .flatMap { it.declaredMethods.asList() }
            .filterNot { it.isSynthetic || it.isBridge }
            .map(::signature)
            .distinct()
        val missing = required.filterNot { it in declared }
        assertTrue(
            "${implementation.simpleName} is missing explicit members: ${missing.joinToString()}",
            missing.isEmpty(),
        )
    }

    private fun signature(method: Method): String =
        method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
}

package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptTransformTest {
    @Test fun promptTransformAppliesToSystemPrompt() = runTest {
        val m = fakeModel { answer("hi") }
        AgentLoop(m, toolDispatcher(FakeScreen()), SystemPrompt.identity(), promptTransform = { "X" }).run("t")
        assertEquals("X", m.requests.single().system)
    }
}

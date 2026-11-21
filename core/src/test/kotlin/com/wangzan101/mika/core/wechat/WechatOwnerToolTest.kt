package com.wangzan101.mika.core.wechat

import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolConfig
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.visual.Shots
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** wechat_send_owner 工具:供给随端口有无,参数上限拒超,端口结局如实进结果。 */
class WechatOwnerToolTest {
    private class FakePort(var result: WechatOwnerResult) : WechatOwnerPort {
        val sent = mutableListOf<String>()
        override suspend fun send(text: String): WechatOwnerResult {
            sent += text
            return result
        }
    }

    private fun ctx(capability: WechatOwnerCapability): ToolContext {
        val ctx = ToolContext(
            ToolConfig(false, null, false, null, false, false, null, null, null, null, null),
            EvidenceLog(), ToolIndex(capability.tools()), null, Shots(),
        )
        capability.onTaskStart(ctx)
        return ctx
    }

    private fun args(tool: AgentTool, text: String) = Args(tool.def, buildJsonObject { put("text", text) })

    @Test fun noPortOffersNothing() {
        val capability = WechatOwnerCapability(null)
        assertTrue(capability.tools().isEmpty())
        assertTrue(capability.sections(PromptEnv(offered = setOf("wechat_send_owner"), inlineScreen = false)).isEmpty())
    }

    @Test fun toolAndSectionOfferedWithPort() {
        val capability = WechatOwnerCapability(FakePort(WechatOwnerResult(WechatOwnerResult.Status.Delivered)))
        val tool = capability.tools().single()
        assertEquals("wechat_send_owner", tool.name)
        assertEquals(Risk.Write, tool.risk)
        assertTrue(tool.offScreen)
        assertTrue(tool.logged)
        assertTrue(tool.available(ctx(capability)))
        assertFalse(capability.sections(PromptEnv(offered = setOf("wechat_send_owner"), inlineScreen = false)).isEmpty())
        // 工具不在本任务供给里时守则也不出现
        assertTrue(capability.sections(PromptEnv(offered = emptySet(), inlineScreen = false)).isEmpty())
    }

    @Test fun overlongTextRejected() {
        val port = FakePort(WechatOwnerResult(WechatOwnerResult.Status.Delivered))
        val capability = WechatOwnerCapability(port)
        val tool = capability.tools().single()
        val ctx = ctx(capability)
        assertThrows(ArgError::class.java) {
            runBlocking { tool.execute(args(tool, "x".repeat(4001)), ctx) }
        }
        assertTrue(port.sent.isEmpty())
    }

    @Test fun unavailableReportedHonestly() = runTest {
        val port = FakePort(WechatOwnerResult(WechatOwnerResult.Status.Unavailable, "绑定已失效"))
        val capability = WechatOwnerCapability(port)
        val result = capability.tools().single().execute(args(capability.tools().single(), "结果正文"), ctx(capability)).content
        assertEquals(false, result.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("unavailable", result.getValue("status").jsonPrimitive.content)
        assertEquals("绑定已失效", result.getValue("text").jsonPrimitive.content)
        assertEquals(listOf("结果正文"), port.sent)
    }

    @Test fun deliveredMapsToOk() = runTest {
        val port = FakePort(WechatOwnerResult(WechatOwnerResult.Status.Delivered))
        val capability = WechatOwnerCapability(port)
        val result = capability.tools().single().execute(args(capability.tools().single(), "结果正文"), ctx(capability)).content
        assertEquals(true, result.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("delivered", result.getValue("status").jsonPrimitive.content)
    }
}

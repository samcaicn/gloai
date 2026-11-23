package com.wangzan101.mika.core.cap.root

import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记下跑过的命令;每条都成功,输出是命令本身。 */
private class FakeRoot(var answer: RootRun? = null) : RootPort {
    val ran = mutableListOf<String>()
    override suspend fun run(command: String, timeoutMs: Long): RootRun {
        ran += command
        return answer ?: RootRun.Done(0, false, "out:$command", "", 0, 5)
    }
}

private class Events : RunSink {
    val all = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { all += e }
}

private var seq = 0
private fun root(command: String) = ModelReply(
    listOf(Part.ToolCall("r${++seq}", "root_run_command", JsonObject(mapOf("command" to kotlinx.serialization.json.JsonPrimitive(command))).toString())),
    StopReason.ToolUse, null,
)
private val FINISH get() = answerReply("好了")

private fun FakeModel.result(i: Int): JsonObject =
    Json.parseToJsonElement(requests[i].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content).jsonObject
private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

/**
 * root 命令:只在给了端口时登记;只读的直接跑;会改动东西的询问时先用确认卡片问用户(卡片上就是这条命令),
 * 同意了才跑、不同意不跑;自主模式不问;问不到人的运行里不跑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootCommandToolTest {
    private fun loop(m: FakeModel, port: RootPort, autonomous: Boolean = false, sink: RunSink = RunSink.NONE) =
        AgentLoop(m, toolDispatcher(FakeScreen(), root = port), "BASE", sink = sink, confirmations = ConfirmationGate(),
            approvals = ApprovalSettings { autonomous })

    @Test
    fun registeredOnlyWithAPort() {
        assertTrue(toolDispatcher(FakeScreen()).offeredNow(all = true).none { it.name == "root_run_command" })
        val with = toolDispatcher(FakeScreen(), root = FakeRoot())
        assertTrue(with.offeredNow().any { it.name == "root_run_command" })
        // 技能、知识里提到它也认得(名字登记过)
        assertTrue("root_run_command" in knownToolNames())
    }

    /** 只读的直接跑,结果里有退出码和输出;不问用户,也不记成要同意。 */
    @Test
    fun readOnlyCommandsRunWithoutAsking() = runTest {
        val port = FakeRoot(); val sink = Events()
        val m = FakeModel(root("ls -l /data/data/com.example.app"), FINISH)
        loop(m, port, sink = sink).run("t")
        assertEquals(listOf("ls -l /data/data/com.example.app"), port.ran)
        val r = m.result(1)
        assertEquals("true", r.s("ok"))
        assertEquals("0", r.s("exitCode"))
        assertTrue(r.s("stdout"), "out:ls" in r.s("stdout"))
        assertTrue(sink.all.none { it is RunEvent.Confirmation })
    }

    /** 会改的直接跑:用户打开「允许 root 命令」本身就是授权,不再逐条问。 */
    @Test
    fun changingCommandsRunWithoutAsking() = runTest {
        val port = FakeRoot()
        val m = FakeModel(root("rm /sdcard/Download/old.apk"), FINISH)
        loop(m, port).run("t")
        assertEquals(listOf("rm /sdcard/Download/old.apk"), port.ran)
        assertEquals("true", m.result(1).s("ok"))
    }

    @Test
    fun autonomousModeDoesNotAsk() = runTest {
        val port = FakeRoot()
        val m = FakeModel(root("rm /sdcard/x"), FINISH)
        loop(m, port, autonomous = true).run("t")
        assertEquals(listOf("rm /sdcard/x"), port.ran)
    }

    /** 问不到人的运行(定时、无人值守):会改的不跑,只读的照跑。 */
    @Test
    fun unattendedRunsDoNotChangeAnything() = runTest {
        val port = FakeRoot()
        val m = FakeModel(root("rm /sdcard/x"), root("ls /sdcard"), FINISH)
        val spec = RunSpec(RunId("r"), "t", "th", provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
        loop(m, port).run(spec, RunInbox())
        assertEquals(listOf("ls /sdcard"), port.ran)
        assertEquals("approval_unavailable", m.result(1).s("error"))
    }

    /** su 拒绝了:照实告诉模型,不当作命令失败重试。 */
    @Test
    fun deniedSuIsReported() = runTest {
        val m = FakeModel(root("ls /data"), FINISH)
        loop(m, FakeRoot(RootRun.Denied)).run("t")
        val r = m.result(1)
        assertEquals("root_denied", r.s("error"))
        assertFalse(r.s("message").isBlank())
        assertNull(r["stdout"])
    }
}

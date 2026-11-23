package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteSteer
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTaskRecordTest {
    @Test fun terminalStatesRoundTrip() {
        val states = listOf(
            TaskState.Succeeded("answer"),
            TaskState.Succeeded("answer", verified = false, note = "unchecked", unconfirmed = listOf("a", "b"), reviewed = true),
            TaskState.Succeeded("answer", verified = false, reviewed = false),
            TaskState.Cancelled,
        ) + FailKind.entries.map { TaskState.Failed("why $it", it) }
        for (s in states) assertEquals(s, RemoteTaskRecord.decode(RemoteTaskRecord.encode(s)).state)
    }

    // 盘上的字自己定,不随枚举名;存下来的格式改了要改这里
    @Test fun storedFormatIsIndependentOfDomainNames() {
        assertEquals("""{"type":"failed","reason":"x","kind":"step_limit"}""",
            RemoteTaskRecord.encode(TaskState.Failed("x", FailKind.StepLimit)))
        assertEquals("""{"type":"failed","reason":"x","kind":"token_limit"}""",
            RemoteTaskRecord.encode(TaskState.Failed("x", FailKind.TokenLimit)))
        assertEquals("""{"type":"cancelled"}""", RemoteTaskRecord.encode(TaskState.Cancelled))
    }

    // 回答的证据账本不进远程收件
    @Test fun sourcesAreNotStored() {
        val s = TaskState.Succeeded("a", sources = listOf(com.wangzan101.mika.core.safety.verify.AnswerSource(1, "screen", null, null, 1)))
        assertEquals(s.copy(sources = emptyList()), RemoteTaskRecord.decode(RemoteTaskRecord.encode(s)).state)
    }

    @Test fun legacyAndUnknownValuesDecodeSafely() {
        // 合入前直接序列化领域类型写下的样子:类别是枚举名
        assertEquals(TaskState.Failed("stopped", FailKind.Interrupted),
            RemoteTaskRecord.decode("""{"type":"failed","reason":"stopped","kind":"Interrupted"}""").state)
        assertEquals(TaskState.Succeeded("a", verified = false, note = "n", unconfirmed = listOf("x")),
            RemoteTaskRecord.decode("""{"type":"done","answer":"a","verified":false,"note":"n","sources":[],"unconfirmed":["x"]}""").state)
        // 更新的版本写的类别、多出来的字段
        assertEquals(TaskState.Failed("r", FailKind.Environment),
            RemoteTaskRecord.decode("""{"type":"failed","reason":"r","kind":"something_new","extra":1}""").state)
        for (bad in listOf("", "not json", "[]", "{}", """{"type":"paused"}""", """{"type":"done"}""", """{"type":"done","answer":3}"""))
            assertEquals(bad, RemoteTaskRecord.UNREADABLE, RemoteTaskRecord.decode(bad).state)
    }

    @Test fun steersRoundTripAndSkipBrokenEntries() {
        val steers = listOf(RemoteSteer("m1", "joined"), RemoteSteer("m2", "not_applied"))
        assertEquals(steers, RemoteTaskRecord.decodeSteers(RemoteTaskRecord.encodeSteers(steers)))
        assertEquals(listOf(RemoteSteer("m1", "joined")),
            RemoteTaskRecord.decodeSteers("""[{"messageId":"m1","state":"joined","x":1},{"messageId":"m2"},3]"""))
        assertEquals(emptyList<RemoteSteer>(), RemoteTaskRecord.decodeSteers("{"))
    }
}

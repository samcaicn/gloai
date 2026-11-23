package com.wangzan101.mika.core.run.record

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.ActionResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackTest {
    private fun args(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun normalizedCoordinatesAndScrollDirection() {
        assertEquals(PlaybackMark("tap", 999, 0), PlaybackMark.of(Verb.Tap, args("""{"x":999,"y":0}"""), null, null))
        assertNull(PlaybackMark.of(Verb.Tap, args("""{"x":1000,"y":0}"""), null, null))
        assertNull(PlaybackMark.of(Verb.Tap, args("""{"x":"10","y":0}"""), null, null))
        assertEquals(PlaybackMark("swipe", 500, 850, 500, 300), PlaybackMark.of(Verb.Swipe,
            args("""{"startX":500,"startY":850,"endX":500,"endY":300}"""), null, null))
        val down = PlaybackMark.of(Verb.Scroll, args("""{"direction":"down"}"""), null, null)!!
        assertTrue(down.y > down.endY!!)
        assertTrue(down.approximate)
        assertNull(PlaybackMark.of(Verb.Swipe, args("""{"startX":1,"startY":1,"endX":1,"endY":1}"""), null, null))
    }

    @Test fun captureWorksWithoutVisionAndDoesNotEnterModelContent() = runTest {
        val fake = FakeScreen()
        var frame = 1
        val screen = object : ScreenPort by fake {
            override suspend fun screenshotPng() = byteArrayOf(frame.toByte())
            override suspend fun tapXY(x: Int, y: Int): ActionResult { frame = 2; return fake.tapXY(x, y) }
        }
        val tools = toolDispatcher(screen, visionEnabled = false, recordFrames = true)
        val result = tools.execute(Part.ToolCall("tap", "phone_tap", """{"x":500,"y":200}"""))
        assertArrayEquals(byteArrayOf(1), result.playback!!.before)
        assertArrayEquals(byteArrayOf(2), result.playback!!.after)
        assertEquals(PlaybackMark("tap", 500, 200), result.playback!!.mark)
        assertTrue(result.images.isEmpty())
        assertFalse(result.content.toString().contains("playback"))
        assertSame(result.playback, result.with().playback)
    }

    @Test fun secureScreensAreNeverCapturedAndDisabledRecordingIsInert() = runTest {
        var captures = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun peek() = ScreenSnapshot("s", "com.x", emptyList(), 1080, 2400, protected = true)
            override suspend fun isProtected() = true
            override suspend fun screenshotPng(): ByteArray { captures++; return byteArrayOf(1) }
        }
        val tools = toolDispatcher(screen, recordFrames = true)
        val result = tools.execute(Part.ToolCall("back", "phone_back", "{}"))
        assertNull(result.playback?.before)
        assertNull(result.playback?.after)
        assertEquals(0, captures)
        val off = toolDispatcher(FakeScreen(), recordFrames = false).execute(Part.ToolCall("back", "phone_back", "{}"))
        assertNull(off.playback)
    }

    @Test fun executedActionsHaveDistinctFrameIdsAndRoundTrip() = runTest {
        val events = mutableListOf<RunEvent>()
        val frames = mutableMapOf<Int, ByteArray>()
        val tools = toolDispatcher(FakeScreen(), recordFrames = true)
        val recorder = ActionRecorder(object : RunSink { override fun event(e: RunEvent) { events += e } }, saveScreen = { id, png -> frames[id] = png })
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = {}, recorder = recorder)
        executor.run(Part.ToolCall("tap", "phone_tap", """{"x":500,"y":200}"""), Origin.Model(1))
        val e = events.filterIsInstance<RunEvent.TaskPlayback>().single()
        assertNotEquals(e.beforeId, e.afterId)
        assertTrue(frames.containsKey(e.beforeId))
        assertTrue(frames.containsKey(e.afterId))
        assertEquals(e, RunEvent.decode(RunEvent.encode(e)))
        executor.run(Part.ToolCall("invalid", "phone_tap", """{"x":1001,"y":2}"""), Origin.Model(2))
        assertEquals(1, events.filterIsInstance<RunEvent.TaskPlayback>().size)
    }

    @Test fun elementMarkerIsPreservedAsAnEstimateNotAnActualTouch() = runTest {
        val tools = toolDispatcher(FakeScreen(), recordFrames = true)
        tools.execute(Part.ToolCall("observe", "phone_observe", "{}"))
        val result = tools.execute(Part.ToolCall("tap", "phone_tap", """{"ref":"@e1","snapshotId":"snap1"}"""))
        assertEquals(PlaybackMark("tap", 500, 50, approximate = true), result.playback!!.mark)
    }
}

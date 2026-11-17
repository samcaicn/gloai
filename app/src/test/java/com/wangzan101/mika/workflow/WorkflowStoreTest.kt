package com.wangzan101.mika.workflow

import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class WorkflowStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file get() = File(tmp.root, "workflows.bin")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmKeyCipher { key }
    private fun store(cipher: KeyCipher = this.cipher) = WorkflowStore(file, cipher) { 1000L }
    private val steps = listOf(WorkflowStep(title = "Find", instruction = "Find materials"), WorkflowStep(title = "Plan", instruction = "Make a plan"))
    private fun template(s: WorkflowStore) = s.saveTemplate("template", 0, "Study", steps)
    private fun fails(problem: WorkflowProblem, action: () -> Unit) {
        try { action(); fail("expected $problem") } catch (e: WorkflowException) { assertEquals(problem, e.problem) }
    }
    @Test fun sequentialResultsSnapshotsAndDuplicateCallbacks() {
        val store = store(); val template = template(store)
        val launched = mutableListOf<WorkflowDispatch>()
        val runtime = WorkflowRuntime(store) { launched += it }
        val id = runtime.start(template, "Japanese")
        val first = launched.single()
        assertEquals("Japanese", first.input); assertEquals("", first.previous)
        assertNotNull(store.claim(first.attemptId)); assertNull(store.claim(first.attemptId))
        store.saveTemplate(template.id, 1, "Changed", steps.reversed())
        runtime.complete(first.attemptId, true, "五十音资料")
        runtime.complete(first.attemptId, true, "duplicate")
        assertEquals(2, launched.size)
        val second = launched.last()
        assertEquals("五十音资料", second.previous); assertEquals("Plan", second.step.title)
        assertNotEquals(first.conversationId, second.conversationId)
        assertNotNull(store.claim(second.attemptId))
        runtime.complete(second.attemptId, true, "Seven-day plan")
        val run = store.data.value.runs.single()
        assertEquals(id, run.id); assertEquals(WorkflowPhase.Completed, run.phase)
        assertEquals("Seven-day plan", run.result); assertEquals(2, run.completed)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("Seven-day plan"))
        assertEquals(store.data.value, store().also { it.load() }.data.value)
    }
    @Test fun interruptedAttemptsRequireExplicitResolutionAndNeverReplayOnLoad() {
        val store = store(); val t = template(store)
        val firstRun = store.start(t.id, t.revision, "trip")
        val first = store.prepare(firstRun.id)!!; store.claim(first.attemptId)
        store.finish(first.attemptId, true, "Gathered")
        val second = store.prepare(firstRun.id)!!; store.claim(second.attemptId)
        val reopened = store(); reopened.load()
        assertEquals(WorkflowPhase.Review, reopened.data.value.runs.single().phase)
        assertEquals(1, reopened.data.value.runs.single().completed)
        assertNull(reopened.claim(second.attemptId)); assertNull(reopened.prepare(firstRun.id))
        val launched = mutableListOf<WorkflowDispatch>()
        val runtime = WorkflowRuntime(reopened) { launched += it }
        runtime.resolve(firstRun.id, second.attemptId, "Already completed")
        assertTrue(launched.isEmpty())
        assertEquals("Already completed", reopened.data.value.runs.single().result)
        assertTrue(reopened.data.value.runs.single().attempts.last().last().manual)
    }
    @Test fun pausePreservesCompletedWorkAndRetryKeepsOldAttempt() {
        val store = store(); val t = template(store)
        val launched = mutableListOf<WorkflowDispatch>(); val runtime = WorkflowRuntime(store) { launched += it }
        val id = runtime.start(t, "repair"); val first = launched.single(); store.claim(first.attemptId)
        store.pause(id); assertEquals(WorkflowPhase.Pausing, store.data.value.runs.single().phase)
        runtime.complete(first.attemptId, true, "Model A")
        assertEquals(1, launched.size); assertEquals(WorkflowPhase.Paused, store.data.value.runs.single().phase)
        runtime.resume(id); val second = launched.last(); store.claim(second.attemptId)
        runtime.complete(second.attemptId, false, "Partial", "offline")
        assertEquals(WorkflowPhase.Review, store.data.value.runs.single().phase)
        runtime.resolve(id, second.attemptId, null)
        assertEquals(3, launched.size); assertNotEquals(second.attemptId, launched.last().attemptId)
        assertEquals("Model A", launched.last().previous)
        assertEquals(2, store.data.value.runs.single().attempts.last().size)
        runtime.complete(second.attemptId, true, "late callback")
        assertEquals(AttemptPhase.Dispatching, store.data.value.runs.single().current!!.phase)
    }
    @Test fun writeFailureDoesNotDispatchAndCompletionCanBeSavedAgain() {
        var failWrite = false
        val cipher = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = if (failWrite) error("disk") else this@WorkflowStoreTest.cipher.encrypt(plain)
            override fun decrypt(data: ByteArray) = this@WorkflowStoreTest.cipher.decrypt(data)
        }
        val store = store(cipher); val t = template(store)
        val launched = mutableListOf<WorkflowDispatch>(); val runtime = WorkflowRuntime(store) { launched += it }
        failWrite = true
        fails(WorkflowProblem.Write) { runtime.start(t, "trip") }
        assertTrue(launched.isEmpty()); assertTrue(store.data.value.runs.isEmpty())
        failWrite = false; runtime.start(t, "trip"); val first = launched.single(); store.claim(first.attemptId)
        val bytes = file.readBytes()
        failWrite = true; runtime.complete(first.attemptId, true, "Result")
        assertTrue(runtime.saveError.value); assertEquals(1, launched.size); assertArrayEquals(bytes, file.readBytes())
        failWrite = false; runtime.retrySave()
        assertFalse(runtime.saveError.value); assertEquals(2, launched.size)
        runtime.retrySave(); assertEquals(2, launched.size)
    }
    @Test fun retrySavingCanDispatchAfterTheRunWasSavedButPreparingFailed() {
        var writes = 0
        var failAt = -1
        val storage = store(object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray {
                writes++
                if (writes == failAt) error("disk")
                return cipher.encrypt(plain)
            }
            override fun decrypt(data: ByteArray) = cipher.decrypt(data)
        })
        val t = template(storage)
        failAt = writes + 2 // start commits; prepare fails before dispatch
        val launched = mutableListOf<WorkflowDispatch>()
        val runtime = WorkflowRuntime(storage) { launched += it }
        val id = runtime.start(t, "requirements")
        assertTrue(runtime.saveError.value)
        assertEquals(id, storage.data.value.runs.single().id)
        assertTrue(launched.isEmpty())
        runtime.retrySave()
        assertFalse(runtime.saveError.value)
        assertEquals(1, launched.size)
    }

    @Test fun launchFailureAndOversizedResultStopTheChain() {
        val store = store(); val t = template(store)
        val runtime = WorkflowRuntime(store) { error("FGS denied") }
        val id = runtime.start(t, "")
        assertEquals(WorkflowPhase.Review, store.data.value.runs.single().phase)
        assertEquals("start_failed", store.data.value.runs.single().current!!.problem)
        store.resolve(id, store.data.value.runs.single().current!!.id, null)
        val attempt = store.pendingDispatch(id)!!; store.claim(attempt.attemptId)
        store.finish(attempt.attemptId, true, "x".repeat(WorkflowStore.MAX_RESULT + 1))
        assertEquals(WorkflowPhase.Review, store.data.value.runs.single().phase)
        assertEquals("result_too_long", store.data.value.runs.single().current!!.problem)
    }
    @Test fun validationStaleEditsAndUnreadableStoragePreserveOriginal() {
        val store = store(); val t = template(store)
        fails(WorkflowProblem.Invalid) { store.saveTemplate("bad", 0, "", steps) }
        fails(WorkflowProblem.Invalid) { store.saveTemplate("bad", 0, "Bad", steps.take(1)) }
        fails(WorkflowProblem.Changed) { store.saveTemplate(t.id, 0, "old", steps) }
        val id = store.start(t.id, t.revision, "").id
        fails(WorkflowProblem.Busy) { store.start(t.id, t.revision, "") }
        fails(WorkflowProblem.Busy) { store.deleteRun(id) }
        store.pause(id); store.deleteRun(id)
        store.deleteTemplate(t.id, t.revision)
        assertTrue(store().also { it.load() }.data.value.templates.isEmpty())
        file.writeBytes(byteArrayOf(1, 2, 3)); val broken = store()
        fails(WorkflowProblem.Read) { broken.load() }
        fails(WorkflowProblem.Read) { template(broken) }
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }
}

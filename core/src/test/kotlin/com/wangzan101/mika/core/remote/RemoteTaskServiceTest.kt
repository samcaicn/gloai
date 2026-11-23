package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteTaskServiceTest {
    private val tasks = FakeRemoteTasks()
    private val service = RemoteTaskService(tasks)

    @Test fun validationDoesNotStartATask() {
        for ((goal, conversation) in listOf(" " to null, "x".repeat(4_001) to null, "x" to "local-conversation")) {
            val error = runCatching { service.start(goal, conversation) }.exceptionOrNull() as RemoteTaskError
            assertEquals(RemoteTaskError.Code.INVALID_ARGUMENT, error.code)
        }
        assertTrue(tasks.tasks.isEmpty())
    }

    @Test fun typedOperationsPreserveIdentityAndActualState() {
        val first = service.start(" first ")
        val second = service.start("second", first.conversation)
        assertEquals(first.conversation, second.conversation)
        assertEquals(listOf(second.id, first.id), service.list().map { it.id })
        tasks.set(first.id, TaskState.WaitingInput("Which account?"))
        assertEquals(first.id, service.answer(first.id, " personal "))
        assertEquals(listOf(first.id to "personal"), tasks.answers)
        val cancelled = service.cancel(first.id)
        assertTrue(cancelled.accepted)
        assertNull(cancelled.status)
        assertEquals(RemoteStatus.WAITING_FOR_ANSWER, RemoteStatus.of(service.get(first.id).state))
        tasks.set(first.id, TaskState.Cancelled)
        assertEquals(RemoteStatus.CANCELLED, service.cancel(first.id).status)
        assertEquals(1, tasks.cancelled.size)
    }

    @Test fun cannotAnswerPhoneTakeoverOrAQuestionThatClosed() {
        val task = service.start("do a task")
        tasks.set(task.id, TaskState.WaitingTakeover("payment", "Use the phone"))
        val error = runCatching { service.answer(task.id, "yes") }.exceptionOrNull() as RemoteTaskError
        assertEquals(RemoteTaskError.Code.CONFLICT, error.code)
        assertTrue(error is RemoteTaskError.NeedsPhone)
        assertTrue(tasks.answers.isEmpty())

        val closing = object : RemoteTasks by tasks {
            override fun answer(id: String, text: String) = false
        }
        tasks.set(task.id, TaskState.WaitingInput("Which one?"))
        val closed = runCatching { RemoteTaskService(closing).answer(task.id, "one") }.exceptionOrNull() as RemoteTaskError
        assertEquals(RemoteTaskError.Code.CONFLICT, closed.code)
        assertTrue(closed is RemoteTaskError.QuestionClosed)
    }

    @Test fun waitSharesTheBoundedStateTransitionRule() = runTest {
        val task = service.start("wait")
        tasks.set(task.id, TaskState.Running(1, "first"))
        var result: RemoteWait? = null
        launch { result = service.wait(task.id, 5) }
        advanceTimeBy(1_000)
        tasks.set(task.id, TaskState.Running(2, "progress only"))
        advanceTimeBy(1_000)
        assertNull(result)
        tasks.set(task.id, TaskState.WaitingInput("Which one?"))
        advanceTimeBy(250)
        assertTrue(result!!.changed)
        assertEquals(RemoteStatus.WAITING_FOR_ANSWER, RemoteStatus.of(result!!.task.state))
        val start = testScheduler.currentTime
        assertFalse(service.wait(task.id, 1).changed)
        assertEquals(1_000L, testScheduler.currentTime - start)
        assertTrue(tasks.cancelled.isEmpty())
    }

    @Test fun lostTaskDuringWaitReturnsTheLastSeenStatus() = runTest {
        val task = service.start("wait")
        tasks.set(task.id, TaskState.Running(1, "working"))
        launch { kotlinx.coroutines.delay(500); tasks.tasks.remove(task.id) }
        val result = service.wait(task.id, 2)
        assertEquals(RemoteStatus.RUNNING, RemoteStatus.of(result.task.state))
        assertFalse(result.changed)
        assertTrue(testScheduler.currentTime < 2_000)
    }

    @Test fun steeringKeepsTheTaskAndConversationAndReportsOnlyReceipt() {
        val task = service.start("read messages")
        tasks.set(task.id, TaskState.Running(1, "reading"))
        val receipt = service.steer(" ${task.id} ", "  only today\n不要发送 👋  ")
        assertEquals(task.id, receipt.taskId)
        assertEquals(listOf(task.id to "only today\n不要发送 👋"), tasks.steered)
        val current = service.get(task.id)
        assertEquals(task.conversation, current.conversation)
        assertEquals(task.goal, current.goal)
        assertEquals(listOf(RemoteSteer(receipt.messageId, "received")), current.steers)
        assertEquals(1, tasks.tasks.size)
    }

    @Test fun steeringRejectsUnknownIdsEmptyOrLongInputWithoutDelivery() {
        val task = service.start("read")
        tasks.set(task.id, TaskState.Running(1, "reading"))
        for ((id, text) in listOf("" to "more", task.id to "  ", task.id to "x".repeat(RemoteTaskService.MAX_STEER + 1))) {
            assertEquals(RemoteTaskError.Code.INVALID_ARGUMENT,
                (runCatching { service.steer(id, text) }.exceptionOrNull() as RemoteTaskError).code)
        }
        assertEquals(RemoteTaskError.Code.NOT_FOUND,
            (runCatching { service.steer("local-task", "more") }.exceptionOrNull() as RemoteTaskError).code)
        assertTrue(tasks.steered.isEmpty())
    }

    @Test fun steeringDoesNotAnswerResumeApproveOrRestartTasks() {
        val task = service.start("read")
        val states = listOf(null, TaskState.Idle, TaskState.WaitingInput("Which one?"),
            TaskState.WaitingTakeover("payment", "Use the phone"),
            TaskState.WaitingConfirmation(com.wangzan101.mika.core.safety.gate.ConfirmationRequest(
                com.wangzan101.mika.core.safety.gate.IrrevAction.order, "Shop", "item", "$25", "shop")),
            TaskState.Held(com.wangzan101.mika.core.agent.GateReason("paused")),
            TaskState.Succeeded("done"), TaskState.Failed("failed", com.wangzan101.mika.core.agent.FailKind.Environment), TaskState.Cancelled)
        for (state in states) {
            tasks.set(task.id, state)
            assertEquals("$state", RemoteTaskError.Code.CONFLICT,
                (runCatching { service.steer(task.id, "continue") }.exceptionOrNull() as RemoteTaskError).code)
        }
        assertTrue(tasks.steered.isEmpty())
        assertTrue(tasks.answers.isEmpty())
        assertEquals(1, tasks.tasks.size)
    }

    @Test fun taskClosingBetweenCheckAndDeliveryIsNotAcknowledged() {
        val task = service.start("read")
        tasks.set(task.id, TaskState.Running(1, "reading"))
        val closing = object : RemoteTasks by tasks {
            override fun steer(id: String, text: String): String? {
                tasks.set(id, TaskState.Cancelled)
                return null
            }
        }
        assertEquals(RemoteTaskError.Code.CONFLICT,
            (runCatching { RemoteTaskService(closing).steer(task.id, "more") }.exceptionOrNull() as RemoteTaskError).code)
        assertTrue(tasks.steered.isEmpty())
        assertEquals(1, tasks.tasks.size)
    }
}

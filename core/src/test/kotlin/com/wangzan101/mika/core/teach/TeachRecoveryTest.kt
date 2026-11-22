package com.wangzan101.mika.core.teach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeachRecoveryTest {
    private val id = "c".repeat(32)

    private fun session(phase: TeachPhase, draftId: String? = null) =
        TeachSession(id = id, goal = "在小红书搜索装修", phase = phase, draftId = draftId)

    @Test fun reviewWithoutAStoredDraftGoesBackToDrafting() {
        val next = TeachRecovery.afterLoad(session(TeachPhase.Reviewing))
        assertEquals(TeachPhase.Drafting, next.phase)
        assertNull(next.draftId)
    }

    @Test fun reviewWithAStoredDraftStaysInReview() {
        val next = TeachRecovery.afterLoad(session(TeachPhase.Reviewing, draftId = "d1"))
        assertEquals(TeachPhase.Reviewing, next.phase)
        assertEquals("d1", next.draftId)
    }

    @Test fun collectingAfterRestartBecomesInterrupted() {
        assertEquals(TeachPhase.Interrupted, TeachRecovery.afterLoad(session(TeachPhase.Recording)).phase)
        assertEquals(TeachPhase.Interrupted, TeachRecovery.afterLoad(session(TeachPhase.Paused)).phase)
    }

    @Test fun savedAndIdleAreLeftAlone() {
        assertEquals(TeachPhase.Saved, TeachRecovery.afterLoad(session(TeachPhase.Saved)).phase)
        assertEquals(TeachPhase.Idle, TeachRecovery.afterLoad(session(TeachPhase.Idle)).phase)
    }

    /** 重启以后仍然知道正文是不是模型写的：「这次没接模型」的说明不能靠重启后的配置去猜。 */
    @Test fun theModelUseFlagSurvivesASaveAndLoad() {
        val session = session(TeachPhase.Reviewing, draftId = "d1").copy(byModel = true)
        assertEquals(true, TeachCodec.decode(TeachCodec.encode(session)).byModel)
        assertEquals(false, TeachCodec.decode(TeachCodec.encode(session.copy(byModel = false))).byModel)
    }
}

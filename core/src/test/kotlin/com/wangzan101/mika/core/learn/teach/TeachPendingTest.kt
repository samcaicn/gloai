package com.wangzan101.mika.core.learn.teach

import com.wangzan101.mika.core.learn.skills.PersonalSkill
import com.wangzan101.mika.core.learn.skills.SkillStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeachPendingTest {
    private fun skill(learnedFrom: String?, name: String = "a") = PersonalSkill(
        name = name,
        markdown = "---\nname: $name\n---\n正文",
        status = SkillStatus.pending,
        learnedFrom = learnedFrom,
        replacesDraftOf = null,
        createdAt = 0L,
        outcomes = emptyList(),
        source = TeachSkill.SOURCE,
    )

    @Test fun findsTheDraftAlreadyStoredForTheSameTrace() {
        val drafts = mapOf(
            "d1" to skill("teach:t-1", "a"),
            "d2" to skill("teach:t-2", "b"),
        )
        assertEquals("d1", TeachPending.find(drafts, "t-1")?.first)
        assertEquals("a", TeachPending.find(drafts, "t-1")?.second?.name)
    }

    @Test fun ignoresDraftsFromOtherTraces() {
        assertNull(TeachPending.find(mapOf("d1" to skill("teach:t-2")), "t-1"))
        assertNull(TeachPending.find(emptyMap(), "t-1"))
    }
}

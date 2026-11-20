package com.wangzan101.mika.chat

import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun resolvesConversationProjectAndDraftBindings() {
        val conversations = ConversationStore(tmp.newFolder("chats"))
        val projects = ProjectStore(tmp.newFolder("projects"))
        val drafts = mutableMapOf<String, String>()
        val resolver = WorkspaceResolver(conversations, projects) { drafts[it] }

        val project = projects.create("科研").getOrThrow()

        // 独立对话 -> Conversation(id)
        assertEquals(WorkspaceKey.Conversation("c1"), resolver.resolve("c1"))
        assertNull(resolver.projectIdOf("c1"))
        assertFalse(resolver.isShared("c1"))

        // 未落盘草稿选中有效项目 -> Project(id)
        drafts["draft-1"] = project.id
        assertEquals(WorkspaceKey.Project(project.id), resolver.resolve("draft-1"))
        assertEquals(project.id, resolver.projectIdOf("draft-1"))
        assertTrue(resolver.isShared("draft-1"))

        // 落盘旁文件优先于草稿缓存
        assertTrue(conversations.setProject("c2", project.id))
        assertEquals(WorkspaceKey.Project(project.id), resolver.resolve("c2"))
        assertTrue(resolver.isShared("c2"))

        // 项目不存在或已删时安全回落为独立对话工作区
        assertTrue(conversations.setProject("c3", "missing-project"))
        assertEquals(WorkspaceKey.Conversation("c3"), resolver.resolve("c3"))
        assertNull(resolver.projectIdOf("c3"))
        assertFalse(resolver.isShared("c3"))

        projects.delete(project.id)
        assertEquals(WorkspaceKey.Conversation("c2"), resolver.resolve("c2"))
        assertEquals(WorkspaceKey.Conversation("draft-1"), resolver.resolve("draft-1"))
    }

    @Test fun wireEncodingRoundTripsAndRejectsUnsafeProjectPrefix() {
        val conv = WorkspaceKey.Conversation("chat-1")
        assertEquals("chat-1", conv.wire)
        assertEquals(conv, WorkspaceKey.parse(conv.wire))

        val proj = WorkspaceKey.Project("proj_1")
        assertEquals("p:proj_1", proj.wire)
        assertEquals(proj, WorkspaceKey.parse(proj.wire))

        val malicious = WorkspaceKey.parse("p:../escape")
        assertEquals(WorkspaceKey.Conversation("p:../escape"), malicious)
    }

    @Test fun setDraftProjectTracksAndClearsPendingDraft() {
        val conversations = ConversationStore(tmp.newFolder("chats2"))
        val projects = ProjectStore(tmp.newFolder("projects2"))
        val project = projects.create("工程").getOrThrow()
        val resolver = WorkspaceResolver(conversations, projects)

        resolver.setDraftProject("draft-1", project.id)
        assertEquals(WorkspaceKey.Project(project.id), resolver.resolve("draft-1"))
        assertTrue(resolver.isShared("draft-1"))

        resolver.setDraftProject("draft-1", null)
        assertEquals(WorkspaceKey.Conversation("draft-1"), resolver.resolve("draft-1"))
        assertFalse(resolver.isShared("draft-1"))
    }
}

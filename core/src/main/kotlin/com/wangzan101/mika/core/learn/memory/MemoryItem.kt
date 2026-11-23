package com.wangzan101.mika.core.learn.memory

import kotlinx.serialization.Serializable

@Suppress("EnumEntryName")
enum class MemoryKind { fact, preference, procedure }

/** 只有做法会处于 pending(待用户批准)。 */
@Suppress("EnumEntryName")
enum class MemoryStatus { active, pending }

/**
 * [type]:`user_said`(带用户原话 [quote])、`manual`(用户在设置里手写),或 `inferred`——用户让 Mika 记、内容却是 Mika
 * 自己归纳出来的(里面有用户没说过的具体值);这时 [quote] 是用户提出要求的那句话,不是内容的出处。只有用户打开了
 * 「让 Mika 自己归纳着记」才会有这一种(见 [MemoryStore.inferenceAllowed]);交给任务和界面时都标明不是用户说的。
 */
@Serializable
data class MemorySource(
    val type: String,
    val quote: String? = null,
    val conversationId: String? = null,
    val atMs: Long,
    val channel: String? = null,
)

/** [replaces]:被这次更新掉的旧内容,只留一版(界面「原来:…」)。 */
@Serializable
data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val keywords: List<String>,
    val source: MemorySource,
    val status: MemoryStatus,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
    val replaces: String? = null,
)

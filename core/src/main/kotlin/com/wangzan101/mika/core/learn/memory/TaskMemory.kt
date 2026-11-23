package com.wangzan101.mika.core.learn.memory

import kotlinx.serialization.Serializable

/** 一次任务时间线上的一步:什么时间、做了什么(给人看的一句话)、那一步读到的原文(按需单独取)。 */
@Serializable
data class TaskMemoryStep(
    val atMs: Long,
    val label: String,
    val detail: String,
)

/**
 * 一次任务的跨会话摘要:目标、一句话结论、动过的 App(包名)与网站(域名)、时间线上的步骤。
 * 任务结束时自动写,加密落盘;检索时分三层给:先索引(goal / conclusion / apps / sites 的紧凑条目),
 * 再时间线(steps 的 label),最后某一步的原文(detail)。App 存包名是为了按 App 稳定过滤与追溯。
 */
@Serializable
data class TaskMemory(
    val id: String,
    val goal: String,
    val conclusion: String,
    val apps: List<String>,
    val sites: List<String>,
    val createdAt: Long,
    val steps: List<TaskMemoryStep>,
)

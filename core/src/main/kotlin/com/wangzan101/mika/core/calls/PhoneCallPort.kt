package com.wangzan101.mika.core.calls

import kotlinx.serialization.json.JsonObject

/** Native phone calls. Permission and local opt-in are checked by the Android implementation. */
interface PhoneCallPort {
    suspend fun state(): JsonObject
    /** [ownerConversationId] 是发起这通电话的任务所在的对话;电话里的前台据此读这件任务的状态,没有任务时为 null。 */
    suspend fun dial(number: String, simId: String? = null, task: PhoneCallTask? = null, ownerConversationId: String? = null): JsonObject
    /** [callId] must still identify the exact call reported by [state]. */
    suspend fun answer(callId: String): JsonObject
    suspend fun hangup(callId: String): JsonObject
    suspend fun takeover(): JsonObject
}

internal const val CALL_ID_FIELD = "call_id"
internal const val MAX_CALL_ID_LENGTH = 64

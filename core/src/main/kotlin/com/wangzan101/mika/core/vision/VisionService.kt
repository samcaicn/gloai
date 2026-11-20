package com.wangzan101.mika.core.vision

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role

/**
 * 让视觉模型看图回答一个问题。只在设置里有能看图的模型时才建;
 * 调用方负责不把受保护页面、付款页的画面传进来。
 *
 * 现已支持通用多级瀑布流备援 (Waterfall Chain):
 * 主视觉模型遇 429、503、超时或配额耗尽时，自动平滑切换到备援视觉模型，保障任务不中断、不致盲。
 */
class VisionService(
    private val model: ChatModel,
    val ops: ImageOps,
    private val fallbacks: List<ChatModel> = emptyList(),
    private val onModelSwitch: ((from: ChatModel, to: ChatModel, error: ModelError) -> Unit)? = null,
) {
    /** 构造完整视觉瀑布流链表 */
    constructor(
        models: List<ChatModel>,
        ops: ImageOps,
        onModelSwitch: ((from: ChatModel, to: ChatModel, error: ModelError) -> Unit)? = null,
    ) : this(models.first(), ops, models.drop(1), onModelSwitch)

    /** 视觉模型链：主模型 + 所有备援模型 */
    val chain: List<ChatModel> = listOf(model) + fallbacks

    /** 不区分实际应答模型时,只有整条链均已验证才可承诺定位可信。 */
    val groundingVerified: Boolean get() = chain.all { it.capabilities.groundingVerified }

    data class Answer(val text: String, val groundingVerified: Boolean)

    /**
     * 不带工具的一次调用。
     * 当主视觉模型抛出 [ModelError] 时，若链路上有备援模型，自动切换并继续执行。
     */
    suspend fun ask(system: String, text: String, images: List<ByteArray>): String =
        askWithGrounding(system, text, images).text

    /** 可信度随本次回答返回,并发或后续复看不能改变坐标所属模型的属性。 */
    suspend fun askWithGrounding(system: String, text: String, images: List<ByteArray>): Answer {
        val parts = listOf(Part.Text(text)) + images.map { ModelImage.part(it, ops) }
        val messages = listOf(Message(Role.User, parts, Author.Mika))
        var lastError: ModelError? = null

        for (i in chain.indices) {
            val candidate = chain[i]
            try {
                val reply = candidate.complete(system, messages, emptyList())
                val answer = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                if (answer.isBlank()) throw ModelError.BadResponse("视觉模型没有给出回答")
                return Answer(answer, candidate.capabilities.groundingVerified)
            } catch (e: ModelError) {
                lastError = e
                val next = chain.getOrNull(i + 1)
                if (next != null) {
                    onModelSwitch?.invoke(candidate, next, e)
                    continue
                }
            }
        }
        throw lastError ?: ModelError.BadResponse("视觉模型没有给出回答")
    }
}

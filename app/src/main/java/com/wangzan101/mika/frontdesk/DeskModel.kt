package com.wangzan101.mika.frontdesk

import android.content.Context
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.modelFor
import com.wangzan101.mika.voice.model.ThinkModel
import com.wangzan101.mika.voice.model.VoiceModelEnvironment

/**
 * 前台(聊天和通话)用的思考模型:按「通话方式」里选的轻/主模型取;选了轻模型但没配时退回主模型。
 * 两个都没配返回 null,入口据此引导用户先去设置,不静默失败。
 */
fun deskModel(context: Context): ChatModel? {
    val think = VoiceModelEnvironment.get(context).catalog.state.value.selections.think
    val store = ProviderStore.get(context)
    return when (think) {
        ThinkModel.Light -> store.modelFor(ModelRole.Light) ?: store.modelFor(ModelRole.Main)
        ThinkModel.Main -> store.modelFor(ModelRole.Main)
    }
}

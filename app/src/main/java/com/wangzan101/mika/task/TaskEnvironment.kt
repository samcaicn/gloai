package com.wangzan101.mika.task

import android.content.Context
import com.wangzan101.mika.core.model.decision.DecisionModel
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.settings.DecisionSettings
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.lightFallbackChain
import com.wangzan101.mika.settings.modelFor
import com.wangzan101.mika.settings.visionFallbackChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * 任务从 App 拿的:模型从哪来、评测钩子、显示中的任务此刻有没有屏幕画面。整个进程一份,由 App 给出;
 * 测试换掉里面的模型来源与钩子。
 */
class TaskEnvironment {
    /** 显示中的任务的虚拟屏编号;没有任务、或还没建过屏(用到系统命令、事件工具或第一次打开 App 时才建)时为 -1。 */
    val agentDisplayId = MutableStateFlow(-1)
    /** 显示中的任务已经在虚拟屏上打开过 App(屏建好了不算:只跑系统命令时屏是空的)。 */
    val agentAppShown = MutableStateFlow(false)
    /** 显示中的任务正在用内置浏览器(画中画要显示网页画面)。 */
    val webScreenOpen = MutableStateFlow(false)
    /** 画中画有画面可看:虚拟屏上打开过 App,或在用内置浏览器。只建了空屏的任务不算。 */
    val previewOpen: Flow<Boolean> = combine(agentAppShown, webScreenOpen) { app, web -> app || web }

    // 三个工厂的 ModelSession 是这次调用属于的对话(ModelSession.of(对话 id)):同一段对话里建的模型都给同一个,
    // 服务商要会话号的就照它路由、复用缓存

    /** 主模型来自设置里的模型配置;测试可换成脚本模型。 */
    @Volatile var modelFactory: (Context, ModelSession) -> ChatModel? = defaultModelFactory
    /** 轻量模型(列成功标准等);为空时由主模型兼任。测试换主模型时一并换掉,免得调到设置里的真模型。 */
    @Volatile var lightModelFactory: (Context, ModelSession) -> ChatModel? = defaultLightModelFactory
    /** 视觉模型(核查时看截图);设置里没有能看图的模型时为空。 */
    @Volatile var visionModelFactory: (Context, ModelSession) -> ChatModel? = defaultVisionModelFactory
    /** 视觉模型备援链 */
    @Volatile var visionFallbackFactory: (Context, ModelSession) -> List<ChatModel> = defaultVisionFallbackFactory
    /** 轻量模型备援链 */
    @Volatile var lightFallbackFactory: (Context, ModelSession) -> List<ChatModel> = defaultLightFallbackFactory
    /** 独立的决策接口,不占主/视觉/轻量用途。测试可注入;替换主模型时不隐式调用真实决策服务。 */
    @Volatile var decisionModelFactory: (Context) -> DecisionModel? = defaultDecisionModelFactory

    fun decisionModel(context: Context): DecisionModel? =
        if (modelFactory !== defaultModelFactory && decisionModelFactory === defaultDecisionModelFactory) null
        else decisionModelFactory(context)

    /** 评测跑器用的钩子;产品里全是默认值。 */
    val evalHooks = EvalHooks()

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val tasks: TaskEnvironment
    }

    companion object {
        val defaultModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Main, s) }
        val defaultLightModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Light, s) }
        val defaultVisionModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Vision, s) }
        val defaultVisionFallbackFactory: (Context, ModelSession) -> List<ChatModel> = { c, s -> ProviderStore.get(c).visionFallbackChain(s) }
        val defaultLightFallbackFactory: (Context, ModelSession) -> List<ChatModel> = { c, s -> ProviderStore.get(c).lightFallbackChain(s) }
        val defaultDecisionModelFactory: (Context) -> DecisionModel? = { c -> DecisionSettings.get(c).createModel() }

        fun of(context: Context): TaskEnvironment = (context.applicationContext as Owner).tasks
    }
}

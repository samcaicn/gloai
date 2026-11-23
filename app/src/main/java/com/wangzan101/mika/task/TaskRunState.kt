package com.wangzan101.mika.task

import android.graphics.Bitmap
import com.wangzan101.mika.cap.phone.display.PipInput
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.chat.GeneratedAudio
import com.wangzan101.mika.core.chat.GeneratedPhoto
import com.wangzan101.mika.core.cap.image.SavedImage
import com.wangzan101.mika.core.model.SavedAudio
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一个任务的界面可见状态：14 个 [Mirrored] 状态流 + 发布锁 + 结束标志。
 * 原 TaskRun 里的状态机整体搬到这里；[shownRef] 替代原 `service.taskState.shownRun === this@TaskRun` 的判断。
 * 字段用 [internal] 暴露给同模块的 TaskRun 及其拆出的子对象直接读写（这是纯移动，不引入新的封装）。
 */
internal class TaskRunState(
    private val service: TaskService,
    private val conversationId: String,
    private val env: TaskEnvironment,
    private val shownRef: () -> Boolean,
) {
    internal inner class Mirrored<T>(initial: T, private val shown: MutableStateFlow<T>) {
        private val own = MutableStateFlow(initial)
        val flow: StateFlow<T> = own.asStateFlow()
        var value: T
            get() = own.value
            set(v) {
                synchronized(service.taskState.shownLock) {
                    own.value = v
                    if (shownRef()) shown.value = v
                }
            }
        fun copyOut() { shown.value = own.value }
    }

    internal val _runId = Mirrored<String?>(null, service.taskState._runId)
    internal val _sharedFiles = Mirrored<List<SharedFile>>(emptyList(), service.taskState._sharedFiles)
    internal val _audios = Mirrored<List<GeneratedAudio>>(emptyList(), service.taskState._audios)
    internal val _photos = Mirrored<List<GeneratedPhoto>>(emptyList(), service.taskState._photos)
    internal val _state = Mirrored<TaskState>(TaskState.Running(0, TaskService.PREPARING), service.taskState._state)
    /** 同一状态带上对话编号,和 [_state] 一起写:界面据此只收屏上那个对话的状态。 */
    internal val _tagged = Mirrored<Pair<String, TaskState>?>(conversationId to _state.value, service.taskState._shownTask)
    internal val _takeoverStage = Mirrored(Takeover.Stage.None, service.taskState._takeoverStage)
    internal val _frames = Mirrored<Bitmap?>(null, service.taskState._frames)
    /** 个人助手的缩略图只收本任务获准展示的手机画面。 */
    internal val _phoneFrames = MutableStateFlow<Bitmap?>(null)
    internal val _activities = Mirrored<List<RunEvent.Activity>>(emptyList(), service.taskState._activities)
    /** 活动只追加;发布的是前 n 条的只读视图,每来一条不复制已有的。 */
    internal val activityLog = ArrayList<RunEvent.Activity>()
    internal val _steers = Mirrored<List<SteerNote>>(emptyList(), service.taskState._steers)
    internal val _displayId = Mirrored(-1, env.agentDisplayId)
    /** 小窗直接操作的口子:屏建好才有,释放时清空。 */
    internal val _pipInput = Mirrored<PipInput?>(null, service.taskState._pipInput)
    /** 小窗目标 App 的声音被用户恢复过(按钮转向「静音」);跟着真实音频记录走,手机侧清掉时回到 false。 */
    internal val _pipSoundOn = Mirrored(false, service.taskState._pipSoundOn)
    /** 虚拟屏上打开过 App:画中画据此才出来(只建了空屏、没打开过 App 时没有画面)。 */
    internal val _appShown = Mirrored(false, env.agentAppShown)
    /** 正在用内置浏览器:画中画改显示网页画面。 */
    internal val _webOpen = Mirrored(false, env.webScreenOpen)

    val state: StateFlow<TaskState> = _state.flow
    val runId: StateFlow<String?> = _runId.flow
    /** 本任务导出的文件;结束时随这一轮存进对话。 */
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.flow
    /** 本任务生成并留在工作区里的音频;结束时随这一轮存进对话。 */
    val audios: StateFlow<List<GeneratedAudio>> = _audios.flow
    /** 本任务生成并留在工作区里的图;结束时随这一轮存进对话。 */
    val photos: StateFlow<List<GeneratedPhoto>> = _photos.flow
    val takeoverStage: StateFlow<Takeover.Stage> = _takeoverStage.flow
    val frames: StateFlow<Bitmap?> = _frames.flow
    val phoneFrames: StateFlow<Bitmap?> = _phoneFrames.asStateFlow()
    /** 本任务到此刻的活动(实际做过的事);悬浮胶囊的卡片按任务取,不只看显示中的那个。 */
    val activities: StateFlow<List<RunEvent.Activity>> = _activities.flow
    /** 本任务的虚拟屏编号;屏到用到系统命令、事件工具或第一次打开 App 时才建,之前和释放之后为 -1。 */
    val displayId: StateFlow<Int> = _displayId.flow
    val pipInput: StateFlow<PipInput?> = _pipInput.flow
    val pipSoundOn: StateFlow<Boolean> = _pipSoundOn.flow
    val webOpen: StateFlow<Boolean> = _webOpen.flow
    /** 本任务进行中插的话和它们的去向;悬浮胶囊的对话卡片按任务取。 */
    val steers: StateFlow<List<SteerNote>> = _steers.flow

    /** 带对话编号的那份先写:收不带编号的状态时读到的编号就已经是这个任务的。 */
    private val mirrored = listOf(_runId, _sharedFiles, _audios, _photos, _tagged, _state, _takeoverStage, _frames, _displayId, _pipInput, _pipSoundOn, _appShown, _webOpen, _activities, _steers)

    /** 发布与收尾互斥;[ended] 之后不再写状态,迟到的发布不能盖掉终态或重新拿锁。 */
    internal val publishLock = Any()
    internal var ended = false

    internal fun setState(s: TaskState) = synchronized(service.taskState.shownLock) {
        _tagged.value = conversationId to s
        _state.value = s
    }

    /** 成为显示中的任务时,把自己的各项抄到服务对外的流。 */
    internal fun copyOut() = synchronized(service.taskState.shownLock) {
        mirrored.forEach { it.copyOut() }
    }

    /** 此刻要显示的状态;已收尾时为空,不再重画通知。 */
    internal fun shownState(): TaskState? = synchronized(publishLock) { if (ended) null else _state.value }

    internal fun logView(n: Int): List<RunEvent.Activity> = object : AbstractList<RunEvent.Activity>() {
        override val size = n
        override fun get(index: Int): RunEvent.Activity = synchronized(activityLog) { activityLog[index] }
    }

    /** 沙箱导出了一个文件(share_file 或 `mika share`)。 */
    fun addShared(f: SharedFile) = synchronized(service.taskState.shownLock) { _sharedFiles.value = _sharedFiles.value + f }

    /** 音频能力生成了一段音频(写进工作区时由端口回调)。 */
    fun addAudio(a: GeneratedAudio) = synchronized(service.taskState.shownLock) {
        _audios.value = _audios.value + a
    }

    /** 音频能力把一段音频存进了音乐库:当作这一轮导出的文件,聊天里出现卡片。 */
    fun addSavedAudio(s: SavedAudio) {
        addShared(SharedFile(s.savedAs.substringAfterLast('/'), s.bytes, s.savedAs, s.uri))
    }

    /** 图片能力生成了一张图(写进工作区时由端口回调)。 */
    fun addPhoto(p: GeneratedPhoto) = synchronized(service.taskState.shownLock) {
        _photos.value = _photos.value + p
    }

    /** 图片能力把一张图存进了相册:当作这一轮导出的文件,聊天里出现「已存入相册」的卡片。 */
    fun addSaved(s: SavedImage) {
        addShared(SharedFile(s.savedAs.substringAfterLast('/'), s.bytes, s.savedAs, s.uri))
    }

    fun clearTakeoverStage() { _takeoverStage.value = Takeover.Stage.None }

    fun draftText(): String? = (_state.value as? TaskState.Running)?.preview?.takeIf(String::isNotBlank)

    fun hasStopMaterial(draft: String?): Boolean = synchronized(activityLog) { activityLog.isNotEmpty() } || !draft.isNullOrBlank()
}

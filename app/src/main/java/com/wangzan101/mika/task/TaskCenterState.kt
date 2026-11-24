package com.wangzan101.mika.task

import android.graphics.Bitmap
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.remote.RemoteSteer
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * 任务进行中用户在同一对话里插的话:[Pending] 刚发出、还没送到任务,[Received] 任务的收件箱已收下(下一步就处理),
 * [Joined] 已交给当前任务的模型,[Next] 任务已收尾、作为下一件事,[Cancelled] 停止或当前任务收尾时没用上的远程插话,[Withdrawn] 用户已撤回。
 */
enum class SteerState { Pending, Received, Joined, Next, Cancelled, Withdrawn }

/** 还没交给模型的插话(任务就此收尾时作为下一件事,停止时作废)。 */
val SteerState.unconsumed: Boolean get() = this == SteerState.Pending || this == SteerState.Received

/**
 * [interrupted] 是这句插话撤掉的那段回复里用户已经看到的字;[finished] 是这句插话交给模型之前、已经说完显示出来的回复。
 * 两者都显示在插话上方;没有时为 null。
 */
data class SteerNote(
    val id: String, val text: String, val state: SteerState, val interrupted: String? = null, val finished: String? = null,
    val remote: Boolean = false,
)

/** 电脑只看到自己追加的消息去向,不带手机上插的话或正文;远程插话撤不回(`RunInbox.withdrawSteer`),[SteerState.Withdrawn] 只为穷举,按没用上报。 */
internal fun remoteSteers(notes: List<SteerNote>, finished: Boolean = false): List<RemoteSteer> = notes.filter { it.remote }.map {
    RemoteSteer(it.id, when (it.state) {
        SteerState.Pending, SteerState.Received -> if (finished) "not_applied" else "received"
        SteerState.Joined -> "joined"
        SteerState.Next, SteerState.Cancelled, SteerState.Withdrawn -> "not_applied"
    })
}

/**
 * 运行事件里插话交给模型的先后:`reply_interrupted`([interrupted])或 `reply_finished_before_steer`([finished])先到,
 * 紧接着这一批插话逐句 `steer_received`,那段字只挂到其中第一句上([joined])。
 */
class SteerJoin {
    @Volatile private var cut: String? = null
    @Volatile private var done: String? = null

    fun interrupted(text: String) { cut = text }

    fun finished(text: String) { done = text }

    /** [id] 这句已交给模型:标成 [SteerState.Joined],有刚记下的那段字就挂上(用过即清)。 */
    fun joined(notes: List<SteerNote>, id: String): List<SteerNote> {
        val c = cut.also { cut = null }
        val d = done.also { done = null }
        return notes.map {
            if (it.id == id) it.copy(state = SteerState.Joined, interrupted = c ?: it.interrupted, finished = d ?: it.finished) else it
        }
    }
}

/** 进程内 TaskCenter 的展示状态，由 App 容器持有，Service 只是写入它的宿主。 */
class TaskCenterState {
    val shownLock = Any()
    @Volatile var shownRun: TaskRun? = null

    val _runs = MutableStateFlow<List<TaskRun>>(emptyList())
    val runs: StateFlow<List<TaskRun>> = _runs.asStateFlow()
    val _activeConversations = MutableStateFlow<List<String>>(emptyList())
    val activeConversations: StateFlow<List<String>> = _activeConversations.asStateFlow()
    val _shownTask = MutableStateFlow<Pair<String, TaskState>?>(null)
    val shownTask: StateFlow<Pair<String, TaskState>?> = _shownTask.asStateFlow()
    fun run(conversationId: String): TaskRun? = _runs.value.firstOrNull { it.conversationId == conversationId }
    fun show(conversationId: String) { run(conversationId)?.show() }

    @Volatile var secondDisplayFailed = false
    val _parallelLimit = MutableStateFlow(ParallelLimit(1, null))
    val parallelLimit: StateFlow<ParallelLimit> = _parallelLimit.asStateFlow()
    fun markSecondDisplayFailed() {
        secondDisplayFailed = true
        _parallelLimit.value = ParallelLimit(1, ParallelCapability.SECOND_DISPLAY_FAILED)
    }

    val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()
    val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val _takeoverStage = MutableStateFlow(Takeover.Stage.None)
    val takeoverStage: StateFlow<Takeover.Stage> = _takeoverStage.asStateFlow()
    val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()
    val _runId = MutableStateFlow<String?>(null)
    val _sharedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.asStateFlow()
    /** 显示中的任务到此刻生成的音频;结束时随这一轮存进对话。 */
    val _audios = MutableStateFlow<List<com.wangzan101.mika.core.chat.GeneratedAudio>>(emptyList())
    val audios: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedAudio>> = _audios.asStateFlow()
    /** 显示中的任务到此刻生成的图;结束时随这一轮存进对话。 */
    val _photos = MutableStateFlow<List<com.wangzan101.mika.core.chat.GeneratedPhoto>>(emptyList())
    val photos: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedPhoto>> = _photos.asStateFlow()
    val _endedRunId = MutableStateFlow<String?>(null)
    val endedRunId: StateFlow<String?> = _endedRunId.asStateFlow()
    val _endedRunIds = MutableStateFlow<Map<String, String?>>(emptyMap())
    val endedRunIds: StateFlow<Map<String, String?>> = _endedRunIds.asStateFlow()
    /** 显示中的任务到此刻的活动(实际做过的事,来自运行事件)。 */
    val _activities = MutableStateFlow<List<RunEvent.Activity>>(emptyList())
    val activities: StateFlow<List<RunEvent.Activity>> = _activities.asStateFlow()
    /** 显示中的任务收到的插话和它们的去向。 */
    val _steers = MutableStateFlow<List<SteerNote>>(emptyList())
    val steers: StateFlow<List<SteerNote>> = _steers.asStateFlow()
    /** 停止之后正在写交代的那几次运行(运行编号);写好记进对话、或写不成,就拿掉。 */
    val _summarizing = MutableStateFlow<Set<String>>(emptySet())
    val summarizing: StateFlow<Set<String>> = _summarizing.asStateFlow()
    val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()
    /** 显示中任务的小窗输入口:只有建了虚拟屏、开着「直接操作」开关时才有,屏释放后回到 null。 */
    val _pipInput = MutableStateFlow<com.wangzan101.mika.cap.phone.display.PipInput?>(null)
    val pipInput: StateFlow<com.wangzan101.mika.cap.phone.display.PipInput?> = _pipInput.asStateFlow()
    /** 显示中任务的小窗目标声音是否被用户恢复过:按钮据此显示「恢复声音」或「静音」。 */
    val _pipSoundOn = MutableStateFlow(false)
    val pipSoundOn: StateFlow<Boolean> = _pipSoundOn.asStateFlow()
}

/** 此刻有虚拟屏(或开着网页)的任务所在的会话;没人看时不跟。通话页的任务卡据此显示「看画面」。 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TaskCenterState.screenConversations(scope: CoroutineScope): StateFlow<Set<String>> =
    runs.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptySet())
        else combine(list.map { run ->
            combine(run.displayId, run.webOpen) { display, web -> run.conversationId.takeIf { display >= 0 || web } }
        }) { ids -> ids.filterNotNull().toSet() }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptySet())

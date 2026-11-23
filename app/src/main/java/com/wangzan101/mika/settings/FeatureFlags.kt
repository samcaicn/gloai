package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.capsule.CapsuleCardScale
import com.wangzan101.mika.capsule.CapsuleDock
import com.wangzan101.mika.capsule.CardDocking
import com.wangzan101.mika.capsule.CapsuleIdleOpacity
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.capsule.DockSide
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.chat.AutoArchive
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import com.wangzan101.mika.core.model.usage.TokenBudgetLimits
import com.wangzan101.mika.task.ParallelCapability
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalTime

/** 功能开关。提速开关存成 [SpeedFlags.describe] 的格式,评测跑器可用参数覆盖。 */
class FeatureFlags(private val prefs: SharedPreferences) {
    fun scheduledQuietHours(): ScheduledQuietHours {
        val parts = prefs.getString(SCHEDULED_QUIET_HOURS, null)?.split(',')
        if (parts?.size != 3) return ScheduledQuietHours.DEFAULT
        return runCatching {
            ScheduledQuietHours(parts[0].toBooleanStrict(), LocalTime.parse(parts[1]), LocalTime.parse(parts[2]))
        }.getOrDefault(ScheduledQuietHours.DEFAULT)
    }

    fun setScheduledQuietHours(hours: ScheduledQuietHours) {
        prefs.edit().putString(SCHEDULED_QUIET_HOURS, "${hours.enabled},${hours.start},${hours.end}").apply()
    }

    fun personalStart(): Boolean = prefs.getBoolean(PERSONAL_START, false)

    fun setPersonalStart(on: Boolean) {
        prefs.edit().putBoolean(PERSONAL_START, on).apply()
    }

    /** 还没做过的默认值升级(见 [SPEED_UPGRADES])各做一次;升级之后用户手动关掉就保持关闭。 */
    fun speedFlags(): SpeedFlags {
        val flags = prefs.getString(SPEED_FLAGS, null)?.let { runCatching { SpeedFlags.parse(it) }.getOrNull() } ?: SpeedFlags.OFF
        val pending = SPEED_UPGRADES.filter { (key, _) -> !prefs.getBoolean(key, false) }
        if (pending.isEmpty()) return flags
        val upgraded = pending.fold(flags) { f, (_, upgrade) -> upgrade(f) }
        setSpeed(upgraded)
        return upgraded
    }

    fun setSpeed(flags: SpeedFlags) {
        val edit = prefs.edit().putString(SPEED_FLAGS, flags.describe())
        SPEED_UPGRADES.forEach { (key, _) -> edit.putBoolean(key, true) }
        edit.commit()
    }

    /** 并行任务时最多同时跑几个(用户设定,1–3);真正的上限还要看设备,见 ParallelCapability。 */
    fun parallelMax(): Int = prefs.getInt(PARALLEL_MAX, ParallelCapability.DEFAULT_MAX)

    fun setParallelMax(max: Int) {
        prefs.edit().putInt(PARALLEL_MAX, max).commit()
    }

    /** 每个任务最多走多少步;null 表示不限制。默认不限制,下次任务起生效。 */
    fun taskStepLimit(): Int? = prefs.getInt(TASK_STEP_LIMIT, 0).takeIf { it in 1..MAX_TASK_STEP_LIMIT }

    fun setTaskStepLimit(limit: Int?) {
        prefs.edit().putInt(TASK_STEP_LIMIT, (limit ?: 0).coerceIn(0, MAX_TASK_STEP_LIMIT)).commit()
    }

    /**
     * 定时任务一次最多跑多少分钟(无人在场,卡住了没人停);null 表示不限制。默认不限制,和步数上限一样,下次触发起生效。
     * 只认 [TRIGGER_RUN_MINUTES] 里的几档,存了别的值按不限制算。
     */
    fun triggerRunMinutes(): Int? = prefs.getInt(TRIGGER_RUN_LIMIT, 0).takeIf { it in TRIGGER_RUN_MINUTES }

    fun setTriggerRunMinutes(minutes: Int?) {
        prefs.edit().putInt(TRIGGER_RUN_LIMIT, minutes?.takeIf { it in TRIGGER_RUN_MINUTES } ?: 0).commit()
    }

    /**
     * 一个任务里沙箱命令累计最多跑多少分钟(装依赖、编译这类活容易超);只认 [SANDBOX_BUDGET_MINUTES] 里的几档,
     * 没设或存了别的值按默认(与 [SANDBOX_BUDGET_MS] 一致)算,下一个任务起生效。
     */
    fun sandboxBudgetMinutes(): Int =
        prefs.getInt(SANDBOX_BUDGET_LIMIT, 0).takeIf { it in SANDBOX_BUDGET_MINUTES } ?: DEFAULT_SANDBOX_BUDGET_MINUTES

    fun setSandboxBudgetMinutes(minutes: Int) {
        prefs.edit().putInt(SANDBOX_BUDGET_LIMIT, minutes.takeIf { it in SANDBOX_BUDGET_MINUTES } ?: 0).commit()
    }

    /**
     * 单个上传文件(聊天附件、微信图片、电脑发任务带的文件)最大多少 MB;只认 [ATTACHMENT_FILE_LIMIT_MB] 里的几档,
     * 没设或存了别的值按默认(与 Attachments.MAX_BYTES 一致)算,改完下一次导入生效。
     */
    fun attachmentMaxMb(): Int =
        prefs.getInt(ATTACHMENT_FILE_LIMIT, 0).takeIf { it in ATTACHMENT_FILE_LIMIT_MB } ?: DEFAULT_ATTACHMENT_FILE_LIMIT_MB

    fun attachmentMaxBytes(): Long = attachmentMaxMb().toLong() shl 20

    fun setAttachmentMaxMb(mb: Int) {
        prefs.edit().putInt(ATTACHMENT_FILE_LIMIT, mb.takeIf { it in ATTACHMENT_FILE_LIMIT_MB } ?: 0).commit()
    }

    /**
     * 一个工作区(对话沙箱目录)最多占多少 GB;只认 [WORKSPACE_LIMIT_GB] 里的几档,
     * 没设或存了别的值按默认(与 Attachments.WORKSPACE_MAX 一致)算。
     */
    fun workspaceMaxGb(): Int =
        prefs.getInt(WORKSPACE_LIMIT, 0).takeIf { it in WORKSPACE_LIMIT_GB } ?: DEFAULT_WORKSPACE_LIMIT_GB

    fun workspaceMaxBytes(): Long = workspaceMaxGb().toLong() shl 30

    /** 聊天附件与设备文件导入共用的体积档位,每次取用都是当前设置。 */
    fun attachmentLimits() = com.wangzan101.mika.cap.sandbox.AttachmentLimits(attachmentMaxBytes(), workspaceMaxBytes())

    fun setWorkspaceMaxGb(gb: Int) {
        prefs.edit().putInt(WORKSPACE_LIMIT, gb.takeIf { it in WORKSPACE_LIMIT_GB } ?: 0).commit()
    }

    /** 每个任务允许已报告的输入加输出 Token 总量;null 表示不限制,下次任务起生效。 */
    fun taskTokenBudget(): Long? = prefs.getLong(TASK_TOKEN_BUDGET, 0L).takeIf(TokenBudgetLimits::valid)

    fun setTaskTokenBudget(limit: Long?) {
        prefs.edit().putLong(TASK_TOKEN_BUDGET, limit?.takeIf(TokenBudgetLimits::valid) ?: 0L).commit()
    }

    /** 主动能力每天(本地日历日,各服务商合计)允许已报告的 Token;没设或不合法时按默认值,没有「不限制」。 */
    fun proactiveDailyTokenBudget(): Long =
        ProactiveBudget.limitOf(prefs.getLong(PROACTIVE_DAILY_TOKEN_BUDGET, 0L))

    /** 不在 [TokenBudgetLimits] 范围内的值(含 null)存成「没设」,读出来就是默认值。 */
    fun setProactiveDailyTokenBudget(limit: Long?) {
        prefs.edit().putLong(PROACTIVE_DAILY_TOKEN_BUDGET, limit?.takeIf(TokenBudgetLimits::valid) ?: 0L).commit()
    }

    /** 干活时屏幕保持常亮;默认开,用户可以关。 */
    fun keepScreenOn(): Boolean = prefs.getBoolean(KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(on: Boolean) {
        prefs.edit().putBoolean(KEEP_SCREEN_ON, on).commit()
    }

    fun showTaskCost(): Boolean = prefs.getBoolean(SHOW_TASK_COST, false)

    fun setShowTaskCost(on: Boolean) {
        prefs.edit().putBoolean(SHOW_TASK_COST, on).apply()
    }

    fun showTaskCostChanges(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SHOW_TASK_COST) trySend(showTaskCost())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(showTaskCost())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** 干活时把被操作的 App 静音;默认开,用户可以关。 */
    fun muteTarget(): Boolean = prefs.getBoolean(MUTE_TARGET, true)

    fun setMuteTarget(on: Boolean) {
        prefs.edit().putBoolean(MUTE_TARGET, on).commit()
    }

    /** 在 App 里操作的节奏:快、标准(默认)、像人(见 [OperatingPace])。 */
    fun operatingPace(): OperatingPace = OperatingPace.parse(prefs.getString(OPERATING_PACE, null)) ?: OperatingPace.DEFAULT

    fun setOperatingPace(pace: OperatingPace) {
        prefs.edit().putString(OPERATING_PACE, pace.name).commit()
    }

    /**
     * 按应用的操作节奏覆盖(包名 → 档位)。列表还没初始化过时先把内置 App 知识标 careful 的 App 预置为「像人」
     * (见 [GuideCatalog.carefulApps]);之后运行时只看这份列表,App 知识的 pace 不再参与。
     * 解析失败按没初始化处理(下次读取重新预置)。
     */
    fun appPaceOverrides(): Map<String, OperatingPace> {
        val stored = prefs.getString(APP_PACE_OVERRIDES, null)
        if (stored != null) {
            val parsed = runCatching { parseAppPaceOverrides(stored) }.getOrNull()
            if (parsed != null) return parsed
        }
        val seeded = GuideCatalog.loadBundled().carefulApps().associateWith { OperatingPace.HUMAN }
        setAppPaceOverrides(seeded)
        return seeded
    }

    /** 整表写回(设置页增删改后调用);空表也落盘(用户删光 = 明确不再按应用区分,别再重新预置)。 */
    fun setAppPaceOverrides(overrides: Map<String, OperatingPace>) {
        val json = overrides.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (pkg, pace) ->
            "\"${pkg.replace("\\", "\\\\").replace("\"", "\\\"")}\":\"${pace.name}\""
        }
        prefs.edit().putString(APP_PACE_OVERRIDES, json).commit()
    }

    private fun parseAppPaceOverrides(json: String): Map<String, OperatingPace> =
        kotlinx.serialization.json.Json.parseToJsonElement(json).let { o ->
            buildMap {
                for ((k, v) in (o as kotlinx.serialization.json.JsonObject)) {
                    OperatingPace.parse((v as kotlinx.serialization.json.JsonPrimitive).content)?.let { pace -> put(k, pace) }
                }
            }
        }

    /** 界面配色:跟随系统、一直浅色、一直深色(见 [ThemeMode])。界面跟着 [themeModeChanges] 当场换,不用重建。 */
    fun themeMode(): ThemeMode = ThemeMode.parse(prefs.getString(THEME_MODE, null))

    /** 外观的当前值,之后每次改动再发一次(设置页改的、任务里改的都一样)。 */
    fun themeModeChanges(): Flow<ThemeMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == THEME_MODE) trySend(themeMode())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(themeMode())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(THEME_MODE, mode.name).commit()
    }

    /** 强调色(见 [ThemeAccent]),默认蓝色。和外观一样跟着 [themeAccentChanges] 当场换,不用重建。 */
    fun themeAccent(): ThemeAccent = ThemeAccent.parse(prefs.getString(THEME_ACCENT, null))

    /** 强调色的当前值,之后每次改动再发一次。 */
    fun themeAccentChanges(): Flow<ThemeAccent> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == THEME_ACCENT) trySend(themeAccent())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(themeAccent())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setThemeAccent(accent: ThemeAccent) {
        prefs.edit().putString(THEME_ACCENT, accent.name).commit()
    }

    /** 背景图片怎么显示(见 [SkinSpec]);存的值超出范围时拉回范围内。跟着 [skinChanges] 当场换,不用重建。 */
    fun skin(): SkinSpec = SkinSpec(
        version = prefs.getLong(SKIN_VERSION, 0L),
        scrim = prefs.getInt(SKIN_SCRIM, SkinSpec.DEFAULT_SCRIM).coerceIn(SkinSpec.MIN_SCRIM, SkinSpec.MAX_SCRIM),
        blur = SkinBlur.parse(prefs.getString(SKIN_BLUR, null)),
        focus = prefs.getFloat(SKIN_FOCUS, 0f).coerceIn(-1f, 1f),
        accentFromImage = prefs.getBoolean(SKIN_ACCENT_FOLLOW, true),
        hue = if (prefs.contains(SKIN_HUE)) prefs.getFloat(SKIN_HUE, 0f) else null,
    )

    fun skinChanges(): Flow<SkinSpec> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in SKIN_KEYS) trySend(skin())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(skin())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setSkin(spec: SkinSpec) {
        prefs.edit()
            .putLong(SKIN_VERSION, spec.version)
            .putInt(SKIN_SCRIM, spec.scrim.coerceIn(SkinSpec.MIN_SCRIM, SkinSpec.MAX_SCRIM))
            .putString(SKIN_BLUR, spec.blur.name)
            .putFloat(SKIN_FOCUS, spec.focus.coerceIn(-1f, 1f))
            .putBoolean(SKIN_ACCENT_FOLLOW, spec.accentFromImage)
            .also { e -> if (spec.hue == null) e.remove(SKIN_HUE) else e.putFloat(SKIN_HUE, spec.hue) }
            .commit()
    }

    /**
     * 允许 Mika 改自己的设置(见 core 的 SelfConfig):模型与 Key、删数据不在允许之列。
     * 默认关;开着的时候任务里才有 settings_list、settings_set 这两件工具。
     */
    fun selfConfig(): Boolean = prefs.getBoolean(SELF_CONFIG, false)

    fun setSelfConfig(on: Boolean) {
        prefs.edit().putBoolean(SELF_CONFIG, on).commit()
    }

    /**
     * 允许 Mika 操作自己的界面(见 core 的 SelfPort):打开 Mika 的页、把工作区文件用 App 内查看器打开、
     * 发通知、写剪贴板。默认关;模型改不了这个开关。
     */
    fun selfOperate(): Boolean = prefs.getBoolean(SELF_OPERATE, false)

    fun setSelfOperate(on: Boolean) {
        prefs.edit().putBoolean(SELF_OPERATE, on).commit()
    }

    /**
     * 允许用户在对话里的虚拟屏小窗直接操作(点 / 滑 / 返回),不用把 App 整个挪到主屏。
     * 默认关:开着时小窗展开后点画面会真实注入到虚拟屏,手滑一下可能点歪 AI 正在跑的任务。
     */
    fun pipOperate(): Boolean = prefs.getBoolean(PIP_OPERATE, false)

    fun setPipOperate(on: Boolean) {
        prefs.edit().putBoolean(PIP_OPERATE, on).commit()
    }

    /**
     * 允许外部脚本经广播(com.wangzan101.mika.SCHEDULE)建定时任务;默认关。
     * 开着时仍受限流(每分钟几条)并标记来源为「外部脚本」,出事能追溯。
     */
    fun externalSchedule(): Boolean = prefs.getBoolean(EXTERNAL_SCHEDULE, false)

    fun setExternalSchedule(on: Boolean) {
        prefs.edit().putBoolean(EXTERNAL_SCHEDULE, on).commit()
    }

    /** 设备文件导入是实验功能,默认关闭,下次任务生效。 */
    fun deviceFiles(): Boolean = prefs.getBoolean(DEVICE_FILES, false)

    fun setDeviceFiles(on: Boolean) {
        prefs.edit().putBoolean(DEVICE_FILES, on).commit()
    }

    /** 自动归档:多久没有新消息的对话自动归档;0 表示关闭(默认)。 */
    fun autoArchiveDays(): Int = prefs.getInt(AUTO_ARCHIVE_DAYS, 0).takeIf { it in AutoArchive.DAYS } ?: 0

    fun setAutoArchiveDays(days: Int) {
        prefs.edit().putInt(AUTO_ARCHIVE_DAYS, days.takeIf { it in AutoArchive.DAYS } ?: 0).commit()
    }

    /** 主动能力(见 [com.wangzan101.mika.proactive.ProactiveRuntime]):读所选 App 的通知;默认关。 */
    fun proactive(): Boolean = prefs.getBoolean(PROACTIVE, false)

    fun setProactive(on: Boolean) {
        prefs.edit().putBoolean(PROACTIVE, on).commit()
    }

    /** 主动能力开关的当前值,之后每次改动再发一次(胶囊据此才去取「值得提醒」的条数,关着时不建主动能力)。 */
    fun proactiveChanges(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == PROACTIVE) trySend(proactive())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(proactive())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** 人格:默认开。开关留着,出问题时能整体关掉(关掉后界面不显示入口,任务也不读对话上的绑定)。 */
    fun persona(): Boolean = prefs.getBoolean(PERSONA, true)

    fun setPersona(on: Boolean) {
        prefs.edit().putBoolean(PERSONA, on).commit()
    }
    /** 悬浮胶囊:关闭、有任务时显示(默认)、一直显示。 */
    fun capsuleMode(): CapsuleMode = CapsuleMode.parse(prefs.getString(CAPSULE_MODE, null))

    fun setCapsuleMode(mode: CapsuleMode) {
        val previous = capsuleMode()
        val edit = prefs.edit().putString(CAPSULE_MODE, mode.name)
        val resume = if (mode != CapsuleMode.Off) mode else previous
        if (resume != CapsuleMode.Off) edit.putString(CAPSULE_RESUME_MODE, resume.name)
        edit.commit()
    }

    /** Quick Settings restores the user's previous non-off mode. */
    fun toggleCapsule() {
        val saved = CapsuleMode.parse(prefs.getString(CAPSULE_RESUME_MODE, null))
        setCapsuleMode(if (capsuleMode() == CapsuleMode.Off) saved.takeIf { it != CapsuleMode.Off } ?: CapsuleMode.Always else CapsuleMode.Off)
    }

    fun capsuleHideAtEdge(): Boolean = prefs.getBoolean(CAPSULE_HIDE_AT_EDGE, false)
    fun setCapsuleHideAtEdge(on: Boolean) { prefs.edit().putBoolean(CAPSULE_HIDE_AT_EDGE, on).commit() }
    fun capsuleHideAtEdgeChanges(): Flow<Boolean> = booleanChanges(CAPSULE_HIDE_AT_EDGE, ::capsuleHideAtEdge)

    fun capsuleHideFullscreen(): Boolean = prefs.getBoolean(CAPSULE_HIDE_FULLSCREEN, false)
    fun setCapsuleHideFullscreen(on: Boolean) { prefs.edit().putBoolean(CAPSULE_HIDE_FULLSCREEN, on).commit() }
    fun capsuleHideFullscreenChanges(): Flow<Boolean> = booleanChanges(CAPSULE_HIDE_FULLSCREEN, ::capsuleHideFullscreen)

    private fun booleanChanges(prefKey: String, value: () -> Boolean): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == null || changedKey == prefKey) trySend(value())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(value())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** 胶囊开关的当前值,之后每次改动再发一次。 */
    fun capsuleModeChanges(): Flow<CapsuleMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // 清空全部设置时 key 为 null
            if (key == null || key == CAPSULE_MODE) trySend(capsuleMode())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(capsuleMode())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** 胶囊静置后的不透明度;只认 20/40/70/100,默认 40。 */
    fun capsuleIdleOpacity(): CapsuleIdleOpacity = CapsuleIdleOpacity.parse(prefs.getInt(CAPSULE_IDLE_OPACITY, CapsuleIdleOpacity.DEFAULT.percent))

    fun setCapsuleIdleOpacity(opacity: CapsuleIdleOpacity) {
        prefs.edit().putInt(CAPSULE_IDLE_OPACITY, opacity.percent).commit()
    }

    fun capsuleIdleOpacityChanges(): Flow<CapsuleIdleOpacity> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == CAPSULE_IDLE_OPACITY) trySend(capsuleIdleOpacity())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(capsuleIdleOpacity())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** 展开的胶囊卡片整体缩放的档位;只认 85/100/115/130,默认 100(标准)。 */
    fun capsuleCardScale(): CapsuleCardScale = CapsuleCardScale.parse(prefs.getInt(CAPSULE_CARD_SCALE, CapsuleCardScale.DEFAULT.percent))

    fun setCapsuleCardScale(scale: CapsuleCardScale) {
        prefs.edit().putInt(CAPSULE_CARD_SCALE, scale.percent).commit()
    }

    fun capsuleCardScaleChanges(): Flow<CapsuleCardScale> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == CAPSULE_CARD_SCALE) trySend(capsuleCardScale())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(capsuleCardScale())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /**
     * 用户点了「完全退出」,要到下次打开 Mika 的界面为止([com.wangzan101.mika.MikaQuit])。
     * 在这期间进程可能被无障碍服务重新拉起:胶囊不画、后台服务不补起、自动恢复也不做,直到用户自己打开 App。
     */
    fun quitRequested(): Boolean = prefs.getBoolean(QUIT_REQUESTED, false)

    fun setQuitRequested(on: Boolean) {
        prefs.edit().putBoolean(QUIT_REQUESTED, on).commit()
    }

    /** 退出标记的当前值,之后每次改动再发一次。 */
    fun quitChanges(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == QUIT_REQUESTED) trySend(quitRequested())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(quitRequested())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** 胶囊停在哪:左边还是右边、高度占可摆范围的比例;没拖过时在右上角。 */
    fun capsuleDock(): CapsuleDock = CapsuleDock(
        if (prefs.getString(CAPSULE_SIDE, null) == DockSide.Left.name) DockSide.Left else DockSide.Right,
        prefs.getFloat(CAPSULE_HEIGHT, 0f).coerceIn(0f, 1f),
    )

    fun setCapsuleDock(dock: CapsuleDock) {
        prefs.edit().putString(CAPSULE_SIDE, dock.side.name).putFloat(CAPSULE_HEIGHT, dock.fraction).apply()
    }

    /**
     * 展开的卡片停在哪:上边在可摆范围里的比例(见 [com.wangzan101.mika.capsule.CardDocking.release]);没记过时最高的卡片在正中。
     * 以前按固定高的卡片记的比例正好就是它的上边比例,照样读。
     */
    fun capsuleCardFraction(): Float = prefs.getFloat(CAPSULE_CARD_HEIGHT, CardDocking.DEFAULT_FRACTION).coerceIn(0f, 1f)

    fun setCapsuleCardFraction(fraction: Float) {
        prefs.edit().putFloat(CAPSULE_CARD_HEIGHT, fraction.coerceIn(0f, 1f)).apply()
    }

    companion object {
        const val PREFS = "feature_flags"
        private const val SCHEDULED_QUIET_HOURS = "scheduled_quiet_hours"
        /** 每个任务步数上限能设的最大值;0/null 表示不限制。 */
        const val MAX_TASK_STEP_LIMIT = 500
        /** 定时任务最长运行时间可选的几档(分钟)。 */
        val TRIGGER_RUN_MINUTES = listOf(15, 30, 60, 120)
        /** 每任务沙箱命令累计时长可选的几档(分钟)。 */
        val SANDBOX_BUDGET_MINUTES = listOf(30, 60, 120, 240)
        /** 每任务沙箱命令累计时长没设(或存了档位外的值)时按这个算,与 core 的 [SANDBOX_BUDGET_MS] 一致。 */
        val DEFAULT_SANDBOX_BUDGET_MINUTES = (com.wangzan101.mika.core.cap.sandbox.SANDBOX_BUDGET_MS / 60_000L).toInt()
        /** 单个上传文件体积可选的几档(MB)。 */
        val ATTACHMENT_FILE_LIMIT_MB = listOf(200, 512, 1024, 2048)
        /** 单文件上限没设(或存了档位外的值)时按这个算,与 Attachments.MAX_BYTES 一致。 */
        val DEFAULT_ATTACHMENT_FILE_LIMIT_MB = (com.wangzan101.mika.cap.sandbox.Attachments.MAX_BYTES shr 20).toInt()
        /** 单个工作区空间可选的几档(GB)。 */
        val WORKSPACE_LIMIT_GB = listOf(4, 8, 16, 32)
        /** 工作区上限没设(或存了档位外的值)时按这个算,与 Attachments.WORKSPACE_MAX 一致。 */
        val DEFAULT_WORKSPACE_LIMIT_GB = (com.wangzan101.mika.cap.sandbox.Attachments.WORKSPACE_MAX shr 30).toInt()
        private const val SPEED_FLAGS = "speed_flags"
        /**
         * 提速开关默认值的升级,按先后各做一次(键记下做过没有):v2 打开分级读屏、连续操作、按需看图;
         * v3 打开经验回放(学习改看结局判定之后路线才记得下来);v4 打开大小模型分工(没配轻量模型时空转,配了就省钱);
         * v5 打开界面元素压成表(重复字段首屏压成列,省 token)。
         */
        private val SPEED_UPGRADES: List<Pair<String, (SpeedFlags) -> SpeedFlags>> = listOf(
            "speed_defaults_applied_v2" to { f -> f.copy(inlineScreen = true, batchSteps = true, adaptiveVision = true) },
            "speed_defaults_applied_v3" to { f -> f.copy(replay = true) },
            "speed_defaults_applied_v4" to { f -> f.copy(lightSteps = true) },
            "speed_defaults_applied_v5" to { f -> f.copy(tablesAtBirth = true) },
        )
        private const val PARALLEL_MAX = "parallel_max"
        private const val TASK_STEP_LIMIT = "task_step_limit"
        private const val TRIGGER_RUN_LIMIT = "trigger_run_limit_minutes"
        private const val SANDBOX_BUDGET_LIMIT = "sandbox_budget_limit_minutes"
        private const val ATTACHMENT_FILE_LIMIT = "attachment_file_limit_mb"
        private const val WORKSPACE_LIMIT = "workspace_limit_gb"
        private const val TASK_TOKEN_BUDGET = "task_token_budget"
        private const val PROACTIVE_DAILY_TOKEN_BUDGET = "proactive_daily_token_budget"
        private const val KEEP_SCREEN_ON = "keep_screen_on"
        private const val SHOW_TASK_COST = "show_task_cost"
        private const val MUTE_TARGET = "mute_target"
        private const val OPERATING_PACE = "operating_pace"
        private const val APP_PACE_OVERRIDES = "app_pace_overrides"
        private const val AUTO_ARCHIVE_DAYS = "auto_archive_days"
        private const val SELF_CONFIG = "self_config"
        private const val SELF_OPERATE = "self_operate"
        private const val DEVICE_FILES = "device_files"
        private const val PIP_OPERATE = "pip_operate"
        private const val EXTERNAL_SCHEDULE = "external_schedule"
        private const val PROACTIVE = "proactive"
        private const val PERSONA = "persona"
        private const val THEME_MODE = "theme_mode"
        private const val PERSONAL_START = "personal_start"
        private const val THEME_ACCENT = "theme_accent"
        private const val SKIN_VERSION = "skin_version"
        private const val SKIN_SCRIM = "skin_scrim"
        private const val SKIN_BLUR = "skin_blur"
        private const val SKIN_FOCUS = "skin_focus"
        private const val SKIN_ACCENT_FOLLOW = "skin_accent_follow"
        private const val SKIN_HUE = "skin_hue"
        private val SKIN_KEYS = setOf(SKIN_VERSION, SKIN_SCRIM, SKIN_BLUR, SKIN_FOCUS, SKIN_ACCENT_FOLLOW, SKIN_HUE)
        private const val CAPSULE_MODE = "capsule_mode"
        private const val CAPSULE_RESUME_MODE = "capsule_resume_mode"
        private const val CAPSULE_HIDE_AT_EDGE = "capsule_hide_at_edge"
        private const val CAPSULE_HIDE_FULLSCREEN = "capsule_hide_fullscreen"
        private const val CAPSULE_IDLE_OPACITY = "capsule_idle_opacity"
        private const val CAPSULE_CARD_SCALE = "capsule_card_scale"
        private const val QUIT_REQUESTED = "quit_requested"
        private const val CAPSULE_SIDE = "capsule_side"
        private const val CAPSULE_HEIGHT = "capsule_height"
        private const val CAPSULE_CARD_HEIGHT = "capsule_card_height"

        fun get(context: Context) = FeatureFlags(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}

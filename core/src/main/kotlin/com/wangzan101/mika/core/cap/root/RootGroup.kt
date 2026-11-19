package com.wangzan101.mika.core.cap.root

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.tool.OutputClip
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.int
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** root 命令申报的「在哪里」:确认卡片据此显示成 root 命令,而不是某个 App。 */
const val ROOT_CONFIRM_APP = "root"

/** 命令最长多少字;会改动东西的那几条确认卡片上整条显示。 */
private const val COMMAND_MAX = 4_000
private const val DEFAULT_TIMEOUT_SEC = 30
private const val MAX_TIMEOUT_SEC = 300

/**
 * 以 root 跑一条 Android shell 命令(经 su,uid 0),返回退出码、标准输出与标准错误(长的留头尾)。
 * 只读的命令(见 [RootCommandClass])直接跑;其余按参数声明成要问的一步:询问时先给用户看这条命令,同意了才跑。
 * 不动屏幕,进动作清单。
 */
internal object RootRunCommand : AgentTool {
    override val capability: String get() = ROOT
    override val verb = Verb.RootCommand
    override val label = "root 命令"
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("root_run_command", Prompts.tool("root_run_command"), mapOf(
        "command" to buildJsonObject {
            put("type", "string"); put("description", "The Android shell command to run as root, 1-$COMMAND_MAX characters.")
            put("minLength", 1); put("maxLength", COMMAND_MAX)
        },
        "timeoutSec" to int("Time limit in seconds, 1-$MAX_TIMEOUT_SEC (default $DEFAULT_TIMEOUT_SEC). The command is stopped when it runs out.", 1, MAX_TIMEOUT_SEC),
    ), listOf("command"))

    override fun available(ctx: ToolContext) = ctx.rootPort != null

    /** 只读命令算只读;会改动的算不可逆(记进审批),但不再逐条问——用户打开「允许 root 命令」本身就是授权。 */
    override fun approvalRisk(args: JsonObject): ApprovalRisk {
        val command = (args["command"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return if (command.isNotEmpty() && RootCommandClass.classify(command) == RootCommandClass.Kind.ReadOnly) ApprovalRisk(ActionRisk.Read)
        else ApprovalRisk(ActionRisk.Irreversible)
    }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val command = a.str("command", max = COMMAND_MAX)!!
        val timeoutSec = a.int("timeoutSec", 1, MAX_TIMEOUT_SEC) ?: DEFAULT_TIMEOUT_SEC
        return when (val r = ctx.rootPort!!.run(command, timeoutSec * 1000L)) {
            is RootRun.Done -> done(r, command)
            RootRun.Denied -> fail("root_denied", Feedback["root.denied"])
            RootRun.NoAnswer -> fail("root_no_answer", Feedback["root.no_answer"])
            RootRun.Unavailable -> fail("root_unavailable", Feedback["root.unavailable"])
        }
    }

    /**
     * 跑完的结果。输出(失败的也是事实)可能被回答引用,记成一条观察:命令是助手自己写的,输出里原样的回显不算读到的;
     * 读的多是别的 App 的数据,只当数据。
     */
    private fun done(r: RootRun.Done, command: String): ToolExecution {
        val (out, outClipped) = OutputClip.clip(r.stdout)
        val (err, errClipped) = OutputClip.clip(r.stderr, head = 1_000, tail = 3_000)
        val hints = listOfNotNull(
            Feedback["root.timed_out"].takeIf { r.timedOut },
            Feedback["root.truncated"].takeIf { outClipped || errClipped || r.droppedBytes > 0 },
        )
        return ToolExecution(buildJsonObject {
            put("ok", r.exitCode == 0 && !r.timedOut); put("exitCode", r.exitCode); put("timedOut", r.timedOut); put("durationMs", r.durationMs)
            put("stdout", Untrusted.wrap(out)); put("stderr", Untrusted.wrap(err))
            if (r.droppedBytes > 0) put("droppedBytes", r.droppedBytes)
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        }, observations = listOf(Observation(ObsKind.Output, "退出码 ${r.exitCode}:\n$out", source = ROOT,
            place = Place.command("root_run_command「${command.take(200)}」"), trust = Trust.Untrusted, authored = command)))
    }

    private fun fail(error: String, message: String) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", error); put("message", message); put("recoverable", false)
    })

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("command")
}

internal object RootGroup {
    val tools: List<AgentTool> = listOf(RootRunCommand)
}

package com.wangzan101.mika.core.device.shell

/**
 * 已 root 的手机上 su 的答复。[Granted]:答应了,并且以要的身份跑了命令;[Denied]:拒绝了(或 su 没跑命令就退出了);
 * [NoAnswer]:到时限还没回话(授权弹框没人点,或 root 管理器卡住了);[WrongUid]:答应了,却切不到要的身份(这个 su 不支持指定用户)。
 */
enum class SuAnswer { Granted, Denied, NoAnswer, WrongUid }

/** 找 su、读它的答复:只按能力判断(有没有这个文件、跑出来的 uid 是多少),不认是哪家的 root。 */
object SuProbe {
    /** su 常见的位置:系统 PATH 里的几处,和 root 方案常放的几处。PATH 里另有的也会找。 */
    val DIRS = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin", "/odm/bin", "/debug_ramdisk")

    /** 第一个存在的 su 的完整路径;没有为 null。[path] 是 PATH 环境变量,[exists] 看这个文件在不在(测试里换掉)。 */
    fun find(path: String?, exists: (String) -> Boolean): String? =
        (path.orEmpty().split(':').map { it.trim().trimEnd('/') }.filter { it.startsWith("/") } + DIRS)
            .distinct().map { "$it/su" }.firstOrNull(exists)

    private val UID = Regex("""uid=(\d+)""")

    /** `id` 或 `id -u` 的输出里的 uid;读不出为 null。su 前面可能多打几行提示,所以逐行找。 */
    fun uid(output: String): Int? =
        UID.find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    /**
     * 跑 `su … -c id` 一类命令后的答复。[output] 是 su 给出的输出(没给任何输出就退出了为空串);
     * [timedOut]:到时限还没回话;[expectUid]:要的身份。读到的 uid 不对算 [SuAnswer.WrongUid],读不到 uid 算拒绝。
     */
    fun answer(output: String, timedOut: Boolean, expectUid: Int): SuAnswer {
        val uid = uid(output)
        return when {
            uid == expectUid -> SuAnswer.Granted
            uid != null -> SuAnswer.WrongUid
            timedOut -> SuAnswer.NoAnswer
            else -> SuAnswer.Denied
        }
    }

    /** 以 root 跑一条命令的参数:`su -c <命令>`(各家 su 都认这个写法,命令作为一个参数原样交给 sh)。 */
    fun rootArgs(su: String, command: String): List<String> = listOf(su, "-c", command)

    /**
     * 以 shell 身份(uid 2000)跑一条命令的参数:`su 2000 -c <命令>`。su 的用户参数写在选项前面,
     * 有的 su 把 -c 之后的参数都当成命令。切不过去的 su 由跑出来的 uid 看出来(见 [answer])。
     */
    fun shellArgs(su: String, command: String): List<String> = listOf(su, HelperHandoff.SHELL_UID.toString(), "-c", command)
}

/**
 * 「自动用 root 启动手机助手」此刻能不能用。[Off]:用户没请求过,或还没有明确授权结果;
 * [Ready]:用户请求过、有 su、并且 su 已确认能切到 shell 身份;其余是授权后用不了,各自给用户一句话。
 * [Gone] 手机上已经没有 su;[Denied] su 拒绝了;[NoAnswer] su 没回话;[NoShellUid] su 切不到 shell 身份。
 * 后三种都要用户点重试才再去问 su(不反复弹授权框)。
 */
enum class RootStart {
    Off, Ready, Gone, Denied, NoAnswer, NoShellUid;

    /** 打开了却用不了时状态里报的失败;[Off]、[Ready] 为 null。 */    val failure: BuiltinShellFailure?
        get() = when (this) {
            Off, Ready -> null
            Gone -> BuiltinShellFailure.RootUnavailable
            Denied -> BuiltinShellFailure.RootDenied
            NoAnswer -> BuiltinShellFailure.RootNoAnswer
            NoShellUid -> BuiltinShellFailure.RootNoShellUid
        }

    companion object {
        /** [enabled]:用户是否请求过自动启动;[suPresent]:找得到 su;[last]:su 明确给过的答复。 */
        fun of(enabled: Boolean, suPresent: Boolean, last: SuAnswer?): RootStart = when {
            !enabled || last == null -> Off
            !suPresent -> Gone
            last == SuAnswer.Denied -> Denied
            last == SuAnswer.NoAnswer -> NoAnswer
            last == SuAnswer.WrongUid -> NoShellUid
            else -> Ready
        }
    }
}

/**
 * 设置里 root 那一项此刻的样子(纯状态机):没有 su 就整个不显示;问 su 的时候是 [Pending],
 * 问完按答复变成 [Granted] / [Denied] / [NoAnswer] / [NoShellUid];没问过是 [Unknown]。
 */
enum class RootGrant {
    Unknown, Pending, Granted, Denied, NoAnswer, NoShellUid;

    companion object {
        fun of(answer: SuAnswer?): RootGrant = when (answer) {
            null -> Unknown
            SuAnswer.Granted -> Granted
            SuAnswer.Denied -> Denied
            SuAnswer.NoAnswer -> NoAnswer
            SuAnswer.WrongUid -> NoShellUid
        }
    }
}

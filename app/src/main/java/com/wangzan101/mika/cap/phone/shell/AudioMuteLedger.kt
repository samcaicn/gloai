package com.wangzan101.mika.cap.phone.shell

import java.io.File

/**
 * 被 Mika 静音的 App → 静音前的 PLAY_AUDIO 模式,同时落在文件里:内置助手进程在静音中被杀、
 * 手机重启后,下次启动靠这份记录把声音还回去。静音时记、恢复成功后删;文件读写失败时退回只在内存里记。
 * 只有记录里的包会被恢复,别人设的值一概不碰。记录的键见 [AppAudioKey]:另一份用户里的那份单独一项。
 */
class AudioMuteLedger(private val file: File?) {
    private val modes = LinkedHashMap<String, String>()

    init {
        val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
        modes.putAll(parse(text))
    }

    fun contains(packageName: String): Boolean = packageName in modes
    operator fun get(packageName: String): String? = modes[packageName]
    fun packages(): List<String> = modes.keys.toList()

    fun put(packageName: String, mode: String) {
        modes[packageName] = mode
        save()
    }

    fun remove(packageName: String) {
        if (modes.remove(packageName) != null) save()
    }

    /** 把记录里的每个包恢复成 [restoreMode];[write] 返回 true 才删记录,失败的留着下次再试。返回是否全部恢复。 */
    fun restoreAll(write: (packageName: String, mode: String) -> Boolean): Boolean {
        packages().forEach { pkg -> if (write(pkg, restoreMode(modes.getValue(pkg)))) remove(pkg) }
        return modes.isEmpty()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            if (modes.isEmpty()) {
                f.delete()
            } else {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(format(modes))
                if (!tmp.renameTo(f)) error("rename failed")
            }
        }
    }

    companion object {
        private val LINE = Regex("""([A-Za-z0-9_.]+(?:@[0-9]+)?) ([a-z_]+)""")

        /**
         * 恢复时写回的模式。原来明确是 allow/ignore/deny 等的照原样写回;没设置过(解析出 default)的写 allow:
         * 安卓里音频系统只认 PLAY_AUDIO 为 allow 才放声音,显式的 default 会让 App 一直没声音,
         * 而没设置过时系统取的默认值本来就是 allow,写 allow 后 `appops get` 也回到「没设置」。
         * 记录里出现 default 只有两种来源:静音前没设置过,或旧版本写坏后又被记下,两种改成 allow 都不会误伤。
         */
        fun restoreMode(prior: String?): String = if (prior.isNullOrEmpty() || prior == "default") "allow" else prior

        /** `appops get <pkg> PLAY_AUDIO` 的输出:"PLAY_AUDIO: ignore; time=..." → "ignore";"No operations." → null(没设置过)。 */
        fun parseSetMode(output: String): String? = Regex("""PLAY_AUDIO:\s*([a-z_]+)""").find(output)?.groupValues?.get(1)

        /** 回读时区分未设置(默认 allow)与显式的 default。 */
        internal fun readMode(output: String): String? = parseSetMode(output)
            ?: Regex("""(?m)^Default mode: ([a-z_]+)$""").find(output)?.groupValues?.get(1)
            ?: "allow".takeIf { output.trim() == "No operations." }

        internal fun format(modes: Map<String, String>): String = modes.entries.joinToString("") { "${it.key} ${it.value}\n" }

        internal fun parse(text: String): Map<String, String> =
            text.lineSequence().mapNotNull { LINE.matchEntire(it.trim())?.destructured }
                .associate { (pkg, mode) -> pkg to mode }
    }
}

/**
 * 一项静音的键。PLAY_AUDIO 按「包 + 用户」分开设:本来那份(Mika 所在用户里的)就是包名,
 * 装在另一份用户里的那份(分身、工作资料)是「包名@用户编号」,两份各记各的原值、各自恢复。
 */
object AppAudioKey {
    private val KEY = Regex("""([A-Za-z0-9_.]+)(?:@([0-9]+))?""")

    /** [user] 为空是本来那份。 */
    fun of(packageName: String, user: Int?): String = if (user == null) packageName else "$packageName@$user"

    /** 拆回(包名, 用户);本来那份用户为空。不合格式的为 null。 */
    fun parse(key: String): Pair<String, Int?>? =
        KEY.matchEntire(key)?.destructured?.let { (pkg, user) -> pkg to user.takeIf { it.isNotEmpty() }?.toIntOrNull() }
}

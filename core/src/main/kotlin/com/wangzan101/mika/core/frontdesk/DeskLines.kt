package com.wangzan101.mika.core.frontdesk

/** 前台一条进度行最长这么多字,超出截断;一瞥/一听就懂,细节本来就不该上来。 */
const val DESK_LINE_MAX_CHARS = 40

/** 交给前台的任务结果文本上限:回给实时模型时协议帧对工具结果有总长度限制,给外层包装留余量。 */
const val DESK_RESULT_MAX_CHARS = 1_800

/**
 * 垫历史时最近一条 Mika 的话(多半是刚写进对话的完整结果)最多带这么多字:用户看着全文追问「第 8 条是什么」时,
 * 回话的模型也得看得到,不然就像 Mika 忘了自己刚说的话。更早的照 [DESK_RESULT_MAX_CHARS] 截。
 */
const val DESK_SEED_LATEST_MAX_CHARS = 12_000

/** 坐标形态:界面树/点击位置常见的 [x,y]、(x,y) 及其全角变体;bounds 是相邻两对,逐对剥。 */
private val COORDINATE_PATTERN = Regex("""[\[\(（【]\s*\d+\s*[,，、]\s*\d+\s*[\]\)）】]""")

/**
 * 带引号的一段:日程标题、联系人名、消息内容这类细节通常被引起来,整段剥掉。【】也按引号段剥(非数字形态)。
 * 半角单引号也是英文里的撇号(don't、it's):只有前面、后面都不挨着字母数字的才算引号,单词里的不动。
 */
private val QUOTED_PATTERN = Regex(
    """「[^」]*」|『[^』]*』|《[^》]*》|【[^】]*】|"[^"]*"|“[^”]*”|‘[^’]*’|(?<![\p{L}\p{N}])'[^']*'(?![\p{L}\p{N}])""",
)

private val WHITESPACE_RUN = Regex("""\s+""")

/** 剥掉坐标与引号包裹的标题等细节,并把剥出的空白收拢。 */
fun stripDetails(text: String): String =
    WHITESPACE_RUN.replace(COORDINATE_PATTERN.replace(QUOTED_PATTERN.replace(text, ""), ""), " ").trim()

/** 一句进度说明变成前台的一行:剥细节、截到 [DESK_LINE_MAX_CHARS] 字;剥完没字时为 null。 */
fun deskLine(narration: String?): String? =
    narration?.let { stripDetails(it).take(DESK_LINE_MAX_CHARS) }?.ifBlank { null }

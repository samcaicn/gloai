package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 用户中途停止之后,按这次已经做了的事写几行交代(做了什么、看到了什么、成批的事做了几件、哪些没做完),
 * 当作这一轮的回答给用户看、记进对话(下一句「继续」有上文)。只读这次的运行记录,不再操作任何东西:
 * 请一次模型([writer] 优先,出错换 [fallback]),不给工具;总共最多等 [timeoutMs],没模型、出错、超时就按记录直接拼一段([plain])。
 * 结局照旧是取消,只是多了这段话。
 */
class StopSummary(
    private val writer: ChatModel?,
    private val fallback: ChatModel? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val language: String? = null,
) {
    /**
     * [events] 是这次运行记下的事件(没有记录时至少是活动);[draft] 是停下时正在写的那段字(可为空)。
     * [label] 把工具名换成界面上的说法,[appLabel] 把包名换成 App 名(「网站 域名」只留域名)。
     */
    suspend fun summarize(
        goal: String, events: List<RunEvent>, draft: String?,
        label: (String) -> String = { it }, appLabel: (String) -> String = { it },
    ): String {
        val input = facts(goal, events, draft, label, appLabel, language)
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        val rolePrompt = Prompts.role("stop_summary")
        val phrase = StopPhrases.forLanguage(language)
        val basePrompt = if (phrase.isChinese) {
            rolePrompt
        } else {
            rolePrompt.replace(
                "例:\n已经停下。停止前:\n- 打开了设置,看完了「网络和互联网」「蓝牙」两页:网络页有 WLAN、移动网络、热点,蓝牙页有配对新设备、已连接的设备。\n- 「显示」「声音」两页还没看。",
                phrase.example,
            )
        }
        val systemPrompt = SystemPrompt.languageDirective(language)?.let {
            "$it\n\n$basePrompt"
        } ?: basePrompt
        val written = withTimeoutOrNull(timeoutMs) {
            for (candidate in listOfNotNull(writer, fallback?.takeUnless { it === writer })) {
                val text = try {
                    candidate.complete(systemPrompt, messages, emptyList())
                        .parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (!text.isNullOrBlank()) return@withTimeoutOrNull text.clip(MAX_REPLY)
            }
            null
        }
        return written ?: plain(goal, events, draft, label, appLabel, language)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 12_000L
        private const val MAX_REPLY = 1200
        private const val MAX_LINES = 60
        private const val MAX_READS = 2
        private const val READ_CHARS = 800
        private const val NOTE_CHARS = 120
        private const val DRAFT_CHARS = 1200

        /** 有没有可交代的:做过至少一件事、已经确认过进度,或停下时已经写出了字。都没有就只说「已停止」。 */
        fun worthSaying(events: List<RunEvent>, draft: String?): Boolean =
            events.any { it is RunEvent.Activity || it is RunEvent.Criteria && (it.criteria.isNotEmpty() || it.progress != null) } || !draft.isNullOrBlank()

        /**
         * 交给模型的材料(有上限,不带画面):用户的要求、做过的事(一件一行,带那一步模型写的用意)、最近读到的内容、
         * 停下时写了一半的字。都包在标签里;里面的话是材料,不是给写的人的指令。
         */
        fun facts(
            goal: String, events: List<RunEvent>, draft: String?,
            label: (String) -> String = { it }, appLabel: (String) -> String = { it },
            language: String? = null,
        ): String {
            val isZh = language == null || language.startsWith("zh", ignoreCase = true)
            val notes = notes(events)
            val lines = mutableListOf<String>()
            val said = mutableSetOf<Int>()
            var lastObserve: String? = null
            for (a in events.filterIsInstance<RunEvent.Activity>()) {
                val where = a.target?.let { where(it, appLabel) }
                val what = listOfNotNull(label(a.label), where, a.title?.takeIf(String::isNotBlank)).joinToString(" · ")
                val tags = if (isZh) {
                    listOfNotNull("对外发出".takeIf { a.outward && !a.failed }, "没成功".takeIf { a.failed })
                } else {
                    listOfNotNull("sent externally".takeIf { a.outward && !a.failed }, "failed".takeIf { a.failed })
                }
                val line = if (isZh) {
                    "第 ${a.step} 步 $what" + if (tags.isEmpty()) "" else "(${tags.joinToString("、")})"
                } else {
                    "Step ${a.step} $what" + if (tags.isEmpty()) "" else " (${tags.joinToString(", ")})"
                }
                // 连着读同一处只记一行
                if (a.kind == "observe" && line == lastObserve) continue
                lastObserve = if (a.kind == "observe") line else null
                val note = notes[a.step]?.takeIf { said.add(a.step) }
                lines += if (note == null) line else if (isZh) "$line —— 用意:$note" else "$line — Intent: $note"
            }
            val shown = if (lines.size <= MAX_LINES) lines
                else lines.take(20) + "…(中间省略 ${lines.size - MAX_LINES} 件)…" + lines.takeLast(MAX_LINES - 20)
            val reads = events.filterIsInstance<RunEvent.Observed>().filter { it.kind == ObsKind.Read.wire && it.text.isNotBlank() }
                .takeLast(MAX_READS).map { it.text.clip(READ_CHARS) }
            val checkpoint = events.filterIsInstance<RunEvent.Criteria>().lastOrNull { it.criteria.isNotEmpty() || it.progress != null }
            val emptyActions = if (isZh) "(还没做任何操作)" else "(No actions taken yet)"
            return buildString {
                append("<request>\n").append(untag(goal)).append("\n</request>\n\n")
                append("<actions>\n").append(untag(shown.joinToString("\n").ifEmpty { emptyActions })).append("\n</actions>")
                checkpoint?.let { c ->
                    if (c.criteria.isNotEmpty()) append("\n\n<acceptance>\n").append(untag(c.criteria.joinToString("\n"))).append("\n</acceptance>")
                    progress(c, isZh)?.let { p -> append("\n\n<progress>\n").append(untag(p)).append("\n</progress>") }
                }
                if (reads.isNotEmpty()) append("\n\n<last_read>\n").append(untag(reads.joinToString("\n----\n"))).append("\n</last_read>")
                draft?.trim()?.takeIf(String::isNotEmpty)?.let { append("\n\n<draft>\n").append(untag(it.clip(DRAFT_CHARS))).append("\n</draft>") }
            }
        }

        /**
         * 不经模型的交代,只按记录拼:去过哪些 App 和网站、各类操作做了几次、对外发出了几次(和是哪些,按那一步的用意)、
         * 几步没成功、停下时写了一半的字;最后说没做完的部分没有接着做。
         */
        fun plain(
            goal: String, events: List<RunEvent>, draft: String?,
            label: (String) -> String = { it }, appLabel: (String) -> String = { it },
            language: String? = null,
        ): String {
            val acts = events.filterIsInstance<RunEvent.Activity>()
            val notes = notes(events)
            val lines = mutableListOf<String>()
            val phrase = StopPhrases.forLanguage(language)
            val isZh = phrase.isChinese
            val places = acts.mapNotNull { a -> a.target?.let { placeOf(it, appLabel) } }.distinct()
            val seen = acts.filter { it.kind == "observe" && !it.failed && !it.title.isNullOrBlank() }
                .map {
                    if (isZh) listOfNotNull(it.target?.let { t -> where(t, appLabel) }, it.title).joinToString("的")
                    else listOfNotNull(it.target?.let { t -> where(t, appLabel) }, it.title).joinToString(" · ")
                }.distinct()
            val done = acts.filter { it.kind != "observe" && !it.failed && !it.outward }
                .groupingBy { label(it.label) }.eachCount()
            val outward = acts.filter { it.outward && !it.failed }
            val failed = acts.count { it.failed }
            val checkpoint = events.filterIsInstance<RunEvent.Criteria>().lastOrNull { it.criteria.isNotEmpty() || it.progress != null }
            if (isZh) {
                if (places.isNotEmpty()) lines += "去过:" + list(places, 4)
                if (seen.isNotEmpty()) lines += "看过:" + list(seen, 4)
                if (done.isNotEmpty()) lines += "做过:" + done.entries.joinToString("、") { (what, n) -> if (n > 1) "$what $n 次" else what }
                checkpoint?.let { c ->
                    if (c.criteria.isNotEmpty()) lines += "验收:" + c.criteria.joinToString("；")
                    progress(c, isZh)?.let { lines += "已确认进度:$it" }
                }
                if (outward.isNotEmpty()) {
                    val where = outward.mapNotNull { a -> a.target?.let { placeOf(it, appLabel) } }.distinct()
                    val which = outward.map { it.step }.distinct().mapNotNull(notes::get).distinct()
                    lines += "对外发出了 ${outward.size} 次" + (if (where.isEmpty()) "" else "(${where.joinToString("、")})") +
                        (if (which.isEmpty()) "" else ":" + list(which, 5))
                }
                if (failed > 0) lines += "有 $failed 步没成功"
                draft?.trim()?.takeIf(String::isNotEmpty)?.let { lines += "停下时正在写:" + it.clip(200) }
                val head = if (acts.isEmpty()) phrase.headEmpty else phrase.headWithActs
                val body = lines.joinToString("\n") { "- $it" }
                val tail = if (checkpoint?.complete == true) phrase.tailComplete(goal.trim().clip(40))
                    else phrase.tailIncomplete(goal.trim().clip(40))
                return listOf(head, body, tail).filter(String::isNotEmpty).joinToString("\n")
            } else {
                val sep = ", "
                if (places.isNotEmpty()) lines += phrase.visited + listOverflow(places, 4)
                if (seen.isNotEmpty()) lines += phrase.observed + listOverflow(seen, 4)
                if (done.isNotEmpty()) lines += phrase.actions + done.entries.joinToString(sep) { (what, n) -> if (n > 1) "$what ($n)" else what }
                checkpoint?.let { c ->
                    if (c.criteria.isNotEmpty()) lines += "Criteria: " + c.criteria.joinToString("; ")
                    progress(c, isZh)?.let { lines += phrase.confirmedProgress + it }
                }
                if (outward.isNotEmpty()) {
                    val where = outward.mapNotNull { a -> a.target?.let { placeOf(it, appLabel) } }.distinct()
                    val which = outward.map { it.step }.distinct().mapNotNull(notes::get).distinct()
                    lines += "${phrase.externalSent}${outward.size}" + (if (where.isEmpty()) "" else " (${where.joinToString(sep)})") +
                        (if (which.isEmpty()) "" else ": " + listOverflow(which, 5))
                }
                if (failed > 0) lines += "$failed ${phrase.stepsFailed}"
                draft?.trim()?.takeIf(String::isNotEmpty)?.let { lines += phrase.drafting + it.clip(200) }
                val head = if (acts.isEmpty()) phrase.headEmpty else phrase.headWithActs
                val body = lines.joinToString("\n") { "- $it" }
                val tail = if (checkpoint?.complete == true) phrase.tailComplete(goal.trim().clip(40))
                    else phrase.tailIncomplete(goal.trim().clip(40))
                return listOf(head, body, tail).filter(String::isNotEmpty).joinToString("\n")
            }
        }

        /** 活动对象给人看的说法:「网站 域名」只留域名,包名换成 App 名,别的(网址、查询词)原样;空的为 null。 */
        private fun where(target: String, appLabel: (String) -> String): String? = when {
            target.isBlank() -> null
            target.startsWith(SITE_PREFIX) -> target.removePrefix(SITE_PREFIX).trim()
            PACKAGE_NAME_RE.matches(target) -> appLabel(target)
            else -> target
        }.takeUnless { it.isNullOrBlank() }

        /** 活动对象是 App 或网站时它的名字;别的(网址、查询词、文件)为 null。 */
        private fun placeOf(target: String, appLabel: (String) -> String): String? =
            if (target.startsWith(SITE_PREFIX) || PACKAGE_NAME_RE.matches(target)) where(target, appLabel) else null

        /** 运行记录里网页那一屏的对象写成「网站 <域名>」。 */
        private const val SITE_PREFIX = "网站 "

        private fun list(items: List<String>, max: Int) =
            items.take(max).joinToString("、") + if (items.size > max) " 等 ${items.size} 处" else ""

        /** 非中文里截断后补一个计数,别默默丢掉。 */
        private fun listOverflow(items: List<String>, max: Int) =
            items.take(max).joinToString(", ") + if (items.size > max) " (+${items.size - max})" else ""

        /** 每一步模型写的话:先用回复里的进度说明,没有用调用参数里的用意(reason)。 */
        private fun notes(events: List<RunEvent>): Map<Int, String> {
            val out = mutableMapOf<Int, String>()
            for (r in events.filterIsInstance<RunEvent.ModelReply>()) {
                if (!r.acted || r.tool == null) continue
                val said = r.text.trim().ifEmpty { null } ?: reason(r.argsJson)
                said?.takeIf(String::isNotBlank)?.let { out[r.step] = it.replace('\n', ' ').clip(NOTE_CHARS) }
            }
            return out
        }

        private fun reason(argsJson: String?): String? = argsJson?.let {
            runCatching { (Json.parseToJsonElement(it) as? JsonObject)?.get("reason") as? JsonPrimitive }.getOrNull()?.contentOrNull?.trim()
        }

        private fun progress(c: RunEvent.Criteria, isZh: Boolean): String? {
            c.confirmedIds?.let { return if (isZh) "目标 ${it.size}/${c.criteria.size}" else "goal ${it.size}/${c.criteria.size}" }
            val p = c.progress ?: return null
            val count = when {
                p.done != null && p.total != null -> "${p.done}/${p.total}"
                p.done != null -> if (isZh) "${p.done} 件" else "${p.done} item(s)"
                else -> null
            }
            return listOfNotNull(p.name.takeIf(String::isNotBlank), count, p.boundary?.takeIf(String::isNotBlank)).joinToString(" · ")
        }

        private val TAG = Regex("</?\\s*(request|actions|acceptance|progress|last_read|draft)\\s*>", RegexOption.IGNORE_CASE)

        /** 材料里自带的标签去掉,免得它提前收口、把后面的话装成写的人的输入。 */
        private fun untag(s: String) = s.replace(TAG, "")

        private fun String.clip(max: Int) = if (length <= max) this else take(max) + "…"
    }
}

internal data class StopPhrase(
    val isChinese: Boolean,
    val headEmpty: String,
    val headWithActs: String,
    val visited: String,
    val observed: String,
    val actions: String,
    val confirmedProgress: String,
    val externalSent: String,
    val stepsFailed: String,
    val drafting: String,
    val tailComplete: (String) -> String,
    val tailIncomplete: (String) -> String,
    val example: String,
)

internal object StopPhrases {
    fun forLanguage(tag: String?): StopPhrase {
        val t = tag?.trim()?.lowercase().orEmpty()
        return when {
            t.startsWith("zh-hant") || t.startsWith("zh-tw") || t.startsWith("zh-hk") -> StopPhrase(
                isChinese = true,
                headEmpty = "已經停下，還沒開始操作。",
                headWithActs = "已經停下。停止前：",
                visited = "去過：",
                observed = "看過：",
                actions = "做過：",
                confirmedProgress = "已確認進度：",
                externalSent = "對外發出了",
                stepsFailed = "步沒成功",
                drafting = "停下時正在寫：",
                tailComplete = { "「$it」已確認達到驗收條件，但任務沒有正常收尾。" },
                tailIncomplete = { "「$it」還沒做完的部分沒有接著做。" },
                example = "例:\n已經停下。停止前:\n- 打開了設定，看完了「網路和網際網路」「藍牙」兩頁:網路頁有 WLAN、行動網路、熱點，藍牙頁有配對新設備、已連線的設備。\n- 「顯示」「聲音」兩頁還沒看。",
            )
            t.startsWith("zh") || t.isEmpty() || t == "system" -> StopPhrase(
                isChinese = true,
                headEmpty = "已经停下,还没开始操作。",
                headWithActs = "已经停下,停止前做了这些:",
                visited = "去过:",
                observed = "看过:",
                actions = "做过:",
                confirmedProgress = "已确认进度:",
                externalSent = "对外发出了",
                stepsFailed = "步没成功",
                drafting = "停下时正在写:",
                tailComplete = { "「$it」已确认达到验收条件,但任务没有正常收尾。" },
                tailIncomplete = { "「$it」还没做完的部分没有接着做。" },
                example = "例:\n已经停下。停止前:\n- 打开了设置,看完了「网络和互联网」「蓝牙」两页:网络页有 WLAN、移动网络、热点,蓝牙页有配对新设备、已连接的设备。\n- 「显示」「声音」两页还没看。",
            )
            t.startsWith("ja") -> StopPhrase(
                isChinese = false,
                headEmpty = "停止しました。操作はまだ開始されていません。",
                headWithActs = "停止しました。停止前:",
                visited = "アクセス先: ",
                observed = "確認内容: ",
                actions = "実行操作: ",
                confirmedProgress = "確認済み進捗: ",
                externalSent = "外部送信回数: ",
                stepsFailed = "件のステップが失敗",
                drafting = "停止時の入力内容: ",
                tailComplete = { "「$it」は完了条件を満たしましたが、正常終了前に停止されました。" },
                tailIncomplete = { "「$it」の残りの操作は継続されませんでした。" },
                example = "例:\n停止しました。停止前:\n- 設定を開き、「ネットワークとインターネット」「Bluetooth」を確認しました。\n- 「ディスプレイ」「音」はまだ確認していません。",
            )
            t.startsWith("ko") -> StopPhrase(
                isChinese = false,
                headEmpty = "중지됨. 아직 작업을 시작하지 않았습니다.",
                headWithActs = "중지됨. 중지 전:",
                visited = "방문함: ",
                observed = "확인함: ",
                actions = "수행 작업: ",
                confirmedProgress = "확인된 진행 상황: ",
                externalSent = "외부 전송: ",
                stepsFailed = "개 단계 실패",
                drafting = "중지 시 작성 중: ",
                tailComplete = { "“$it” 기준을 충족했으나 정상 종료 전 중지되었습니다." },
                tailIncomplete = { "“$it” 미완료 부분은 계속 진행되지 않았습니다." },
                example = "예:\n중지됨. 중지 전:\n- 설정을 열고 '네트워크 및 인터넷', '블루투스'를 확인했습니다.\n- '디스플레이'와 '소리'는 아직 확인하지 않았습니다.",
            )
            t.startsWith("es") -> StopPhrase(
                isChinese = false,
                headEmpty = "Detenida antes de iniciar acciones.",
                headWithActs = "Detenida. Antes de detener:",
                visited = "Visitado: ",
                observed = "Observado: ",
                actions = "Acciones: ",
                confirmedProgress = "Progreso confirmado: ",
                externalSent = "Enviado externamente: ",
                stepsFailed = "paso(s) fallidos",
                drafting = "Escribiendo al detener: ",
                tailComplete = { "“$it” cumplió los criterios de aceptación, pero se detuvo antes de finalizar." },
                tailIncomplete = { "Las partes pendientes de “$it” no se continuaron." },
                example = "Ejemplo:\nDetenida. Antes de detener:\n- Se abrió Ajustes y se revisó Redes e Internet y Bluetooth.\n- Pantalla y Sonido aún no se han revisado.",
            )
            t.startsWith("pt") -> StopPhrase(
                isChinese = false,
                headEmpty = "Parado antes de iniciar ações.",
                headWithActs = "Parado. Antes de parar:",
                visited = "Visitado: ",
                observed = "Observado: ",
                actions = "Ações: ",
                confirmedProgress = "Progresso confirmado: ",
                externalSent = "Enviado externamente: ",
                stepsFailed = "etapa(s) com falha",
                drafting = "Digitando ao parar: ",
                tailComplete = { "“$it” atingiu os critérios de aceitação, mas foi interrompido antes do término normal." },
                tailIncomplete = { "As partes pendentes de “$it” não foram continuadas." },
                example = "Exemplo:\nParado. Antes de parar:\n- Abriu Configurações e verificou Rede e internet e Bluetooth.\n- Tela e Som ainda não foram verificados.",
            )
            t.startsWith("ru") -> StopPhrase(
                isChinese = false,
                headEmpty = "Остановлено до начала действий.",
                headWithActs = "Остановлено. До остановки:",
                visited = "Посещено: ",
                observed = "Просмотрено: ",
                actions = "Действия: ",
                confirmedProgress = "Подтвержденный прогресс: ",
                externalSent = "Отправлено наружу: ",
                stepsFailed = "шагов не удалось",
                drafting = "В процессе ввода при остановке: ",
                tailComplete = { "«$it» соответствует критериям приемки, но было остановлено до штатного завершения." },
                tailIncomplete = { "Незавершенные части «$it» не продолжались." },
                example = "Пример:\nОстановлено. До остановки:\n- Открыты настройки, проверены разделы «Сеть и интернет» и «Bluetooth».\n- Разделы «Экран» и «Звук» еще не проверялись.",
            )
            t.startsWith("ar") -> StopPhrase(
                isChinese = false,
                headEmpty = "تم الإيقاف قبل بدء أي إجراء.",
                headWithActs = "تم الإيقاف. قبل التوقف:",
                visited = "تمت زيارة: ",
                observed = "تمت مشاهدة: ",
                actions = "الإجراءات: ",
                confirmedProgress = "التقدم المؤكد: ",
                externalSent = "تم الإرسال خارجياً: ",
                stepsFailed = "خطوة لم تنجح",
                drafting = "كانت قيد الكتابة عند التوقف: ",
                tailComplete = { "استوفت \"$it\" معايير القبول ولكن تم الإيقاف قبل الإنهاء الطبيعي." },
                tailIncomplete = { "الأجزاء غير المكتملة من \"$it\" لم تتم متابعتها." },
                example = "مثال:\nتم الإيقاف. قبل التوقف:\n- تم فتح الإعدادات والتحقق من الشبكة والإنترنت والبلوتوث.\n- لم يتم التحقق من الشاشة والصوت بعد.",
            )
            t.startsWith("fa") -> StopPhrase(
                isChinese = false,
                headEmpty = "قبل از شروع هر اقدامی متوقف شد.",
                headWithActs = "متوقف شد. قبل از توقف:",
                visited = "بازدید شد: ",
                observed = "مشاهده شد: ",
                actions = "اقدامات: ",
                confirmedProgress = "پیشرفت تأییدشده: ",
                externalSent = "ارسال به بیرون: ",
                stepsFailed = "مرحله ناموفق بود",
                drafting = "در حال نوشتن هنگام توقف: ",
                tailComplete = { "«$it» معیارهای پذیرش را برآورده کرد اما پیش از پایان عادی متوقف شد." },
                tailIncomplete = { "بخش‌های ناتمام «$it» ادامه نیافت." },
                example = "مثال:\nمتوقف شد. قبل از توقف:\n- تنظیمات باز شد و «شبکه و اینترنت» و «بلوتوث» بررسی شد.\n- «نمایشگر» و «صدا» هنوز بررسی نشده‌اند.",
            )
            t.startsWith("tr") -> StopPhrase(
                isChinese = false,
                headEmpty = "İşlemlere başlamadan önce durduruldu.",
                headWithActs = "Durduruldu. Durdurulmadan önce:",
                visited = "Ziyaret edildi: ",
                observed = "Gözlemlendi: ",
                actions = "İşlemler: ",
                confirmedProgress = "Onaylanan ilerleme: ",
                externalSent = "Dışarıya gönderildi: ",
                stepsFailed = "adım başarısız oldu",
                drafting = "Durdurulduğunda yazılıyordu: ",
                tailComplete = { "“$it” kabul kriterlerini karşıladı ancak normal bitişten önce durduruldu." },
                tailIncomplete = { "“$it” için tamamlanmayan kısımlar devam ettirilmedi." },
                example = "Örnek:\nDurduruldu. Durdurulmadan önce:\n- Ayarlar açıldı, Ağ ve internet ile Bluetooth kontrol edildi.\n- Ekran ve Ses henüz kontrol edilmedi.",
            )
            t.startsWith("id") || t.startsWith("in") -> StopPhrase(
                isChinese = false,
                headEmpty = "Dihentikan sebelum memulai tindakan apa pun.",
                headWithActs = "Dihentikan. Sebelum berhenti:",
                visited = "Dikunjungi: ",
                observed = "Dilihat: ",
                actions = "Tindakan: ",
                confirmedProgress = "Kemajuan terkonfirmasi: ",
                externalSent = "Terkirim ke luar: ",
                stepsFailed = "langkah gagal",
                drafting = "Sedang diketik saat berhenti: ",
                tailComplete = { "“$it” telah memenuhi kriteria penerimaan, tetapi dihentikan sebelum selesai normal." },
                tailIncomplete = { "Bagian dari “$it” yang belum selesai tidak dilanjutkan." },
                example = "Contoh:\nDihentikan. Sebelum berhenti:\n- Membuka Setelan dan memeriksa Jaringan & internet serta Bluetooth.\n- Tampilan dan Suara belum diperiksa.",
            )
            else -> StopPhrase(
                isChinese = false,
                headEmpty = "Stopped before starting any actions.",
                headWithActs = "Stopped. Actions before stop:",
                visited = "Visited: ",
                observed = "Observed: ",
                actions = "Actions: ",
                confirmedProgress = "Confirmed progress: ",
                externalSent = "Sent externally: ",
                stepsFailed = "step(s) failed",
                drafting = "Drafting when stopped: ",
                tailComplete = { "“$it” reached acceptance criteria, but was stopped before normal finish." },
                tailIncomplete = { "“$it” incomplete parts were not continued." },
                example = "Example:\nStopped. Before stopping:\n- Opened Settings and checked Network & internet and Bluetooth.\n- Display and Sound have not been checked yet.",
            )
        }
    }
}

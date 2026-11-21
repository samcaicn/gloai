package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.capability.PromptSections
import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.core.prompt.Prompts

/**
 * 系统提示词:P0 身份(prompts/system/identity.md)、P1 通用准则(system/principles.md,不点名工具),
 * 接通用工具的总规则(capabilities/general.md,通用工具一直都在),再接核心自己与各能力给的段落。
 * 段落只在那组工具出现在本任务发给模型的工具列表里时才有:说明和工具出自同一个判断。
 * 任务开始时拼一次,任务内一字不变。
 */
object SystemPrompt {
    /**
     * [assistant] 是助理的自称(人格里的「称呼」),默认 Mika;进提示词前在这里由 [PersonaPrompt.nickname] 清洗。
     * 不是 Mika 时身份句用它,并紧跟一段说明:Mika 是所在 App 的名字,后文和工具说明里的 Mika 指这个 App 和它自己。
     * 是 Mika 时和没有这个参数之前逐字节一样。
     */
    fun identity(assistant: String = PersonaPrompt.DEFAULT_NICKNAME): String {
        // 称呼落在围栏之外,这里自己再清洗一遍,不指望调用方洗过
        val name = PersonaPrompt.nickname(assistant)
        return listOfNotNull(
            Prompts.fill(Prompts.system("identity"), mapOf("assistant" to name)),
            if (name == PersonaPrompt.DEFAULT_NICKNAME) null else Prompts.fill(Prompts.read("persona/renamed.md"), mapOf("assistant" to name)),
            Prompts.system("principles"),
            read(GENERAL),
        ).joinToString("\n\n")
    }

    /** 在身份、准则、通用规则之后接人格层;没有人格时原样返回。 */
    fun withPersona(base: String, layer: String?): String = if (layer == null) base else "$base\n\n$layer"

    /**
     * 根据语言偏好生成语言指令段落。
     * 当选定特定语言时（如 English, 中文），指示模型思考、步骤说明、工具用意及最终回复均使用该语言。
     * 为空或系统自适应时，指示模型严格跟随用户提示词的语言。
     */
    fun languageDirective(languageTag: String?): String {
        val tag = languageTag?.trim()?.lowercase()
        return when {
            tag == null || tag.isEmpty() || tag == "system" ->
                "## Communication & Reasoning Language\nAlways mirror the language of the user's prompt for all internal thinking, progress notes, tool call reasons, and final replies."
            tag.startsWith("en") ->
                "## Communication & Reasoning Language\nYou must think, formulate all progress notes, tool call reasons, and final replies in English."
            tag.startsWith("zh") ->
                "## 语言设定\n所有思考过程、动作用意、执行进度与最终回复均使用中文。"
            tag.startsWith("ja") ->
                "## 言語設定\nすべての思考、進捗メモ、ツールの理由、および回答は日本語で行ってください。"
            tag.startsWith("es") ->
                "## Directiva de idioma\nDebes pensar, formular todas las notas de progreso, motivos de llamadas a herramientas y respuestas finales en español."
            tag.startsWith("ko") ->
                "## 언어 설정\n모든 생각, 진행 상황 메모, 도구 호출 이유 및 최종 답변은 한국어로 작성해야 합니다."
            tag.startsWith("ar") ->
                "## توجيه اللغة\nيجب عليك التفكير وكتابة جميع ملاحظات التقدم وأسباب استخدام الأدوات والردود النهائية باللغة العربية."
            tag.startsWith("fa") ->
                "## راهنمای زبان\nباید تمام افکار، یادداشت‌های پیشرفت، دلایل فراخوانی ابزارها و پاسخ‌های نهایی را به فارسی بنویسید."
            tag.startsWith("pt") ->
                "## Diretriz de idioma\nVocê deve pensar, formular todas as notas de progresso, motivos de chamada de ferramentas e respostas finais em português."
            tag.startsWith("id") || tag.startsWith("in") ->
                "## Arahan Bahasa\nAnda harus berpikir, menulis semua catatan kemajuan, alasan pemanggilan alat, dan balasan akhir dalam bahasa Indonesia."
            tag.startsWith("ru") ->
                "## Языковая директива\nВы должны думать, формулировать все заметки о ходе выполнения, причины вызова инструментов и окончательные ответы на русском языке."
            tag.startsWith("tr") ->
                "## Dil Yönergesi\nTüm düşünceleri, ilerleme notlarını, araç çağrı nedenlerini ve nihai yanıtları Türkçe olarak oluşturmalısınız."
            else ->
                "## Communication & Reasoning Language\nAlways mirror the language of the user's prompt for all internal thinking, progress notes, tool call reasons, and final replies."
        }
    }

    /** 在 base 提示词后接语言指令层。 */
    fun withLanguage(base: String, languageTag: String?): String {
        val directive = languageDirective(languageTag)
        return if (directive.isBlank()) base else "$base\n\n$directive"
    }

    fun assemble(identity: String, sections: List<String>): String = (listOf(identity) + sections).joinToString("\n\n")

    private const val GENERAL = "general"

    /** 一段能力说明。 */
    internal fun read(name: String): String = Prompts.capability(name)
}

/**
 * 核心自己的段落,排在各能力之前:用到才展开的几组从哪个入口进(groups,只列本任务给了入口的;展开后照旧留着,系统提示词少变);
 * 有两块以上的屏时怎么选、怎么在两块屏之间配合(channels);有屏展开后交给用户、下单发送前的核对(handover);
 * 动作结果自带屏幕时怎么读它(inline-screen);一次多步(batch)。
 */
internal object CoreSections : PromptSections {
    override fun sections(env: PromptEnv): List<String> = listOfNotNull(
        env.gate(SystemPrompt.read("groups")).takeIf { it.lines().size > 1 },
        SystemPrompt.read("channels").takeIf { env.screens >= 2 },
        SystemPrompt.read("handover").takeIf { env.openScreens >= 1 },
        SystemPrompt.read("inline-screen").takeIf { env.inlineScreen && env.openScreens >= 1 },
        SystemPrompt.read("batch").takeIf { DO_STEPS_TOOL in env.offered },
    ).map(env::gate)
}

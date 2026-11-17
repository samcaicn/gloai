package com.wangzan101.mika.core.cap.phone

/**
 * 一个任务开始时解析好的「操作节奏」:全局档位 + 设置里按应用覆盖的列表(未覆盖的 App 跟随全局)。
 * 内置 App 知识标 careful 的 App 在列表首次初始化时预置为 [OperatingPace.HUMAN](见 app 设置侧),运行时只看这份解析。
 */
class AppPacePlan(
    /** 全局档位(设置 › 干活时 › 操作节奏;评测钩子设了档位时为钩子值且覆盖为空)。 */
    val global: OperatingPace,
    /** 按应用覆盖:包名 → 档位。 */
    val overrides: Map<String, OperatingPace> = emptyMap(),
) {
    /** [pkg] 的实际档位:有覆盖用覆盖,否则全局;不知道包名( null)为全局。 */
    fun of(pkg: String?): OperatingPace = overrides[pkg] ?: global

    /** [pkg] 是否限制浏览快慢(计屏数、受上限):只看用户明确设的「像人」,全局像人不连带上限(与旧版一致:上限只给 careful App)。 */
    fun capped(pkg: String?): Boolean = pkg != null && overrides[pkg] == OperatingPace.HUMAN

    /** 所有 App 都按「快」(全局快且没有更慢的覆盖):停留可以整个关掉([AppPacing.Off])。 */
    val allFast: Boolean get() = global == OperatingPace.FAST && overrides.values.all { it == OperatingPace.FAST }

    /** 这个任务在 App 里一步一步操作的节奏:有覆盖时不能按全局档位整个变形,统一两套 limits、快慢按 App 分。 */
    fun appPacing(random: kotlin.random.Random = kotlin.random.Random.Default): AppPacing =
        if (allFast) AppPacing.Off else AppPacing.human(random)
}

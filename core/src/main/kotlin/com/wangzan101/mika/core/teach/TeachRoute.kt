package com.wangzan101.mika.core.teach

/** 折叠后的一条路线步骤。[kind] 为 null 表示合成的启动/切换步,不是示范里的一次操作。 */
data class RouteStep(val app: String?, val text: String, val kind: TeachKind?)

/** 这次示范里真正用到的 App。 [browser] 由调用方按系统默认浏览器判断，只作为提示。 */
data class RouteApp(val packageName: String, val label: String, val browser: Boolean)

data class TeachRoute(
    val steps: List<RouteStep>,
    val apps: List<RouteApp>,
    val folded: List<String>,
) {
    /**
     * 把「人的操作」整理成「Mika 的做法」的确定性第一步:
     * 回桌面、找图标、点开 App 这些只是为了让人进到 App 里,技能里写成「启动/改用/回到 <App>」。
     *
     * 识别不到桌面时([launcherPackages] 为空)整段原样保留,不误删任何步骤。
     * 识别不到浏览器时只是不给角色提示,不影响折叠。
     */
    companion object {
        fun fold(
            steps: List<TeachStep>,
            launcherPackages: Set<String>,
            browserPackages: Set<String>,
            label: (String) -> String,
        ): TeachRoute {
            if (launcherPackages.isEmpty()) return failOpen(steps, browserPackages, label)

            val apps = LinkedHashMap<String, RouteApp>()
            val out = ArrayList<RouteStep>(steps.size)
            val folded = ArrayList<String>()
            var lastApp: String? = null
            var viaLauncher = false

            steps.forEach { step ->
                val pkg = step.packageName
                if (pkg != null && pkg in launcherPackages) {
                    folded += foldLine(step)
                    viaLauncher = true
                    return@forEach
                }
                // 窗口本身不是操作:它可能是负一屏、输入法、系统弹层,不能因此把那个包当成这次要用的 App。
                if (step.kind == TeachKind.Window) return@forEach
                if (pkg == null) {
                    out += RouteStep(null, teachLine(step), step.kind)
                    return@forEach
                }
                val name = label(pkg).trim().ifEmpty { pkg }
                val previous = lastApp
                when {
                    previous == null && viaLauncher ->
                        out += RouteStep(pkg, "启动 $name", null)
                    viaLauncher && pkg == previous ->
                        out += RouteStep(pkg, "回到 $name", null)
                    previous != null && pkg != previous ->
                        out += RouteStep(pkg, (if (pkg in apps) "回到 " else "改用 ") + name, null)
                }
                lastApp = pkg
                viaLauncher = false
                apps.getOrPut(pkg) { RouteApp(pkg, name, pkg in browserPackages) }
                // 窗口切换本身不是操作:它是上一步的结果,或已经由合成步表达。
                if (step.kind != TeachKind.Window) out += RouteStep(pkg, teachLine(step), step.kind)
            }
            return TeachRoute(out, apps.values.toList(), folded)
        }

        private fun failOpen(
            steps: List<TeachStep>,
            browserPackages: Set<String>,
            label: (String) -> String,
        ): TeachRoute {
            val apps = LinkedHashMap<String, RouteApp>()
            steps.forEach { step ->
                val pkg = step.packageName ?: return@forEach
                apps.getOrPut(pkg) { RouteApp(pkg, label(pkg).trim().ifEmpty { pkg }, pkg in browserPackages) }
            }
            return TeachRoute(
                steps = steps.map { RouteStep(it.packageName, teachLine(it), it.kind) },
                apps = apps.values.toList(),
                folded = emptyList(),
            )
        }

        /**
         * 只用于给人看「省掉了什么」和给模型一条对照。不带包名和窗口的技术说明。
         */
        private fun foldLine(step: TeachStep): String {
            val target = step.nodeName ?: step.viewId
            return when (step.kind) {
                TeachKind.Window -> step.window?.let { "切到「$it」" } ?: "切换了界面"
                TeachKind.Click -> target?.let { "点了「$it」" } ?: "点了一下"
                TeachKind.LongClick -> target?.let { "长按了「$it」" } ?: "长按了一下"
                TeachKind.Type -> target?.let { "在「$it」输入了文字" } ?: "输入了文字"
                TeachKind.Scroll -> "滚动"
            }
        }
    }
}

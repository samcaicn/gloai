package com.wangzan101.mika.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.wangzan101.mika.ui.theme.MikaTheme

/** App 里的页面。对话是根页面,其余都从它(或从设置)进去,返回时回到进来的那一页。 */
enum class Route { Chat, Personal, Settings, Memory, Plans, Browser, Files, Run, Source, PhoneSetup, Models, Usage, SpeechModel, VoiceModel, ImageModel, Welcome, ModelSetup, About, License, UpdateMirrors, Screens, Teach, Calls, Report, Playback, Persona, VoiceCatalog, RealtimeVoice, VoiceServices, CallMode, VoiceCall }

/**
 * 页面栈:最底下永远是对话页。[push] 进一页,[back] 回上一页(已在对话页时返回 false,交给系统退出)。
 * 返回键和页面上的返回按钮走同一个 [back],所以两者总是一致。
 */
@Stable
class NavStack(initial: List<Route> = listOf(Route.Chat), fromDrawer: Boolean = false) {
    var stack: List<Route> by mutableStateOf(normalize(initial))
        private set

    /** 这一串页面是从侧栏进去的:退回对话页时要回到打开的侧栏。 */
    private var fromDrawer = fromDrawer && stack.size > 1

    /** 刚从侧栏进去的页面退回到了对话页,侧栏该重新打开;打开后调 [drawerReopened]。 */
    var reopenDrawer: Boolean by mutableStateOf(false)
        private set

    /** 每次外部跳转或系统分享回到对话页时递增,让已展开的侧栏一并收起。 */
    var resetSerial: Int by mutableStateOf(0)
        private set

    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** 进一页;已经在这一页时不重复压栈。 */
    fun push(route: Route) {
        if (route == Route.Chat) return reset()
        if (current != route) stack = stack + route
    }

    /** 从侧栏进一页:之后一路退回对话页时,侧栏是打开的。 */
    fun pushFromDrawer(route: Route) {
        fromDrawer = true
        push(route)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack = stack.dropLast(1)
        if (!canGoBack && fromDrawer) {
            fromDrawer = false
            reopenDrawer = true
        }
        return true
    }

    /** 把 [route] 摆到最上面:它已在栈里就退回到它(上面的页面关掉),不在就压进去。 */
    fun bringToTop(route: Route) {
        if (route == Route.Chat) return reset()
        val at = stack.indexOf(route)
        stack = if (at < 0) stack + route else stack.take(at + 1)
    }

    /** [route] 离开页面栈:在最上面时就是退一页,压在下面时把它抽掉;不在栈里不动。 */
    fun leave(route: Route) {
        if (route == Route.Chat || route !in stack) return
        if (current == route) back() else stack = stack - route
    }

    fun drawerReopened() {
        reopenDrawer = false
    }

    /** 只留对话页和 [route] 两层(首次上手的每一步用)。 */
    fun show(route: Route) {
        fromDrawer = false
        stack = normalize(listOf(route))
    }

    /** 回到对话页(打开某个对话时)。 */
    fun reset() {
        fromDrawer = false
        reopenDrawer = false
        stack = listOf(Route.Chat)
        resetSerial++
    }

    companion object {
        private const val DRAWER_MARK = "fromDrawer"

        private fun normalize(list: List<Route>): List<Route> =
            listOf(Route.Chat) + list.filter { it != Route.Chat }

        val Saver: Saver<NavStack, ArrayList<String>> = Saver(
            save = { ArrayList(it.stack.map(Route::name) + listOfNotNull(DRAWER_MARK.takeIf { _ -> it.fromDrawer })) },
            restore = { names ->
                NavStack(names.mapNotNull { n -> Route.entries.firstOrNull { it.name == n } }, fromDrawer = DRAWER_MARK in names)
            },
        )
    }
}

@Composable
fun rememberNavStack(first: Route = Route.Chat): NavStack =
    rememberSaveable(saver = NavStack.Saver) { NavStack(listOf(first)) }

/** 页面切换的动效参数:时长、视差比例(旧页挪四分之一)、曲线。 */
private object NavAnim {
    const val MS = 280
    const val PARALLAX = 4
    val EASE = FastOutSlowInEasing
}

/**
 * 页面切换的滑动推入过渡:[depth] 变深时新页从右滑入、旧页小幅左移(视差),变浅时反向。
 * 同级替换按推入处理;系统关了动画([MikaTheme.reduceMotion])时直接替换。
 * 内容里要用 [targetState] 的值(动画期间旧页还在),别读导航的实时状态。
 */
@Composable
fun <T> NavSlide(
    targetState: T,
    depth: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduce = MikaTheme.reduceMotion
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            if (reduce) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                val slide = tween<IntOffset>(NavAnim.MS, easing = NavAnim.EASE)
                if (depth(targetState) >= depth(initialState)) {
                    slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it / NavAnim.PARALLAX }
                } else {
                    slideInHorizontally(slide) { -it / NavAnim.PARALLAX } togetherWith slideOutHorizontally(slide) { it }
                }
            }
        },
        label = "nav-slide",
        content = content,
    )
}

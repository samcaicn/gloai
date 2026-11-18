package com.wangzan101.mika.cap.phone.a11y

import android.content.res.Resources
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.cap.phone.GesturePacing
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.uistate.TextField
import com.wangzan101.mika.core.uistate.typeAndVerify
import com.wangzan101.mika.core.uistate.UnseenField
import com.wangzan101.mika.core.uistate.normalize
import com.wangzan101.mika.cap.phone.shell.AsciiTextKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 打字 / 粘贴 / 清空的整块处理:对着输入框写入、或经剪贴板/输入法降级给自绘编辑器。
 * 共享 [AndroidScreen] 的字段与方法经 [screen] 后向引用访问。
 */
internal class TextInput(private val screen: AndroidScreen) {

    internal suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val found = when (val f = screen.findField(ref, snapshotId)) {
            is AndroidScreen.Field.Found -> f
            is AndroidScreen.Field.Missing -> return@withContext when {
                f.reason == "not_editable" -> {
                    if (ref != null && snapshotId != null) {
                        contextPasteAnchor(ref, snapshotId)?.let { anchor ->
                            pasteFromContextMenu(anchor, text)?.let { return@withContext it }
                        }
                    }
                    ActionResult.Failed(f.reason, "The referenced control did not expose an editor or a verified Paste menu.")
                }
                f.reason != AndroidScreen.NO_FOCUS -> ActionResult.Failed(f.reason)
                // 屏上什么都没拿着输入焦点、也没弹输入法:没有框接得住粘贴,不去粘(粘了界面别处一动就会被当成「可能打进去了」)
                !screen.somethingHoldsInputFocus() -> ActionResult.Failed(AndroidScreen.NO_FOCUS)
                else -> typeUnseen(text)
            }
        }
        typeField(found, text)
    }
    internal suspend fun typeField(found: AndroidScreen.Field.Found, text: String): ActionResult {
        var field = found.node
        val original = AndroidScreen.FieldShape.of(field)
        var focusUncertain = false
        if (!field.isFocused) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            field.refresh()
            // ACTION_FOCUS 的返回值和焦点标志都可能不可靠:没确认拿到焦点时,再按普通输入框点一下。
            if (!field.isFocused) {
                screen.withEventClock { clock ->
                    clock.mark()
                    screen.click(field).also { if (it) clock.awaitQuiet(AndroidScreen.QUIET_MS, AndroidScreen.FOCUS_WAIT_MS) }
                }
                screen.focusedEditable()?.takeIf { screen.sameField(original, it) }?.let { field = it }
            }
            // SET_TEXT 不要求焦点,仍可以尝试;最后以读回核对为准,但把焦点未确认如实带给调用方。
            focusUncertain = !field.isFocused
        }
        val web = screen.inWebView(field)
        // 网页编辑区要粘贴才收得到输入;密码框不经剪贴板
        var pasteUnavailable = web && field.isPassword
        var inputUncertain = false
        val placeholder = ScreenReader.placeholderOf(field)
        fun writeByInput(value: String): Boolean = when (val r = screen.inputText(field, value)) {
            null -> { pasteUnavailable = true; screen.setText(field, value) }
            AsciiTextKeys.NOT_SENT -> false
            AsciiTextKeys.DISPATCHED -> true
            else -> {
                // 动作结果不确定时也可能已经写入:让读回核对停在 UNKNOWN,不能再换一种方式重写。
                inputUncertain = true
                true
            }
        }
        val io = object : TextField {
            override fun read(): String? {
                if (inputUncertain) return null
                if (field.refresh() && screen.visibleInputTarget(field)) return ScreenReader.fieldText(field, placeholder, text)
                // 有的 App 内容一变就换掉输入控件:当前拿着输入焦点的那个和原来的类型、资源 ID、位置都对得上才改读它,
                // 否则算读不回,也不往别的框里写
                val now = screen.focusedEditable()?.takeIf { screen.sameField(original, it) } ?: return null
                field = now
                return ScreenReader.fieldText(now, placeholder, text)
            }
            override fun write(text: String): Boolean {
                if (!web || pasteUnavailable) return screen.setText(field, text)
                return writeByInput(text)
            }
            override fun retry(text: String): Boolean {
                if (web || pasteUnavailable) return write(text)
                // 原生框第一次 SET_TEXT 没落地时,第二次改走选择加粘贴,让编辑器收到真实输入。
                return writeByInput(text)
            }
        }
        val textsBefore = screen.read().texts.toSet()
        // 像人一样一段一段打出来(见 TypingPacing);密码框、要粘贴才收得到的网页框照旧一次写入。
        // 写不进、框不接受半截字时停下,下面照旧整段写入并读回核对
        screen.typing(field.packageName?.toString()).forField(web, field.isPassword).leadIn(io, text)
        var frozen = false
        val verification = typeAndVerify(
            io, text, AndroidScreen.TYPE_ATTEMPTS,
            settle = { screen.withEventClock { clock -> clock.awaitQuiet(AndroidScreen.READBACK_QUIET_MS, AndroidScreen.READBACK_CAP_MS) } },
            // 读回一直不变时,看界面上有没有冒出含所输文字的新文字(联想词等)
            reacted = {
                val typed = text.filterNot { it.isWhitespace() }
                (screen.read().texts.toSet() - textsBefore).any { typed in it.filterNot { c -> c.isWhitespace() } }.also { frozen = it }
            },
            secret = field.isPassword,
        )
        val hint = listOfNotNull(
            AndroidScreen.HINT_TAPPED_TO_FOCUS.takeIf { found.tapped },
            AndroidScreen.HINT_FOCUS_UNCONFIRMED.takeIf { focusUncertain },
            AndroidScreen.HINT_READBACK_FROZEN.takeIf { frozen },
            AndroidScreen.HINT_PASTE_UNAVAILABLE.takeIf { pasteUnavailable },
        ).joinToString(" ").ifEmpty { null }
        // 字可能只是显示出来、App 并没收到:不能报「一致」
        val reported = if (pasteUnavailable && verification != Verification.MISMATCH) Verification.UNKNOWN else verification
        return screen.ok(verification = reported, hint = hint, secret = field.isPassword, relocatedTo = found.relocatedTo)
    }

    internal suspend fun clearText(ref: String?, snapshotId: String?): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val found = when (val f = screen.findField(ref, snapshotId, tapToFocus = false)) {
            is AndroidScreen.Field.Found -> f
            is AndroidScreen.Field.Missing -> return@withContext when {
                f.reason != AndroidScreen.NO_FOCUS -> ActionResult.Failed(f.reason)
                !screen.somethingHoldsInputFocus() -> ActionResult.Failed(AndroidScreen.NO_FOCUS)
                else -> clearUnseen()
            }
        }
        clearField(found)
    }

    internal suspend fun clearField(found: AndroidScreen.Field.Found): ActionResult {
        val field = found.node
        val cleared = if (screen.inWebView(field)) screen.inputText(field, "") else {
            if (screen.setText(field, "")) AsciiTextKeys.DISPATCHED else AsciiTextKeys.NOT_SENT
        }
        return when (cleared) {
            null -> ActionResult.Failed(AndroidScreen.PASTE_UNAVAILABLE, AndroidScreen.HINT_PASTE_BLOCKED)
            AsciiTextKeys.NOT_SENT -> ActionResult.Failed("action_failed")
            else -> {
                val result = screen.verified(relocatedTo = found.relocatedTo) { true }
                if (cleared == AsciiTextKeys.UNCONFIRMED && result is ActionResult.Ok) result.copy(
                    verification = Verification.UNKNOWN,
                    hint = "The clear action did not confirm delivery. Read the field before trying again.",
                ) else result
            }
        }
    }

    // ---------- 树里看不到的输入框 ----------

    /**
     * 往这一点上的输入框里打字:先点它,等界面安静;点出了树里看得到、拿着焦点的输入框就照常对着它打,
     * 否则按 [typeUnseen] 粘贴并核对,粘贴前的屏上文字在点完之后读(点出的光标、键盘不算打进去的字)。
     * 点了就换到别的 App(点到了链接、按钮)时不打字。
     */
    internal suspend fun typeAt(x: Int, y: Int, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val hit = pointHit(x, y)
        val anchor = hit?.contextPasteAnchor()
        val target = hit?.node?.takeIf(ScreenReader::canSetText) ?: anchor
        val original = anchor?.let(AndroidScreen.FieldShape::of)
        val keyTask = if (anchor != null && AsciiTextKeys.supports(text)) {
            runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        } else -1
        val tapped = screen.verified { screen.tapAt(x, y) }
        if (tapped !is ActionResult.Ok) return@withContext tapped
        // 点了开出了别的 App(开到用户主屏上又挪回来的也是):不打字,照实报这一下点的结果
        if (tapped.opened != null) return@withContext tapped
        tapped.appSwitched?.takeIf { !tapped.switchedByTarget }?.let { return@withContext UnseenField.tapSwitched(it) }
        when (val focused = screen.focusedField()) {
            is AndroidScreen.Field.Missing -> if (focused.reason == AndroidScreen.EDITOR_NOT_VISIBLE) {
                return@withContext ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
            }
            is AndroidScreen.Field.Found -> {
                if (target != null && !samePointFocus(target, focused.node)) {
                    return@withContext ActionResult.Failed(
                        AndroidScreen.INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was sent.",
                    )
                }
                return@withContext typeField(focused, text)
            }
        }
        val typed = if (target != null && pointEditorFocusMismatch(target)) {
            // 点击没有让目标拿到焦点,另一个隐藏编辑框还拿着焦点时,按键会写进那个框;这次正文尚未发送。
            ActionResult.Failed(AndroidScreen.INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was sent.")
        } else {
            typeUnseen(text, atPoint = true)
        }
        if (typed is ActionResult.Failed && anchor != null && original != null && tapped.appSwitched == null) {
            // 只有粘贴键按下确定没发出,或剪贴板在改动前就被拒绝,才换输入通道。
            // 「已经发出但没观察到变化」可能已写入,不能再走一条会实际输入的路径。
            if (keyTask >= 0 && (typed.reason == AndroidScreen.INPUT_NOT_SENT || typed.reason == AndroidScreen.PASTE_UNAVAILABLE)) {
                typeWithKeys(anchor, original, keyTask, text)?.let { return@withContext it }
            }
            if (ContextPastePolicy.canRetry(typed.reason)) {
                val sameEditorFocused = pointEditorHasFocus(anchor, original)
                // The local Paste command itself confirms that the custom control can accept text.
                pasteFromContextMenu(anchor, text)?.let { return@withContext it }
                if (sameEditorFocused && keyTask < 0) {
                    return@withContext typed.copy(hint = "The editor did not accept clipboard input. Call hand_to_user to enter the text.")
                }
            }
        }
        typed
    }

    /** 显式点中的自绘编辑器的硬件按键降级:先确认全选已投递,再输入 ASCII,不在粘贴之后重放。 */
    internal suspend fun typeWithKeys(
        anchor: AccessibilityNodeInfo, original: AndroidScreen.FieldShape, taskId: Int, text: String,
    ): ActionResult? {
        if (screen.blockedFocusedEditor()) return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        if (imeBounds() != null || screen.focusedEditable() != null || !pointEditorHasFocus(anchor, original)) return null
        val pkg = anchor.packageName?.toString() ?: return null
        currentCoroutineContext().ensureActive()
        when (ctrlKey(KeyEvent.KEYCODE_A)) {
            AsciiTextKeys.NOT_SENT -> return null
            AsciiTextKeys.UNCONFIRMED -> return ActionResult.Failed(
                "key_input_unconfirmed", "Select-all may have reached the field. Check it before entering text again.",
            )
        }
        if (screen.blockedFocusedEditor()) return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        val sent = runCatching { screen.shell.injectAsciiText(screen.displayId, taskId, pkg, text) }.getOrDefault(AsciiTextKeys.UNCONFIRMED)
        return when (sent) {
            AsciiTextKeys.NOT_SENT -> null
            AsciiTextKeys.DISPATCHED -> screen.ok(
                verification = Verification.UNKNOWN,
                hint = "Sent keyboard events after select-all. Check the field before retrying or submitting.",
            )
            else -> ActionResult.Failed("key_input_unconfirmed", "Some keys may have reached the field. Check it before retrying.")
        }
    }

    /** 焦点明确落在点位之外的节点时为 true;读不到焦点时保留原来的隐藏编辑器输入路径。 */
    internal fun pointEditorFocusMismatch(anchor: AccessibilityNodeInfo): Boolean {
        val window = ScreenReader.contentWindows(screen.windows()).firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        } ?: return false
        if (window.id != anchor.windowId) return true
        val focus = window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return !samePointFocus(anchor, focus)
    }

    internal fun samePointFocus(target: AccessibilityNodeInfo, focus: AccessibilityNodeInfo): Boolean =
        target.windowId == focus.windowId && (within(target, focus) || within(focus, target))

    internal fun pointEditorHasFocus(anchor: AccessibilityNodeInfo, original: AndroidScreen.FieldShape): Boolean {
        if (anchor.isCheckable || anchor.isScrollable) return false
        val window = contextPasteScope(anchor, original)?.second?.singleOrNull()?.takeIf { it.isFocused } ?: return false
        if (anchor.isFocused) return true
        val root = window.root ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return !focus.isPassword && (within(anchor, focus) || within(focus, anchor))
    }

    /** [node] 是不是 [possibleAncestor] 自己或它下面的节点。 */
    internal fun within(node: AccessibilityNodeInfo, possibleAncestor: AccessibilityNodeInfo): Boolean =
        screen.ancestorOrSelf(node) { it == possibleAncestor } != null

    /** 清空这一点上的输入框,做法同 [typeAt]。 */
    internal suspend fun clearAt(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val hit = pointHit(x, y)
        val target = hit?.node?.takeIf(ScreenReader::canSetText) ?: hit?.contextPasteAnchor()
        val tapped = screen.verified { screen.tapAt(x, y) }
        if (tapped !is ActionResult.Ok) return@withContext tapped
        // 点了开出了别的 App(开到用户主屏上又挪回来的也是):不清空,照实报这一下点的结果
        if (tapped.opened != null) return@withContext tapped
        tapped.appSwitched?.takeIf { !tapped.switchedByTarget }?.let { return@withContext UnseenField.tapSwitched(it) }
        when (val focused = screen.focusedField()) {
            is AndroidScreen.Field.Missing -> if (focused.reason == AndroidScreen.EDITOR_NOT_VISIBLE) {
                return@withContext ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
            }
            is AndroidScreen.Field.Found -> {
                if (target != null && !samePointFocus(target, focused.node)) {
                    return@withContext ActionResult.Failed(
                        AndroidScreen.INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was cleared.",
                    )
                }
                return@withContext clearField(focused)
            }
        }
        if (target != null && pointEditorFocusMismatch(target)) {
            return@withContext ActionResult.Failed(
                AndroidScreen.INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was cleared.",
            )
        }
        clearUnseen(atPoint = true)
    }

    /**
     * 树里没有拿着焦点的输入框:输入框可能在 App 自己画的界面里,树读不到它,焦点在它上面。
     * 先全选(替换原有的字,和对着节点设字一样),再经剪贴板粘贴,按键只发到 Mika 的屏;之后比打字前后屏上读到的文字
     * 核对([UnseenField.typed])。不要求弹着输入法:按键发到这块屏拿着焦点的窗口,框不经输入法也收得到;
     * 没弹输入法而前后一点没变,就请模型先点输入框。弹着输入法时键盘上的字不算屏上的字。
     */
    internal suspend fun typeUnseen(text: String, atPoint: Boolean = false): ActionResult {
        if (screen.blockedFocusedEditor()) return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        val ime = imeBox()
        val before = screen.readVisibleText()?.let { UnseenField.outside(it, ime) }
        val beforeTree = screen.treeHash()
        val beforeFrame = screen.frameSignature()
        var pasted: Int? = null
        val changed = screen.withEventClock { clock ->
            clock.mark()
            if (screen.blockedFocusedEditor()) return@withEventClock null
            pasted = pasteUnseen(text)
            pasted != null && pasted != AsciiTextKeys.NOT_SENT && screen.awaitChange(clock, beforeTree, beforeFrame)
        } ?: return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        when (pasted) {
            null -> return ActionResult.Failed(AndroidScreen.PASTE_UNAVAILABLE, AndroidScreen.HINT_PASTE_BLOCKED)
            AsciiTextKeys.NOT_SENT -> return ActionResult.Failed(
                AndroidScreen.INPUT_NOT_SENT, "The input shortcut was not delivered; no text was sent by this attempt.",
            )
            AsciiTextKeys.UNCONFIRMED -> return screen.ok(
                changed = changed, verification = Verification.UNKNOWN,
                hint = "The paste shortcut may have been partially delivered. Check the field before retrying.",
            )
        }
        val after = screen.readVisibleText()?.let { UnseenField.outside(it, imeBox() ?: ime) }
        return when (val r = UnseenField.typed(text, before, after, changed, keyboardUp = ime != null, atPoint = atPoint)) {
            is ActionResult.Ok -> screen.ok(changed = r.changed, verification = r.verification, hint = r.hint)
            else -> r
        }
    }

    /**
     * 剪贴板换成 [text]、全选、粘贴,给 App 留出异步读取时间再还原。返回 null:剪贴板不能安全使用(读不到用户原来的内容),
     * 什么都没动。按键结果沿用 [AsciiTextKeys] 三态;只有正文按下确定没投递才是 NOT_SENT。
     * 标准粘贴动作存在时只调它一次,返回 false 也按不确定处理,不再补发 Ctrl+V。
     */
    internal fun pasteUnseen(text: String): Int? =
        screen.withClipboardText(text) {
            if (screen.blockedFocusedEditor()) return@withClipboardText AsciiTextKeys.NOT_SENT
            when (ctrlKey(KeyEvent.KEYCODE_A)) {
                AsciiTextKeys.NOT_SENT -> return@withClipboardText AsciiTextKeys.NOT_SENT
                AsciiTextKeys.UNCONFIRMED -> return@withClipboardText AsciiTextKeys.UNCONFIRMED
            }
            if (screen.blockedFocusedEditor()) return@withClipboardText AsciiTextKeys.NOT_SENT
            val focus = ScreenReader.contentWindows(screen.windows()).firstOrNull { it.isFocused }?.root
                ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword }
            val sent = if (focus?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_PASTE } == true) {
                if (focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.UNCONFIRMED
            } else {
                ctrlKey(KeyEvent.KEYCODE_V)
            }
            // 选区或光标可能先重绘,App 随后才异步读取剪贴板。
            Thread.sleep(AndroidScreen.PASTE_WAIT_MS)
            sent
        }

    /** Retain the node at an explicit point independently of context-menu eligibility. */
    internal fun pointHit(x: Int, y: Int): NodeEntry? {
        if (screen.handedOver() || imeBounds()?.contains(x, y) == true) return null
        val window = ScreenReader.contentWindows(screen.windows()).firstOrNull()
            ?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return null
        val entries = ScreenReader.read(listOf(window), screen.screenWidth, screen.screenHeight).entries
        return entries.mapNotNull { entry ->
            val bounds = Rect().also(entry.node::getBoundsInScreen)
            if (bounds.contains(x, y)) entry to bounds.width().toLong() * bounds.height() else null
        }.minByOrNull { it.second }?.first
    }

    internal fun NodeEntry.contextPasteAnchor(): AccessibilityNodeInfo? = node.takeIf { contextPasteCandidate(this) }

    internal fun contextPasteAnchor(ref: String, snapshotId: String): AccessibilityNodeInfo? {
        if (screen.handedOver()) return null
        val node = screen.resolve(ref, snapshotId)?.first ?: return null
        val entry = screen.read().entries.firstOrNull { it.node == node } ?: return null
        return node.takeIf { contextPasteCandidate(entry) }
    }

    internal fun contextPasteCandidate(entry: NodeEntry): Boolean = ContextPastePolicy.canProbe(
        entry.node.isEnabled, entry.node.isPassword, entry.locator.inList, entry.element.role,
    )

    /** The editor must still be present, with only its own app's windows above it. */
    internal fun contextPasteScope(
        anchor: AccessibilityNodeInfo, original: AndroidScreen.FieldShape,
    ): Pair<Rect, List<AccessibilityWindowInfo>>? {
        if (!screen.hasDisplay || screen.handedOver() || !anchor.refresh() || !screen.visibleInputTarget(anchor) || screen.blockedFocusedEditor() ||
            anchor.isPassword || (anchor.textSelectionStart >= 0 && anchor.textSelectionEnd >= 0 &&
                anchor.textSelectionStart != anchor.textSelectionEnd)) return null
        val current = AndroidScreen.FieldShape.of(anchor)
        val bounds = current.rect
        val ime = imeBounds()
        // Focusing an editor can resize or move it, even when there is no IME.
        // A changed rectangle must still belong to the original, focused node.
        val sameSize = bounds.width() == original.rect.width() && bounds.height() == original.rect.height()
        if (current.cls != original.cls || current.rid != original.rid ||
            (bounds != original.rect && !anchor.isFocused && (ime == null || !sameSize)) ||
            ime?.contains(bounds.centerX(), bounds.centerY()) == true ||
            !Rect(0, 0, screen.screenWidth, screen.screenHeight).contains(bounds)) return null
        val pkg = anchor.packageName?.toString() ?: return null
        val content = ScreenReader.contentWindows(screen.windows())
        val index = content.indexOfFirst { it.id == anchor.windowId }
        if (index < 0) return null
        val scoped = content.take(index + 1)
        if (scoped.any { it.type != AccessibilityWindowInfo.TYPE_APPLICATION || it.root?.packageName?.toString() != pkg }) return null
        return bounds to scoped
    }

    /** Confirm a newly opened editor menu before using its local Paste command. */
    internal suspend fun pasteFromContextMenu(anchor: AccessibilityNodeInfo, text: String): ActionResult? {
        if (!anchor.refresh()) return null
        val original = AndroidScreen.FieldShape.of(anchor)
        if (contextPasteScope(anchor, original) == null) return null
        val beforeText = screen.readVisibleText()?.let { UnseenField.outside(it, imeBox()) }
        val beforeTree = screen.treeHash()
        val beforeFrame = screen.frameSignature()
        val menu = ContextEditMenu(Resources.getSystem().getString(android.R.string.paste))
        fun isPaste(node: AccessibilityNodeInfo): Boolean {
            val label = ScreenReader.ownLabel(node)
            return menu.isPaste(node.viewIdResourceName, node.className?.toString()?.substringAfterLast('.'), label)
        }
        fun controls(scoped: List<AccessibilityWindowInfo>) =
            ScreenReader.read(scoped, screen.screenWidth, screen.screenHeight).entries.filter { it.node.isClickable && it.node.isEnabled }
        fun near(bounds: Rect, controls: List<NodeEntry>): List<AccessibilityNodeInfo> {
            val margin = min(screen.screenWidth, screen.screenHeight) / 5
            val vicinity = Rect(bounds).apply { inset(-margin, -margin) }
            val ime = imeBounds()
            return controls.filter { entry ->
                val x = entry.element.centerX
                val y = entry.element.centerY
                vicinity.contains(x, y) && ime?.contains(x, y) != true
            }.map { it.node }
        }
        val context = currentCoroutineContext()
        val pasted = screen.withClipboardText(text) {
            context.ensureActive()
            val (startBounds, scoped) = contextPasteScope(anchor, original) ?: return@withClipboardText AsciiTextKeys.NOT_SENT
            val before = controls(scoped)
            if (near(startBounds, before).any(::isPaste)) return@withClipboardText AsciiTextKeys.NOT_SENT
            val existing = before.map { it.node }.toSet()
            // 自绘编辑器的长按阈值可能比系统设置更长。
            val hold = maxOf(AndroidScreen.PASTE_MENU_MIN_HOLD_MS, ViewConfiguration.getLongPressTimeout().toLong() + 2 * AndroidScreen.POLL_MS)
            for (attempt in 0..1) {
                context.ensureActive()
                val (bounds, windows) = contextPasteScope(anchor, original) ?: return@withClipboardText AsciiTextKeys.NOT_SENT
                if (attempt > 0 && (!anchor.isFocused || windows.singleOrNull()?.isFocused != true ||
                        controls(windows).any { it.node !in existing })) {
                    return@withClipboardText AsciiTextKeys.NOT_SENT
                }
                val point = GesturePacing.Point(bounds.centerX().toDouble(), bounds.centerY().toDouble(), 0)
                if (!screen.injectStroke(listOf(point, point.copy(t = hold)))) return@withClipboardText AsciiTextKeys.NOT_SENT
                val deadline = SystemClock.uptimeMillis() + AndroidScreen.PASTE_MENU_WAIT_MS
                var previous: AccessibilityNodeInfo? = null
                var sawNewControl = false
                do {
                    context.ensureActive()
                    val (currentBounds, currentWindows) = contextPasteScope(anchor, original) ?: return@withClipboardText AsciiTextKeys.NOT_SENT
                    val current = controls(currentWindows)
                    sawNewControl = sawNewControl || current.any { it.node !in existing }
                    // Some editors add a clickable backdrop around the existing field.
                    // Its ancestors are containers, not newly offered edit commands.
                    val containers = generateSequence(anchor) { it.parent }.toSet()
                    val paste = menu.freshPaste(existing, near(currentBounds, current), ::isPaste, containers)
                    if (paste != null && paste == previous) {
                        if (!paste.refresh() || !paste.isVisibleToUser || !paste.isEnabled || !paste.isClickable || !isPaste(paste) || screen.handedOver()) {
                            return@withClipboardText AsciiTextKeys.NOT_SENT
                        }
                        context.ensureActive()
                        // performAction=false 不能证明点击没落到菜单上;按不确定收尾,不补发别的输入。
                        if (!paste.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withClipboardText AsciiTextKeys.UNCONFIRMED
                        Thread.sleep(AndroidScreen.PASTE_WAIT_MS)
                        return@withClipboardText AsciiTextKeys.DISPATCHED
                    }
                    previous = paste
                    Thread.sleep(AndroidScreen.POLL_MS)
                } while (SystemClock.uptimeMillis() < deadline)
                // 编辑器可能忽略第一次长按;只有没冒出菜单控件时才再按一次。
                if (sawNewControl) return@withClipboardText AsciiTextKeys.NOT_SENT
            }
            AsciiTextKeys.NOT_SENT
        }
        return when (pasted) {
            null -> ActionResult.Failed(AndroidScreen.PASTE_UNAVAILABLE, AndroidScreen.HINT_PASTE_BLOCKED)
            AsciiTextKeys.NOT_SENT -> null
            AsciiTextKeys.DISPATCHED -> {
                val afterText = screen.readVisibleText()?.let { UnseenField.outside(it, imeBox()) }
                val changed = beforeTree != screen.treeHash() || !beforeFrame.contentEquals(screen.frameSignature())
                val verification = UnseenField.verify(text, beforeText, afterText, changed)
                screen.ok(changed = changed, verification = verification, hint = "Used and checked the editor's Paste menu.")
            }
            else -> screen.ok(
                verification = Verification.UNKNOWN,
                hint = "The Paste menu click did not confirm delivery. Check the field before retrying.",
            )
        }
    }

    /** 和 [typeUnseen] 同样的前提;全选确认投递后才按删除,不确定时停止,不换路径重试。 */
    internal suspend fun clearUnseen(atPoint: Boolean = false): ActionResult {
        if (screen.blockedFocusedEditor()) return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        val keyboardUp = imeBox() != null
        when (ctrlKey(KeyEvent.KEYCODE_A)) {
            AsciiTextKeys.NOT_SENT -> return ActionResult.Failed(
                AndroidScreen.INPUT_NOT_SENT, "The select-all key-down was not delivered; delete was not sent.",
            )
            AsciiTextKeys.UNCONFIRMED -> return screen.ok(
                verification = Verification.UNKNOWN,
                hint = "Select-all may have been partially delivered; delete was not sent. Check the field before retrying.",
            )
        }
        if (screen.blockedFocusedEditor()) return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        if (!screen.shell.injectKey(screen.displayId, KeyEvent.KEYCODE_DEL)) return screen.ok(
            verification = Verification.UNKNOWN,
            hint = "Delete did not confirm delivery. Check the field before trying to clear it again.",
        )
        return UnseenField.cleared(screen.verified { true }, keyboardUp, atPoint)
    }

    internal fun ctrlKey(keyCode: Int): Int =
        runCatching { screen.shell.injectCtrlKey(screen.displayId, keyCode) }.getOrDefault(AsciiTextKeys.UNCONFIRMED)

    /** 这块屏上弹着输入法(这时按返回,安卓先收起输入法)。 */
    fun keyboardUp(): Boolean = screen.hasDisplay && imeBox() != null

    /** 这块屏上输入法窗口的范围(0–999 的 [l,t,r,b]);没弹输入法为 null。弹着时它上面的字不算屏上的字。 */
    internal fun imeBox(): List<Int>? {
        val r = imeBounds() ?: return null
        return listOf(normalize(r.left, screen.screenWidth), normalize(r.top, screen.screenHeight), normalize(r.right, screen.screenWidth), normalize(r.bottom, screen.screenHeight))
    }

    internal fun imeBounds(): Rect? {
        val w = screen.windows().firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        return Rect().also(w::getBoundsInScreen).takeUnless { it.isEmpty }
    }
}

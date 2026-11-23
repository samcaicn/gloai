package com.wangzan101.mika.core.cap.phone.sms

import com.wangzan101.mika.core.screen.uistate.*

/** Private during login; after verified success restore native text observations with code redaction.
 * Images remain local for the rest of this run. Only a task-local fingerprint is retained, never SMS/code text.
 */
class SmsPrivateScreen(private val inner: ScreenPort) : ScreenPort by inner, AutoCloseable {
    @Volatile var privateLogin = false
        private set
    private var fingerprint: ByteArray? = null
    private var codeLength = 0
    @Volatile var imagesPrivate = false
        private set
    fun begin() { privateLogin = true; imagesPrivate = true }
    @Synchronized fun resumeVerified(code: CharArray) {
        fingerprint?.fill(0)
        fingerprint = digest(String(code)); codeLength = code.size
        privateLogin = false
    }
    @Synchronized override fun close() { fingerprint?.fill(0); fingerprint = null }
    private fun digest(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    @Synchronized private fun redact(s: String): String {
        val fp = fingerprint ?: return s
        val text = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        var found = false
        val result = buildString {
            var i = 0
            while (i < text.length) {
                var j = i
                val digits = StringBuilder()
                while (j < text.length && digits.length < codeLength && j - i <= codeLength * 3) {
                    val ch = text[j]
                    if (ch in '0'..'9') digits.append(ch) else if (ch != ' ' && ch != '-') break
                    j++
                }
                val hash = if (text[i] in '0'..'9' && digits.length == codeLength) digest(digits.toString()) else null
                val matches = hash?.contentEquals(fp) == true
                hash?.fill(0)
                if (matches) { found = true; append("[private code]"); i = j } else { append(text[i]); i++ }
            }
        }
        return if (found) "[private login data]" else result
    }
    private fun cleaned(r: ActionResult): ActionResult = when (r) {
        is ActionResult.Ok -> r.copy(hint = r.hint?.let(::redact), relocatedTo = r.relocatedTo?.let(::redact))
        is ActionResult.Failed -> r.copy(hint = r.hint?.let(::redact))
    }
    private fun node(n: UiTreeNode): UiTreeNode = n.copy(text = n.text?.let(::redact), description = n.description?.let(::redact),
        hintText = n.hintText?.let(::redact), children = n.children.map(::node))
    private fun sanitize(s: ScreenSnapshot): ScreenSnapshot = if (!imagesPrivate) s else s.copy(
        elements = s.elements.map { it.copy(name = redact(it.name), placeholder = it.placeholder?.let(::redact)) },
        windows = s.windows.map { it.copy(title = it.title?.let(::redact), nodes = it.nodes.map(::node)) })

    private fun hidden() = ScreenSnapshot("sms-private", null, emptyList(), 1, 1,
        protected = true, captureError = "sms_login_private")
    override suspend fun snapshot() = if (privateLogin) hidden() else sanitize(inner.snapshot())
    override suspend fun peek() = if (privateLogin) hidden() else sanitize(inner.peek())
    override suspend fun screenshotPng() = if (imagesPrivate) null else inner.screenshotPng()
    override suspend fun readVisibleText() = if (privateLogin) null else inner.readVisibleText()?.map { it.copy(text = redact(it.text)) }
    override suspend fun frameGrid() = if (imagesPrivate) null else inner.frameGrid()
    override suspend fun checkScreen() = if (imagesPrivate) null else inner.checkScreen()
    override suspend fun isProtected() = privateLogin || inner.isProtected()
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long) =
        if (privateLogin) null else inner.nextEvent(kind, packageName, timeoutMs)?.let { it.copy(text = it.text?.let(::redact)) }
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int) =
        if (privateLogin) emptyList() else inner.watchEvents(kind, packageName, durationMs, maxEvents).map { it.copy(text = it.text?.let(::redact)) }
    override suspend fun systemCommand(command: String) = if (imagesPrivate) null else inner.systemCommand(command)
    // A stale pre-login ref could return the code as relocatedTo/hint. Refuse writes once hidden.
    private val blocked get() = ActionResult.Failed("sms_login_private", "Hand the remaining login step to the user and finish this run.")
    override suspend fun tapRef(ref: String, snapshotId: String) = if (privateLogin) blocked else cleaned(inner.tapRef(ref, snapshotId))
    override suspend fun tapXY(x: Int, y: Int) = if (privateLogin) blocked else cleaned(inner.tapXY(x, y))
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = if (privateLogin) blocked else cleaned(inner.doubleTapRef(ref, snapshotId))
    override suspend fun doubleTapXY(x: Int, y: Int) = if (privateLogin) blocked else cleaned(inner.doubleTapXY(x, y))
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = if (privateLogin) blocked else cleaned(inner.typeText(ref, snapshotId, text))
    override suspend fun clearText(ref: String?, snapshotId: String?) = if (privateLogin) blocked else cleaned(inner.clearText(ref, snapshotId))
    override suspend fun typeAt(x: Int, y: Int, text: String) = if (privateLogin) blocked else cleaned(inner.typeAt(x, y, text))
    override suspend fun clearAt(x: Int, y: Int) = if (privateLogin) blocked else cleaned(inner.clearAt(x, y))
    override suspend fun back() = if (privateLogin) blocked else cleaned(inner.back())
    override suspend fun pressKey(keyCode: Int) = if (privateLogin) blocked else cleaned(inner.pressKey(keyCode))
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = if (privateLogin) blocked else cleaned(inner.scroll(ref, snapshotId, down))
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = if (privateLogin) blocked else cleaned(inner.swipe(x1, y1, x2, y2, durationMs))
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = if (privateLogin) blocked else cleaned(inner.gesture(paths, durationMs))
    override suspend fun idleScroll(fraction: Double) = if (privateLogin) false else inner.idleScroll(fraction)
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = if (privateLogin) false else inner.waitFor(condition, value, timeoutMs)
    override suspend fun setWheel(ref: String, snapshotId: String, value: String) = if (privateLogin)
        WheelOutcome(false, null, null, 0, "none", error = "sms_login_private") else inner.setWheel(ref, snapshotId, value)
}

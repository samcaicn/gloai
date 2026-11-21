# do_each 采用率 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 模型逐条干活(开详情→动作→返回重复 ≥3 次)时收到机制级的 do_each 建议,并复活 same_state/state_cycle 守卫死代码、给 do_each 说明配真实感示例。

**Architecture:** 新增纯状态机 `EachLoopDetector`(core/agent)按动词序列形状识别重复,AgentLoop 轮末喂它,命中后以 `ctx.notice` 追加一条提醒并 `track.markRecovery()` 让下一步回主模型;同时在 `OperationGuards.frame()` 里接回 `arrived()`/`cycle()` 两条既有判定(界面状态维度,与形状维度互补)。

**Tech Stack:** Kotlin,JUnit4,Gradle(`:core` 纯 JVM 模块)。

**Spec:** `docs/superpowers/specs/2026-11-21-do-each-adoption-design.md`

## Global Constraints

- 通用 AOSP 行为,只看动词序列形状与界面状态哈希;**禁止任何 App/品牌特例**。
- 提示词改动只增不改已发消息(前缀缓存);few-shot 增量 <200 token。
- 反馈键在 `feedback/loop.md` 与 `feedback/tools.md` 间唯一,且每个键必须有代码以 `"<key>"` 字面量引用(`PromptLayoutTest.noOrphanFeedbackKeys` 强制)。
- 测试名必须与断言语义一致,不留名不副实的测试。
- 工作分支 `worktree-do-each-adoption`(worktree),不碰 main;不改版本号(非发版)。
- 全量回归用 `./gradlew :app:testDebugUnitTest`(**不要用裸 `:app:test`,会 OOM**)。

## Review Focus

1. `frame()` 里 `trail` 必须先于 `arrived()` 更新——顺序反了,`arrived()` 内的 cycle 抑制会用旧轨迹(时序 bug,测试只断言最终结论抓不到,需用例覆盖)。
2. `EachLoopDetector` 在序列恰满 `REPEATS*l` 时就应触发(L=2 时第 6 个动词即触发,不得等到窗口 15 攒满)。
3. 纯滚动信息流 `[Observe, Scroll]×N` 与双屏对比 `[Tap?, Back]` 之外的 motif 绝不触发(误报防护)。
4. `cycleLadder` 首级 2→3 后,确认仓库内没有其他代码/测试依赖旧阈值 2(grep `cycleLadder` 全部引用)。
5. 批(do_steps)与单步交错时 `eachLoop.reset()` 不漏:批前的半截序列不得拼接到批后。

---

### Task 1: 复活 same_state / state_cycle 守卫

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/guard/OperationGuards.kt`(frame() 169-189 行区域)
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/guard/GuardRules.kt`(cycleLadder 57 行 + 类注释兜圈行)
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/guard/OperationGuardsTest.kt`(114-183 行的三个测试)

**Interfaces:**
- Consumes: 现有 `OperationGuards.frame(id: String, hash: Long): List<GuardVerdict>`、`GuardRules.cycle(trail: List<Long>): Cycle?`、`GuardVerdict(code, severity, evidence, nudge)`。
- Produces: `GuardRules.cycleLadder(budget: Int?): List<Int>` 改为 `[3, 4, lateStop(budget, 5, 6)]`(Task 3 不依赖本任务接口,但同一测试文件)。

- [ ] **Step 1: 改写失败测试**

在 `OperationGuardsTest.kt` 中重写三个测试(断言恢复与测试名一致的真实语义;注意 `arrived()` 的 cycle 闸门保持原样——正在兜圈时 same_state 不说,由 state_cycle 单独说,所以第一个测试必须用**不规则**回访序列):

```kotlin
@Test fun returningToTheSameScreenWithoutAnythingNewNudgesAtThree() {
    val g = OperationGuards()
    g.frame("s0", 1L)
    // 每次经不同的新页面回到同一屏(不规则回访,不构成周期):第二次到 1 不提醒,第三次提醒
    val out1 = listOf(2L, 3L, 4L, 1L).mapIndexed { i, h -> g.noteAction(); g.frame("a$i", h) }.flatten()
    assertTrue(out1.toString(), out1.none { it.code == "same_state" || it.code == "state_cycle" })
    val out2 = listOf(5L, 6L, 7L).mapIndexed { i, h -> g.noteAction(); g.frame("b$i", h) }.flatten()
    assertTrue(out2.toString(), out2.isEmpty())
    g.noteAction()
    val last = g.frame("b3", 1L)
    assertEquals(listOf("same_state"), last.map { it.code })
    assertEquals(Severity.Warn, last.single().severity)
    assertTrue(last.single().nudge!!.contains("do_each"))
}

@Test fun threeStateLoopIsCaught() {
    val g = OperationGuards()
    g.frame("s0", 1L)
    // 1→2→3 转满三圈(trail 攒到 9)时提醒兜圈;周期循环全程没有 same_state(由 cycle 通道单独说)
    val seq = listOf(2L, 3L, 1L, 2L, 3L, 1L, 2L, 3L)
    val verdicts = seq.mapIndexed { i, h -> g.noteAction(); g.frame("s${i + 1}", h) }
    assertTrue(verdicts.dropLast(1).flatten().none { it.code == "state_cycle" || it.code == "same_state" })
    assertEquals(listOf("state_cycle"), verdicts.last().map { it.code })
    assertEquals(Severity.Warn, verdicts.last().single().severity)
}

@Test fun cycleStopFollowsTheStepBudget() {
    val g = OperationGuards(stepBudget = 30)
    g.frame("s0", 1L)
    // budget 30 → 梯 [3, 4, max(6, 30/5)=6]:第 5 帧(rounds 3)、第 7 帧(rounds 4)提醒,第 11 帧(rounds 6)叫停
    var stopAt = 0
    val warnAt = mutableListOf<Int>()
    for (i in 1..11) {
        g.noteAction()
        val v = g.frame("s$i", if (i % 2 == 1) 2L else 1L)
        if (v.any { it.code == "state_cycle" && it.severity == Severity.Warn }) warnAt += i
        if (g.stop != null) { stopAt = i; break }
    }
    assertEquals(listOf(5, 7), warnAt)
    assertEquals(11, stopAt)
    assertEquals("state_cycle", g.stop?.code)
}
```

并保留 `backAndForthBetweenTwoStatesDoesNotStopProgress` 原样(首级提到 3 后,3 帧 rounds=2 不触发,断言 empty 名副其实)。

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :core:test --tests '*OperationGuardsTest*' --console=plain -q`
Expected: FAIL——`returningToTheSameScreen…` 拿不到 same_state(死代码),`threeStateLoopIsCaught` 拿不到 state_cycle,`cycleStop…` 的 stopAt 为 0。

- [ ] **Step 3: 接线 arrived()/cycle() 进 frame(),改 ladder**

`OperationGuards.frame()` 的 `if (!same)` 块改为(顺序即语义:trail 先更新,再 arrived,再 cycle):

```kotlin
if (!same) {
    pageChanged()
    if (trail.lastOrNull() != hash) {
        trail.addLast(hash)
        while (trail.size > TRAIL_KEEP) trail.removeFirst()
    }
    if (seenStates.add(hash)) progress() else out += arrived(hash)
    out += cycle()
}
```

`arrived()` 本体**不动**(cycle 闸门保持:「正在兜圈时由 cycle 说,这里不重复」)。

`GuardRules.cycleLadder` 改为 `listOf(3, 4, lateStop(budget, 5, 6))`;类注释表格里「在两三个状态之间兜圈(整圈数)| 2、4 |…」一行的提醒列改为「3、4」;「没到过新状态时第 N 次回到同一状态」一行改为「回到同一状态(兜圈时不重复,由兜圈那条说)」。

- [ ] **Step 4: grep 确认没有别处依赖旧首级 2**

Run: `grep -rn "cycleLadder" core/src --include="*.kt"`
Expected: 只有 `GuardRules.kt`(定义)与 `OperationGuards.kt`(调用)两处;无其他阈值假设。

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :core:test --tests '*OperationGuardsTest*' --console=plain -q`
Expected: PASS(含保留的 `backAndForth…`、`manyActionsWithoutANewScreen…` 等全部既有测试)。

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/guard/OperationGuards.kt core/src/main/kotlin/com/wangzan101/mika/core/guard/GuardRules.kt core/src/test/kotlin/com/wangzan101/mika/core/guard/OperationGuardsTest.kt
git commit -m "fix(guard): 接回 same_state/state_cycle 判定,兜圈提醒首级 2→3"
```

---

### Task 2: EachLoopDetector 纯状态机

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/agent/EachLoopDetector.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/agent/EachLoopDetectorTest.kt`

**Interfaces:**
- Produces:
  - `internal class EachLoopDetector`,`fun feed(verb: Verb?, ok: Boolean): Boolean`(该提醒返回 true,每任务最多一次),`fun reset()`
  - 常量 `EachLoopDetector.REPEATS = 3`、`MAX_MOTIF = 5`、`WINDOW = REPEATS * MAX_MOTIF`
- Consumes: `com.wangzan101.mika.core.tool.Verb`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.Verb
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EachLoopDetectorTest {
    private fun EachLoopDetector.feedAll(varbs: List<Verb>, ok: Boolean = true): List<Boolean> =
        varbs.map { feed(it, ok) }

    @Test fun openActBackThreeTimesFiresOnceAtTheNinth() {
        val d = EachLoopDetector()
        val rounds = List(4) { listOf(Verb.Observe, Verb.Tap, Verb.Back) }.flatten()
        val fired = d.feedAll(rounds)
        assertEquals(fired.indices.filter { fired[it] }, listOf(8))
    }

    @Test fun tapBackPairFiresAtTheSixth() {
        val d = EachLoopDetector()
        val fired = d.feedAll(List(4) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }

    @Test fun feedScrollingNeverFires() {
        val d = EachLoopDetector()
        assertFalse(d.feedAll(List(6) { listOf(Verb.Observe, Verb.Scroll) }.flatten()).any { it })
    }

    @Test fun twoScreenCompareNeverFires() {
        val d = EachLoopDetector()
        // motif 里必须恰一个 Back 且至少一个 Tap:[Observe, Back] 不含 Tap,不触发
        assertFalse(d.feedAll(List(4) { listOf(Verb.Observe, Verb.Back) }.flatten()).any { it })
    }

    @Test fun failureBreaksTheStreak() {
        val d = EachLoopDetector()
        assertFalse(d.feedAll(List(2) { listOf(Verb.Tap, Verb.Back) }.flatten()).any { it })
        assertFalse(d.feed(Verb.Tap, false))
        // 失败后重新攒满 3 次才触发(证明失败前的半截没算进去)
        val fired = d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }

    @Test fun usedDoEachDisarms() {
        val d = EachLoopDetector()
        d.feed(Verb.DoEach, true)
        assertFalse(d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten()).any { it })
    }

    @Test fun nonScreenVerbBreaksTheStreak() {
        val d = EachLoopDetector()
        d.feedAll(List(2) { listOf(Verb.Tap, Verb.Back) }.flatten())
        d.feed(Verb.WebSearch, true)
        val fired = d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }

    @Test fun resetClearsHalfFormedStreak() {
        val d = EachLoopDetector()
        d.feedAll(List(2) { listOf(Verb.Tap, Verb.Back) }.flatten())
        d.reset()
        val fired = d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }
}
```

(`assertEquals` 需要 `org.junit.Assert.assertEquals` import。)

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:test --tests '*EachLoopDetectorTest*' --console=plain -q`
Expected: FAIL——`EachLoopDetector` 不存在(编译错误)。

- [ ] **Step 3: 实现 `EachLoopDetector`**

语义(唯一合理实现):

```kotlin
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.Verb

/**
 * 逐条干活的识别:最近动词序列里「开详情→动作→返回」的 motif 完整重复 [REPEATS] 次时提醒一次 do_each。
 * 只看形状不看内容;失败、非屏动词都打断;模型自己用了 do_each 就解除武装。纯状态机,每任务一个实例。
 */
internal class EachLoopDetector {
    private val seq = ArrayDeque<Verb>()
    private var fired = false
    private var disarmed = false

    fun reset() = seq.clear()

    /** 喂一轮的动词([ok] 为这轮成功);该提醒返回 true(每任务最多一次)。 */
    fun feed(verb: Verb?, ok: Boolean): Boolean {
        if (verb == Verb.DoEach) disarmed = true
        if (verb == null || !ok || verb !in COUNTED) { reset(); return false }
        seq.addLast(verb)
        while (seq.size > WINDOW) seq.removeFirst()
        if (fired || disarmed) return false
        if (!repeating()) return false
        fired = true
        return true
    }

    /** 最近 [REPEATS]×l 个动词呈 l 周期,motif 恰含一个 Back 且至少一个 Tap(开详情→动作→返回的签名)。 */
    private fun repeating(): Boolean {
        for (l in 2..MAX_MOTIF) {
            if (seq.size < REPEATS * l) continue
            val window = seq.toList().takeLast(REPEATS * l)
            val motif = window.take(l)
            if (window.chunked(l).any { it != motif }) continue
            if (motif.count { it == Verb.Back } == 1 && Verb.Tap in motif) return true
        }
        return false
    }

    companion object {
        const val REPEATS = 3
        const val MAX_MOTIF = 5
        const val WINDOW = REPEATS * MAX_MOTIF
        private val COUNTED = setOf(
            Verb.Observe, Verb.ListElements, Verb.Tap, Verb.TypeText, Verb.ClearText,
            Verb.Scroll, Verb.Swipe, Verb.Back, Verb.PressKey,
        )
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :core:test --tests '*EachLoopDetectorTest*' --console=plain -q`
Expected: PASS(8 个用例)。

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/agent/EachLoopDetector.kt core/src/test/kotlin/com/wangzan101/mika/core/agent/EachLoopDetectorTest.kt
git commit -m "feat(agent): EachLoopDetector 识别逐条干活的动词形状"
```

---

### Task 3: AgentLoop 接线 + loop.repeating_items 文案

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/AgentLoop.kt`(`val track = StepTrack()` 附近 ~515 行、单步路径 `track.after(...)` ~779 行、`batch()` 开头 ~955 行)
- Modify: `core/src/main/resources/prompts/feedback/loop.md`(末尾追加新键)
- Modify(再生成): `core/src/test/resources/prompt-surface/feedback-loop.txt`

**Interfaces:**
- Consumes: Task 2 的 `EachLoopDetector.feed/reset`、`EachLoopDetector.REPEATS`;`ctx.notice(source: String, text: String): Message`(ContextBuilder.kt:160);`Feedback.format(key, vararg vars)`;`track.markRecovery()`(StepTrack)。
- Produces: feedback 键 `loop.repeating_items`,带占位符 `{{count}}`。

- [ ] **Step 1: 加反馈文案**

`prompts/feedback/loop.md` 末尾追加(自成一体、首尾无空白、英文):

```
## loop.repeating_items
Your last steps repeat the same cycle — open an item, act on it, go back — {{count}} times in a row. If items remain, finish them in one do_each call instead of one item per turn: item says how to recognize one entry (role/text/resourceId), body is the steps for ONE item with "@item" as targetText, ending with back.
```

- [ ] **Step 2: 接线(三处小改)**

a) `val track = StepTrack()` 之后加:`val eachLoop = EachLoopDetector()`
b) 单步路径 `track.after(tools.verb(first.name), !isError, ran.verdicts.isNotEmpty() || advice != null, exec.changed, false, exec.place?.name)`(~779 行)之后加:

```kotlin
if (eachLoop.feed(verb, !isError)) {
    history += ctx.notice(EACH_LOOP_SOURCE, Feedback.format("loop.repeating_items", "count" to EachLoopDetector.REPEATS))
    track.markRecovery()
}
```

c) `batch()` 函数开头(`val (parsed, problem) = BatchRunner.parse(...)` 之前)加:`eachLoop.reset()`(Review Focus #5)。

companion object 加:`private const val EACH_LOOP_SOURCE = "each loop"`。`Feedback` 的 import 文件里已有(StuckAdvice 用),`EachLoopDetector` 同包无需 import。

- [ ] **Step 3: 跑 PromptLayoutTest 确认键有引用、无孤儿**

Run: `./gradlew :core:test --tests '*PromptLayoutTest*' --console=plain -q`
Expected: PASS(`noOrphanFeedbackKeys` 在代码里找到 `"loop.repeating_items"`)。

- [ ] **Step 4: 重新生成提示词金样并跑全部 prompt 测试**

Run: `./gradlew :core:test --tests '*PromptSurface*' -PupdateSnapshots --console=plain -q && ./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' --console=plain -q`
Expected: 第一条命令再生成 `feedback-loop.txt`;第二条 PASS。
随后 `git diff --stat core/src/test/resources/prompt-surface/` 确认只有 `feedback-loop.txt` 变化(若还有别的文件变,停下核对原因)。

- [ ] **Step 5: 跑 AgentLoop 相关测试**

Run: `./gradlew :core:test --tests '*AgentLoop*' --tests '*StuckAdvice*' --console=plain -q`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/agent/AgentLoop.kt core/src/main/resources/prompts/feedback/loop.md core/src/test/resources/prompt-surface/feedback-loop.txt
git commit -m "feat(agent): 逐条干活重复 3 次时提醒改用 do_each,提醒后回主模型"
```

---

### Task 4: do_each.md few-shot

**Files:**
- Modify: `core/src/main/resources/prompts/tools/do_each.md`(末尾示例句)
- Modify(再生成): `core/src/test/resources/prompt-surface/tools.txt`(及任何渲染 do_each 描述的金样)

**Interfaces:**
- Consumes: 无(纯文案);do_each body 的 op 集合以 `DoEach.kt:50` 的 enum 为准(tap/type/clear/scroll/swipe/key/back/wait/assert/observe,`wait` 的参数是 `whenText`/`timeoutMs`)。
- Produces: 无接口。

- [ ] **Step 1: 换示例**

`do_each.md` 里现有的示例句:

`Example — open each item, read its page, go back: do_each(item={"role":"Button"}, body=[{"op":"tap","targetText":"@item"},{"op":"observe"},{"op":"back"}], reveal="scroll").`

替换为(4 步真实 body,含等待):

`Example — open each entry, tap Like on its page, go back: do_each(item={"role":"Button"}, body=[{"op":"tap","targetText":"@item"},{"op":"wait","whenText":"Like","timeoutMs":3000},{"op":"tap","targetText":"Like"},{"op":"back"}]).`

其余文字不动;全段增量 <200 token。

- [ ] **Step 2: 重新生成金样并跑 prompt 测试**

Run: `./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' -PupdateSnapshots --console=plain -q && ./gradlew :core:test --tests '*PromptSurface*' --console=plain -q`
注意:`sizes.txt` 金样归 `PromptLayoutTest.layerSizesRendered` 所有,再生成必须带上 `--tests '*PromptLayoutTest*'`,只跑 `*PromptSurface*` 不会更新它。
Expected: PASS;`git diff --stat core/src/test/resources/prompt-surface/` 只动含 do_each 描述的文件(tools.txt,若 layer 类金样含工具全文也可能有,逐个核对 diff 内容确实只是这句示例)。

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/prompts/tools/do_each.md core/src/test/resources/prompt-surface/
git commit -m "docs(tools): do_each 示例换成含等待的 4 步真实 body"
```

---

### Task 5: 全量回归

**Files:** 无(验证任务)。

- [ ] **Step 1: core 全量**

Run: `./gradlew :core:test --console=plain -q`
Expected: PASS。

- [ ] **Step 2: app 全量(注意不是裸 :app:test)**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q`
Expected: PASS。

- [ ] **Step 3: Commit(如有修复)并报告**

无需提交则直接报告:两个测试任务绿、分支 `worktree-do-each-adoption` 的提交清单(`git log --oneline main..HEAD`)。真机 LiveRun 复测(采用率 ≥1/3)不在本计划内,需用户在场另做。

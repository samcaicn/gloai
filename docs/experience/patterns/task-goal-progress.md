# 做了很多操作，目标计数却一直没变

信号：任务卡停在 0/N，但操作时间线里已经出现了可核实的结果。先比对同一次运行的 `criteria`、解析错误事件（`RunEvent.State.name == "task_progress_invalid"`）和模型回复。只有初始 `criteria`、没有解析错误，说明没有收到后续有效更新；不能由动作条数推算已完成的目标。聊天页折叠的「前面还有 N 步」指已经发生的动作。

一个跨应用查店任务里，回复先后报告已确认地址和店名，计数却只有开局的 0/3。旧协议会从对话历史剥离进度块，又没有在后续常规请求里带回清单。现在由 `TaskProgressTracker` 带回待确认编号，按附有证据的上报去重；`RunEvent.Criteria.confirmedIds` 可用于复查。计数器不验证证据内容，也不把未上报的结果补为完成。

回归入口：`AgentLoopTest.coffeeTaskConfirmsGoalsWithoutCountingClicksOrClosingOnFiveNotes` 检查模型实际收到的待办、1/3 → 2/3 → 3/3 以及子数量到达时仍可继续操作；`completedQuantityClosesOutInsteadOfRecheckingForever` 保留单纯计数任务的收尾行为。两者是脚本回放，不能证明所有模型都按时上报。

另一种信号是**一次清单事件都没有**：同一咖啡馆任务再次运行时，30 轮后正常结束，但没有清单也没有解析错误。原来的待办提醒只看已经建立的多目标清单，因此不会提示补建。现在首个工具调用若没带清单，就在下一次正常请求内提醒一次；可以用已观察到的结果同时补报确认。检查 `RunEvent.State.name == "task_progress_plan_reminder"` 区分“发出了补建提醒”和“模型实际补报”。如果仍没有清单，保持动作状态，不编造分母。脚本用例 `missingChecklistCanBeBackfilledOnTheNextCoffeeTaskReply` 和 `ignoredChecklistReminderDoesNotRepeatOrInventProgress` 分别验证补报和忽略；真实模型的响应仍需实测。

补建规则写在系统准则，运行时只发短标记。把整段示例重复塞进工具结果后的上下文，在 `longTaskStaysUnderBudget` 的小窗口用例中会触发压缩，反而让模型看不到刚读到的完整屏幕。该用例同时检查首个长读屏的保留和整次请求的预算。

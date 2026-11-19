# 缓存命中率的分母

如果全局 Token 能对上，但混用服务商的会话命中率偏低，先检查模型适配器是否还保留了缓存字段的上报状态。有些响应只提供输入与输出，另一些会明确返回缓存读取量为零。两者转换成同一个整数后，旧运行小结无法区分。

会话面板按请求累加命中 Token，并且只把显式上报缓存的请求输入计入分母。检查时混合三种回复：命中、明确零命中、未上报。未上报的输入仍应增加会话消耗。旧小结可以补累计用量下限；无法确认覆盖范围时保留未知。

回归入口为 `MeteredModelTest.conversationUsesReportedCacheDenominatorAndLatestMainContext` 和 `StreamingModelsTest.anthropicStreamTakesCumulativeUsageFromMessageDelta`。前者检查不同输入长度的加权和旁路调用，后者检查流式协议对零命中和字段缺失的区分。该结论适用于会话内已计量的推理请求。

验证一次：混合上报的单测得到 1,300 个输入 Token，缓存覆盖其中 400 个，命中 80 个，命中率为 20%。一加手机上的两轮脚本对话另验证了运行中刷新和结束后重开；该真机用例使用固定用量，未连接在线服务商。

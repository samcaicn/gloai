## ADDED Requirements

### Requirement: 任务回答渲染产出引用卡片

回答含 `/workspace` 引用的任务最终回答（`Done`）与停止后的交代（`Cancelled` 的 summary）
SHALL 在回答文字下方渲染引用卡片（照片排格子、文件画卡片），与插话回复
（`FinishedReply`）同一渲染入口；点开 SHALL 走文件闸门读对话工作区，读不到提示文件不存在。

#### Scenario: 任务回答带编码引用
- **WHEN** 任务最终回答含 `![图](/workspace/%E6%8A%A5%E5%91%8A.png)` 与
  `[my report.csv](/workspace/my%20report.csv)`
- **THEN** 回答下方渲染蓝色图片缩略图与名为 `my report.csv` 的文件卡片；点文件卡经文件
  闸门打开工作区里的 `my report.csv`

#### Scenario: 停止后的交代带引用
- **WHEN** 任务被停止后补写的交代（summary）含 `/workspace` Markdown 引用
- **THEN** 交代下方同样渲染引用卡片

### Requirement: 产出引用按百分号转义解码

回答里抽出的 `/workspace` 链接/图片目标，渲染前 SHALL 按 URI 百分号转义宽容解码：合法
`%HH` 序列按 UTF-8 解出；非法转义序列与字面 `+` SHALL 原样保留；解码后路径仍 SHALL 经
越界过滤（`..` 或圈出工作区的引用丢弃）。

#### Scenario: 空格写成 %20
- **WHEN** 模型回复含 `[报告](/workspace/my%20report.csv)`
- **THEN** 引用卡片显示文件名 `my report.csv`，点开经文件闸门读工作区里的
  `my report.csv`

#### Scenario: 中文文件名被 URL 编码
- **WHEN** 模型回复含 `![图](/workspace/%E6%8A%A5%E5%91%8A.png)`
- **THEN** 解析出的引用路径为 `/workspace/报告.png`，按照片渲染缩略图

#### Scenario: 字面加号不被当空格
- **WHEN** 引用目标为 `/workspace/a+b.png`
- **THEN** 解析出的路径仍为 `/workspace/a+b.png`

#### Scenario: 非法百分号序列保留原样
- **WHEN** 引用目标为 `/workspace/100%.csv`（`%.c` 不是合法转义）
- **THEN** 解析出的路径仍为 `/workspace/100%.csv`，引用不因解码失败被丢弃

#### Scenario: 解码后仍受越界过滤
- **WHEN** 引用目标解码后含 `..` 或指到工作区外
- **THEN** 该引用不渲染成卡片

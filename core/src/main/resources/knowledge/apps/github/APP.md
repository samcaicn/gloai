---
name: github
display: GitHub
packages: ["com.github.android"]
aliases: ["GitHub", "github"]
sites: ["github.com"]
open: GitHub
description: 代码托管与开源协作:仓库、议题、拉取请求与通知
---
## 是什么
GitHub 是开发者代码托管与项目协作平台 App,包名 `com.github.android`。网站知识见 `github.com`。

## 界面
底部导航包含四个标签:「主页 (Home)」「通知 (Notifications)」「探索 (Explore)」「个人资料 (Profile)」。
- **主页**:展示「我的工作 (My Work)」快捷入口:议题(Issues)、拉取请求(Pull Requests)、讨论(Discussions)、仓库(Repositories)、组织(Organizations)、星标(Starred)。下方为已固定的置顶仓库(Pinned)。
- **仓库页面**:顶部显示仓库所有者/名称、分支切换器(Branch);下方分设「代码 (Code)」「议题 (Issues)」「拉取请求 (Pull Requests)」「讨论 (Discussions)」标签。
- **通知页**:按收件箱(Inbox)、已保存(Saved)、已完成(Done)分类,可快速滑动将通知标记为 Done 或稍后查看。
- **拉取请求(PR)与议题(Issue)详情**:展示标题、状态徽标(Open、Merged、Closed)、发件人与时间戳、时间线对话、审查状态(Reviewers)、检查运行(Checks)。PR 详情页包含「文件变动 (Files Changed)」「提交 (Commits)」。

## 功能
- **浏览代码与分支**:
  - 进入仓库后点击分支下拉菜单可切换特定开发分支或 Tag。在文件树中点击目录逐步深入,点击源码文件阅读渲染后的代码或 Markdown 说明。
- **处理与回复 Issue/PR**:
  - 在 Issue 或 PR 底部评论框键入回复文本,点击「Comment」提交。
  - 维护者点击底部的「Close issue」关闭议题;在 PR 界面满足合并条件且 CI 全绿时,可点击「Merge pull request」并确认合并。
- **双重身份验证 (2FA / GitHub Mobile)**:
  - 在电脑端登录 GitHub 时,手机端常弹出全屏「是否正在尝试登录?」附带双位数字匹配核验,需点击手机屏幕上与电脑端一致的数字完成授权。

## 已知现象
- **状态残留**:退出重开经常停留在上次查看的特定 Issue 或某行代码 Diff 视图中,左上角点击「←」可逐层返回仓库首页。
- **CI 检查折叠**:PR 的 Checks 模块若包含多个自动化工作流,只展示汇总状态(如「13/14 passed」),需点击整行进入二级页面才能查看哪一项失败。
- **合并方式选择**:点击 Merge pull request 时若仓库支持多种合并策略,会弹出「Create a merge commit」「Squash and merge」「Rebase and merge」菜单,需注意选择目标模式。

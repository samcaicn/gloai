# 使用流程（USAGE）

本文档描述「自动选品沟通」技能从配置到执行的完整链路。

## 前置条件

### Brave 浏览器 CDP 配置

Brave 浏览器需以远程调试端口启动，支持 CDP 调用：

**macOS:**
```bash
/Applications/Brave\ Browser.app/Contents/MacOS/Brave\ Browser --remote-debugging-port=9222
```

**Windows:**
```cmd
"C:\Program Files\BraveSoftware\Brave-Browser\Application\brave.exe" --remote-debugging-port=9222
```

**Linux:**
```bash
brave-browser --remote-debugging-port=9222
```

### 微信小店登录态

浏览器中需已登录微信小店后台（`store.weixin.qq.com`）。如未登录，页面会跳转到登录页，技能会检测到并提示。

## 1. 配置 — 预设关键词和文案模板

```json
{
  "action": "setup",
  "keywords": ["女装", "夏季", "连衣裙"],
  "messageTemplate": "您好！我对贵店的{keywords}产品很感兴趣，请问可以发一份产品目录和报价吗？",
  "maxMerchants": 5
}
```

- `keywords`：选品关键词数组，不传则执行时交互式询问
- `messageTemplate`：沟通文案模板，支持 `{keywords}` 占位符，不传则使用 LLM 生成
- `maxMerchants`：最多联系商家数量，默认 5

## 2. 查看状态

```json
{ "action": "status" }
```

返回当前配置、CDP 连接状态和浏览器标签页列表。

## 3. 执行 — 全自动选品沟通

```json
{
  "action": "execute",
  "goal": "自动选品并联系商家",
  "maxMerchants": 3
}
```

或直接传入关键词（跳过交互询问）：

```json
{
  "action": "execute",
  "keywords": ["男装", "T恤", "纯棉"],
  "maxMerchants": 5
}
```

### 执行流程

1. **ensure** — 检测 CDP 连接，确认 Brave 浏览器可用
2. **navigate** — 打开微信小店选品 IM 页面
3. **wait_page** — 等待页面 DOM 完成加载（最多 30s）
4. **keywords?** — 检查是否有预设关键词
   - 有 → 直接筛选
   - 无 → 弹窗询问用户输入关键词
5. **filter** — 在页面搜索框中输入关键词并触发筛选
6. **wait_results** — 等待筛选结果列表加载（最多 20s）
7. **contact** — 点击「联系商家」按钮
8. **tab_opened?** — 检测新标签页是否打开（最多 15s）
   - 新标签页打开 → 切换到新标签页
   - 同页跳转 → 等待页面加载
   - 无变化 → 跳过此商家
9. **switch_tab** — 通过 `navigate` 将 CDP 控制的页面导航到新标签页 URL
10. **generate_msg** — LLM 根据商品信息生成沟通文案（有商品信息时）或使用模板
11. **send_msg** — 在会话窗口输入文案并点击发送
12. **more?** — 判断是否继续联系下一个商家
    - 已联系数 < maxMerchants → 回到 filter 继续
    - 已达上限或无更多结果 → 结束

## 4. 控制 — 迷你悬浮窗

执行过程中可通过悬浮窗控制：

```json
{ "action": "step_once" }  // 单步执行
{ "action": "pause" }      // 暂停
{ "action": "resume" }     // 继续
{ "action": "stop" }       // 停止
```

## 5. 回放 — 停止后查看执行轨迹

```json
{ "action": "get_flowchart" }  // 获取流程图
{ "action": "get_judgments" }  // 获取判断规则
{ "action": "get_trace" }      // 获取执行轨迹
```

## 单步操作

也可单独调用某个功能节点：

```json
// 打开选品页面
{ "action": "open_page", "url": "https://store.weixin.qq.com/talent/kf/collab/im?mode=business&roomId=4608582318278705168" }

// 执行筛选
{ "action": "apply_filter", "keywords": ["女装", "连衣裙"] }

// 点击第2个联系商家
{ "action": "contact_merchant", "index": 1 }

// 直接发送消息
{ "action": "send_message", "productInfo": "夏季新款女装连衣裙..." }
```

## 识别降级链

技能使用多层识别降级链保证鲁棒性：

| 层级 | 方法 | 用途 |
|------|------|------|
| CDP (L0) | `cap.cdp.eval` | 直读 DOM，CSS 选择器匹配 |
| UIA (L1) | `cap.uia.find` | Windows UI Automation（注入点） |
| OCR (L2) | `cap.ocr.readText` | 屏幕文字识别（注入点） |
| VLM (L3) | `cap.vlm.ask` | 视觉语言模型理解（注入点） |

当 CDP 层找不到元素时（如动态渲染、Shadow DOM），自动降级到 OCR/VLM 层。

## CDP 标签页切换说明

由于 CDP HTTP API 始终操作第一个页面 target（`first_target_ws_url()`），本技能通过以下策略处理新标签页：

1. 点击「联系商家」后，通过 `getTargets()` 检测新标签页
2. 获取新标签页的 URL
3. 使用 `cap.cdp.eval('window.location.href = ...')` 将当前 CDP target 导航到新 URL
4. 这等效于"切换"到新标签页（原页面被覆盖，但可在下次循环中重新导航回去）

## 参数速查

| 参数 | 类型 | 说明 |
|------|------|------|
| `action` | string | 动作名称 |
| `goal` | string | 任务目标描述 |
| `keywords` | string[] | 选品关键词列表 |
| `messageTemplate` | string | 沟通文案模板，支持 `{keywords}` 占位符 |
| `roomUrl` | string | 微信小店选品页面 URL |
| `maxMerchants` | number | 最多联系商家数量（默认 5） |
| `maxRounds` | number | 最大执行轮次（默认 50） |
| `recognition` | string[] | 识别能力链顺序 |
| `startNodeId` | string | 从指定节点恢复执行 |

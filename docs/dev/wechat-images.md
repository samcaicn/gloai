# 微信图片收发

微信本人私聊通道接收文字、图片及同一消息中的多张图片，并可回传任务明确导出的图片。图片仍属于原来的渠道任务，不创建新的运行状态机。

## 接收

`WechatMessage.userContent()` 合并文字内容，并保留图片列表的原始顺序。只含文字项的消息如果引用了一张图片，会从 `ref_msg.message_item` 取出图片，和本次提问一起导入。消息本身带有附件时只处理本次附件；只有引用编号时，在同一微信绑定和会话最近登记的 512 条来源记录中查找原图。原消息按 `message_id`（缺失时按首个非空的项级 `msg_id`）登记，引用按 `svr_id`（缺失时按被引项的 `msg_id`）匹配。导入时原子保存路径、大小与 SHA-256；重启后核对文件再交给任务。原图缺失或已改变时，请用户重发。`WechatChannel` 先验证当前绑定的本人私聊，并在下载前查消息去重账本；不接收群消息。`WechatApi.downloadImage()` 从微信 CDN 下载、按 iLink 的 AES-128-ECB / PKCS#7 规则解密。

宿主 `StoredWechatImages` 逐张暂存图片，经 Android 解码边界检查与必要的缩放处理后，调用已有的 `Attachments.withTaskFiles` 导入当前线程工作区。附件仍使用 `AttachmentLines` 格式，因此新任务、追问回答、持久队列及 `read_file` 都复用原有路径。主模型不支持图片时，`read_file` 按已有逻辑明确反馈能力限制。

整批图片成功导入后，先原子写入微信 pending 账本，再交给任务，最后推进游标。重放 pending 使用原来的工作区路径，不重复下载；任务持久登记后才确认消息。下载或导入失败会写入独立的失败通知，消息不会退化为缺图的文字任务。暂停、换绑或停止期间的旧导入会回滚本批文件。

Mika 的本地资源限额：每条消息最多 8 张图片，每张下载图片最多 10 MiB；这是客户端限额，不是微信服务端的限制。入站图片需可由 Android 解码，原始像素最多 64 MiPixels，解码时有界采样；交给任务的图片最多 5 MiB、最长边 4096 像素。普通 PNG/JPEG/WebP 在无需调整时保留原字节；GIF 作为静态图片导入。微信媒体引用和密钥不写入任务提示词，CDN 请求不带 Bot 鉴权。

## 回复

用户要求回传的截图可用 `phone_screenshot` / `web_screenshot` 的 `savePath` 保存为工作区内的 PNG，再调用现有 `share_file` 导出。无视觉能力的主模型也可保存截图；截图保护和屏幕访问门禁与原工具一致。

`TaskService` 从本次运行真实的 `SharedFile` 导出记录中挑选图片，不解析最终回答中的任意文件路径。`ResultDelivery` 先持久化文字和每张图片的独立送达记录，再逐项发送。`WechatChannel` 只读取 MediaStore 导出 URI，核对大小和已有 SHA-256，随后调用 `getuploadurl`、上传加密图片、重新验证绑定状态，并通过 `sendmessage` 发送 `image_item`。

上传失败记为 Failed；图片消息已经发出但响应不确定时记为 Uncertain。恢复时只发送 Pending 项，不重复发送 Delivered 或 Uncertain 项；任一图片失败或不确定都不会把整批结果记为全部送达。图片本身每张最多 10 MiB；有效图片格式按内容签名检查。

## 验证入口

- Core：`WechatImagesTest` 验证加密互通、CDN 上传下载、媒体请求构造、鉴权隔离、大小限制及取消；`ScreenshotExportTest` 验证截图保存和保护。
- App JVM：`WechatImageFilesTest`、`WechatImageChannelTest` 验证真实文件导入、消息重放、失败通知、绑定变化及图片回复；`ResultDeliveryTest`、`FileOutboxStoreTest`、`RunRecoveryTest` 覆盖独立送达和重启恢复。
- 设备：`WechatImagePreparationTest` 检查 Android 图片解码和采样。没有设备时只编译此测试，不代表实际运行。
- 微信联调：本人私聊发单图、文字加多图并让 Mika 读取；请求截图并回传；下载中暂停/换绑；测试失败和重启恢复。需要真实绑定和手机，MockWebServer 测试不替代该验收。

协议依据是腾讯官方插件 [Tencent/openclaw-weixin](https://github.com/Tencent/openclaw-weixin/tree/0ad1ae91347a938f52f1e4e2abd6dcda51e05f9e) 的 [协议文档](https://github.com/Tencent/openclaw-weixin/blob/0ad1ae91347a938f52f1e4e2abd6dcda51e05f9e/docs/protocol_zh_CN.md)、上传/下载及图片消息构造实现。Mika 使用自身 Kotlin 客户端对接，无新增 OpenClaw 运行时依赖。

# 飞书消息（能力包示例）

```
manifest.json         P1a：只带技能，可以直接安装
manifest.full.json    P1b：加 send 工具（webhook），当前会以 only_skills_supported 拒绝
skills/feishu/SKILL.md
scripts/send.py       工具脚本：POST 到飞书自定义机器人 webhook
build.py              打 zip（只用 Python 标准库）
```

## 构建

```bash
python examples/plugins/com.example.feishu/build.py
# 只打技能版:
python examples/plugins/com.example.feishu/build.py --only skill
```

zip 条目统一用 `/`，只包含 manifest、`skills/` 和（工具版的）`scripts/`。

## 安装后的行为

### P1a（技能）

- 插件技能名是 `com.example.feishu/feishu`，`skill_read` 可写 `plugin:com.example.feishu/feishu`。
- 停用插件后，下一次任务里搜不到；卸载会删技能。

### P1b（工具）

- manifest 里的 `send` 变成 `mcp_com_example_feishu__send`，风险按 `external` 走现有审批。
- 脚本约定：参数 JSON 由 P1b 写到 `MIKA_PLUGIN_ARGS` 指向的临时文件，脚本最后一行输出 JSON。
- 没有 `network` 权限时不能联网；有权限时走 Mika 的全局代理。

### P3（secret）

- 正式用法应把 webhook/secret 放进插件设置（`secrets.bin`），脚本从 `MIKA_PLUGIN_SECRETS` 读，而不是每次把 webhook 放在工具参数里。现在的参数写法只是 P1b 阶段的过渡。

## 手动验证工具脚本

```bash
echo '{"webhook":"https://open.feishu.cn/open-apis/bot/v2/hook/xxx","text":"来自 Mika 的测试"}' \
  | python examples/plugins/com.example.feishu/scripts/send.py
```

真实 webhook 会真的发消息；先确认目标和内容。脚本不打印 webhook/secret，失败时最后一行是 `{"ok": false, "error": ...}`。

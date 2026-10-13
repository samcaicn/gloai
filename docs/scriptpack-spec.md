# WeAuto 话术包（.wpk）格式规范 v1.0

> 定稿 2026-10-13｜消费端：Android 探针（com.jev.probe）｜生成端：Python 工具链（纯标准库）
> 代码：`tools/scriptpack/`（生成/校验/自测）· `app/src/main/java/com/jev/probe/core/scriptpack/`（导入/解密/生效）

## 1. 目标与边界

定义一种可商用分发的话术包格式，四条硬约束：

1. **加密**——包内话术（系统提示词、风格、示例）静态加密，拿到文件也读不到内容；
2. **防破解**——防篡改、防伪造、防换机共用、防改本地时钟续期；
3. **有使用期限**——`expires_at` 到期自动失效，失效后 AI 回复回退内置默认话术；
4. **导入即生效**——App 导入后 `ReplyClient.draft()` 的 system prompt / temperature / few-shot 按包覆盖。

**诚实声明**：客户端没有绝对防破解。本方案把成本提到「逆向 App + 伪造签名或 dump 运行期内存」量级，并对接服务端撤销（v1.1 预留）。**不要**当强 DRM 用。

## 2. 文件布局（.wpk，小端）

```
"WPK1"          4B   magic
format_version  1B   = 0x01
flags           1B   bit0=device_binding  bit1=requires_online_activation(v1.1)
header_len      2B   LE，header JSON 字节数
header_json     var  明文 UTF-8 JSON（§3）
payload_len     4B   LE，密文区字节数（= 明文 + 16B tag）
ciphertext      var  AES-256-GCM，16B tag 附在尾部
sig_len         2B   LE
signature       var  ECDSA P-256/SHA-256，DER
```

- **AAD** = 文件中密文之前的全部字节（含 `payload_len`）；
- **签名覆盖** = AAD ‖ ciphertext（除 sig_len/signature 外的全部字节）。两层认证重叠是故意的：GCM tag 保完整，ECDSA 保「谁做的」。

## 3. header（明文 JSON，被签名覆盖，不可篡改）

| 字段 | 类型 | 说明 |
|---|---|---|
| `pack_id` | string | 全局唯一，如 `wpk_chuqing_2026_v1`；KDF info 的一部分 + 文件名 |
| `name` / `version` / `author` | string/int | 展示与署名 |
| `issued_at` / `expires_at` | int64 unix 秒 | 到期**双写**（header + payload），不一致即拒 |
| `kdf_salt` | b64url 16B | 每包随机 |
| `nonce` | b64url 12B | 每包随机 |
| `device_hashes` | [hex 32] | 允许的设备集合；**v1.0 一个包最多绑一台设备**，多设备签多个包 |
| `payload_sha256` | b64url 32B | 明文 payload 摘要，解密后复核 |
| `min_app_version` | string | 低于此版本拒导入 |

## 4. payload（解密后 JSON，话术本体）

| 字段 | 说明 |
|---|---|
| `system_prompt` | 话术核心（人设/任务），**替换**默认系统提示词 |
| `style` | `tone` 语气 / `persona` 人设 / `taboo` 禁忌 / `length_hint` 篇幅，App 组装为风格块 |
| `few_shot` | [{user, assistant}]，插在 system 后、user 前 |
| `params` | `temperature`（0..2）/ `max_tokens` / `candidates`（1..5） |
| `relationship_hint` | 关系阶段提示，进 system prompt |
| `expires_at` | 与 header 必须一致 |

**输出契约留在 App 侧**（`PackPrompts`）：话术包决定人设与风格，不决定「只输出 JSON 数组」的解析协议——否则劣质包能让候选回复解析器失效。

## 5. 密钥与算法（两端逐字节一致）

| 项 | 参数 |
|---|---|
| 内容加密 | AES-256-GCM，12B nonce，16B tag |
| KDF | HKDF-SHA256，salt=`kdf_salt`，info=`"weauto-scriptpack-v1\|"+pack_id`，L=32 |
| IKM | `master_key ‖ device_hash`；未绑设备时 device_hash=16B 全零 |
| device_hash | SHA-256(DeviceId 稳定值) 前 16B，hex 展示（= `DeviceId.shortHash`） |
| 签名 | ECDSA P-256 / SHA-256 / DER（Android 侧 `Signature.getInstance("SHA256withECDSA")`） |
| master | 32B；App 侧 XOR 0x5A 逆序混淆存放（`AppPackCrypto.MASTER_OBFUSCATED`），生成端 `--master-hex`/`PACK_MASTER_HEX` |

**为什么允许 master 被逆向**：伪造包没有意义——没有签名私钥，验签就过不了。master 泄密只影响「攻击者解自己的包」。

## 6. 防破解分层

| 攻击路径 | 对策 | 落点 |
|---|---|---|
| 直接读文件 / 备份导出 | 内容 AES-256-GCM；header 仅元数据 | §2 |
| 改 expires_at 续期 | header 被 ECDSA 签名覆盖；payload 双写 | §3 |
| 自制话术包 | 无签名私钥 → 验签失败；公钥内嵌 App | §5 |
| 改系统时钟 | effective_now = 本地 + 网络时间偏移；每次联网（导入/Worker Date 头）校准，clamp ±7 天 | §7 |
| 换机 / 多机共用 | `device_hashes` 签名覆盖；IKM 混入 device_hash，KDF 错 → tag 失败 | §3/§5 |
| dump 运行期内存 | 明文只活内存，TTL 缓存 5 分钟；不写 logcat/崩溃日志/云备份 | §8 |
| root 拖私有目录 | .wpk 仍是密文；index 走 SecureStore（AndroidKeyStore） | §8 |
| 抓激活票据（v1.1） | 短期票据 + 设备绑定 + 服务端可撤销 | §7 |

## 7. 期限判定与时间锚

```
effective_now = System.currentTimeMillis() + clock_offset_ms   # clamp ±7 天
clock_offset_ms = Worker /health 的 Date 响应头 - 本地时间      # 导入时/联网时刷新
```

- `effective_now >= expires_at` → 回退默认话术，设置页标红提示续期；
- 首次无网络时间记录时用本地时间（已知窗口，靠首激活联网收紧）；
- **在线激活（v1.1 预留）**：`requires_online_activation=1` 的包，导入时上报 pack_id+device_hash，服务端下发短期 secret 参与 IKM；停发即远程撤销。

## 8. App 端集成

| 文件 | 职责 |
|---|---|
| `core/scriptpack/AppPackCrypto.kt` | 混淆 master + HKDF/AES-GCM/deviceHash |
| `core/scriptpack/ScriptPack.kt` | 解析/验签/设备/到期/解密；`PackError` 码 |
| `core/scriptpack/ScriptPackStore.kt` | 导入（SAF uri）/列表/启用/删除；index 加密；时钟偏移；`effectivePack()`（TTL 缓存，任何异常返回 null） |
| `core/scriptpack/PackPrompts.kt` | 组装 system prompt（话术+风格+输出契约）；`DEFAULT_DRAFT_SYS` |
| `jev/ReplyClient.kt` | `draft()` 覆盖 sys/temperature/fewShot；chat/chatFallback 支持 few-shot 消息 |
| `SettingsActivity.kt` | 「话术包」卡：导入按钮 / 到期状态 / 启用 / 删除 / 本机绑定码 |

纪律：解密后明文不落盘、不进日志、不进 `exportAll` 云备份；logcat 只记 pack_id 与错误码。

## 9. 生成端工具链（纯标准库，CI 零依赖）

```
tools/scriptpack/
  packcrypto.py     # 算法单一事实源：HKDF/AES-GCM/ECDSA/装配解析（与 Android 逐字节一致）
  make_pack.py      # genkey / sign / info
  unpack_pack.py    # 验签+解密导出明文（客服排查）
  selftest.py       # FIPS-197 AES KAT + ECDSA 域性质 + round-trip + 8 条拒收路径
  examples/chuqing.json
```

```bash
python3 tools/scriptpack/make_pack.py genkey --out sig.pem --pub-out pub.pem
# 把 genkey 打印的 SPKI 行整行替换 ScriptPack.PACK_VERIFY_PUBKEY_B64 后发版
python3 tools/scriptpack/make_pack.py sign --script examples/chuqing.json \
    --sign-key sig.pem --days 365 --out chuqing.wpk
python3 tools/scriptpack/make_pack.py sign ... --device-hex <设置页显示的绑定码>   # 绑机
python3 tools/scriptpack/unpack_pack.py --pack chuqing.wpk --pub pub.pem
python3 tools/scriptpack/selftest.py
```

CI：`.github/workflows/scriptpack-selftest.yml` 在 push 命中 `tools/scriptpack/**` 时跑 py_compile + selftest + 示例包签验（含「错误公钥必须拒收」反向用例）；私钥只活在 CI 运行内。

**公钥轮换** = 换 App 内嵌公钥（发版）。正式私钥线下保管，永不进仓库/CI。测试 master 与测试公钥是占位值，正式发行两端同步替换。

## 10. 已知限制（对商户/用户如实说明）

1. 已 root + 主动 dump 内存的攻击者可拿到当期话术明文；
2. 首次导入前未联网时，改本地时钟有 ≤ 时钟偏移的续期窗口；
3. v1.0 一个包绑一台设备；多设备 = 签多个包；
4. `params.candidates` 偏离 3 时解析器仍取前 3 条（降级可用）；
5. 客户端无远程撤销，v1.1 在线激活补上。

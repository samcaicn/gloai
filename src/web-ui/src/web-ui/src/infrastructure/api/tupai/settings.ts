// 设置读写相关 Tauri 命令封装。
// 命令名已对齐后端 lib.rs 的 invoke_handler 注册：
//   settingsGet → get_config  (legacy.rs: get_config(app) —— 无参数，返回整个 AppConfig)
//   settingsSet → set_config  (legacy.rs: set_config(app, key, value) —— key/value 均为 String)
import { invoke } from './invoke';

// 后端 get_config 不接受 key，返回完整 AppConfig 对象（key 保留在 invoke 对象中以维持函数签名，后端 serde 忽略未知字段）。
export async function settingsGet(key: string): Promise<any> {
  return invoke('get_config', { key });
}

// 后端 set_config 期望 (key: String, value: String)。
export async function settingsSet(key: string, value: any): Promise<void> {
  return invoke<void>('set_config', { key, value });
}

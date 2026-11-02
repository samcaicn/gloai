// 本地配置存储抽象层（对齐 weauto/kv.js 的思路）：
// 统一 JSON 序列化，对外暴露 get / put。底层走 Tauri command -> 磁盘 config.json。
import { api } from "./api";

class LocalKV {
  async get(): Promise<any> {
    return api.loadConfig();
  }
  async put(value: any): Promise<any> {
    return api.saveConfig(value);
  }
}

export const kv = new LocalKV();

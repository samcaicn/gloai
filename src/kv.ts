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
  /** 局部更新（深合并）：只覆盖传入的字段，不抹掉其它页面写入的配置节点。 */
  async patch(value: any): Promise<any> {
    return api.patchConfig(value);
  }
}

export const kv = new LocalKV();

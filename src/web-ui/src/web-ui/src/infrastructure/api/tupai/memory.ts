// 长记忆相关 Tauri 命令封装。
// 命令名已对齐后端 lib.rs 的 invoke_handler 注册：
//   memorySave   → add_memory      (legacy.rs: add_memory(summary, content, source, workspace_path?))
//   memorySearch → memory_search   (memory_evolution.rs: memory_search(query, workspace?, limit?))
//   memoryList   → get_memories    (legacy.rs: get_memories(workspace_filter?))
//   memoryDelete → delete_memory   (legacy.rs: delete_memory(id))
//   memoryClear  → memory_clear    (memory_ext.rs: memory_clear(app) —— DELETE FROM memories，已实现)
import { invoke } from './invoke';

export interface MemoryEntry {
  id?: string;
  text: string;
  tags?: string[];
  metadata?: Record<string, any>;
}

export interface MemorySearchParams {
  query: string;
  topK?: number;
}

// 后端 add_memory 期望 (summary, content, source, workspace_path?)，返回 MemoryEntry。
// 此处把 entry.text 同时作为 summary/content，source 固定 'manual'；tags/metadata 后端暂不支持。
export async function memorySave(entry: MemoryEntry): Promise<string> {
  return invoke<string>('add_memory', {
    summary: entry.text,
    content: entry.text,
    source: 'manual',
  });
}

// 后端 memory_search 期望 (query, workspace?, limit?)。
export async function memorySearch(params: MemorySearchParams): Promise<MemoryEntry[]> {
  return invoke<MemoryEntry[]>('memory_search', {
    query: params.query,
    limit: params.topK,
  });
}

// 后端 get_memories 期望 (workspace_filter?)，返回 Vec<MemoryEntry>。
export async function memoryList(): Promise<MemoryEntry[]> {
  return invoke<MemoryEntry[]>('get_memories');
}

// 后端 delete_memory 期望 (id)。
export async function memoryDelete(id: string): Promise<void> {
  return invoke<void>('delete_memory', { id });
}

// 后端 memory_clear（memory_ext.rs）对 memories 表执行 DELETE 清空全部条目，已注册。
export async function memoryClear(): Promise<void> {
  return invoke<void>('memory_clear');
}

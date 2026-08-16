// 文档相关 Tauri 命令封装。
// 后端文档/笔记能力通过 notebook 命令暴露：
//   - 创建笔记：create_notebook_note（folder_id + title，无 content 入参）
//   - 读取笔记：get_notebook_note
//   - 编辑笔记：update_notebook_note（note_id + title + content，title 必填）
//   - 列表：list_notebook_tree（目录+笔记元信息，非模板）
// 本桥接将 doc_* 命名映射到上述实际命令。
// 注：后端无模板概念，docListTemplates 暂保留 TODO。
import { invoke } from './invoke';

export interface DocCreateInput {
  templateId?: string;
  title: string;
  content?: string;
}

export interface DocEditInput {
  docId: string;
  content: string;
}

// 映射到 create_notebook_note：notebook 无模板概念，templateId 无等价入参（忽略）；
// create 命令不接受 content，content? 暂不写入（如需写入须追加 update_notebook_note）。
export async function docCreate(input: DocCreateInput): Promise<string> {
  const note = await invoke<any>('create_notebook_note', {
    folderId: null,
    title: input.title,
  });
  return note.id;
}

// 映射到 get_notebook_note。
export async function docRead(docId: string): Promise<any> {
  return invoke('get_notebook_note', { noteId: docId });
}

// 映射到 update_notebook_note：该命令强制要求 title，而 DocEditInput 仅含 docId+content；
// 先读取现有 title 再更新，避免清空标题。
export async function docEdit(input: DocEditInput): Promise<void> {
  const note = await invoke<any>('get_notebook_note', { noteId: input.docId });
  await invoke<void>('update_notebook_note', {
    noteId: input.docId,
    title: note.title,
    content: input.content,
  });
}

// 后端无 doc_list_templates 命令（notebook 仅提供 list_notebook_tree，无模板概念）。
// 抛出明确错误而非静默返回假数据，避免调用方误以为拿到真实模板。
export async function docListTemplates(): Promise<any[]> {
  throw new Error('doc_list_templates not implemented in backend (notebook has no template concept)');
}

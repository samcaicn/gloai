// Mesh (安全 P2P) Tauri 命令封装。
// 命令名已对齐后端 hermes::mesh::commands（lib.rs invoke_handler 注册）：
//   meshCreate            → mesh_create            (joinCode, availableSkills) 身份后端自动派生
//   meshJoin              → mesh_join              (ticket, availableSkills)   身份后端自动派生
//   meshLeave             → mesh_leave             ()
//   meshStatus            → mesh_status            () → Option<MeshStatus>（未激活返回 null）
//   meshSubmitRequirement → mesh_submit_requirement (text)  仅协调者
//   meshListPeers         → mesh_list_peers        () → Vec<ClientInfo>
//   meshSendFile          → mesh_send_file         (path)  P1 blobs 接线，P0 stub
//
// 身份自动化铁律：tenant_id / device_fingerprint 由后端从 tenant.json + hardware_id(SHA-256)
// 自动派生（与服务器设备身份一致），前端永不接触。前端只负责 join_code / ticket /
// available_skills（available_skills 由 UI 从技能列表自动探测 + 用户勾选）。
//
// 后端类型见 hermes/mesh/ainl.rs::ClientInfo 与 hermes/mesh/mod.rs::MeshStatus，
// 均为 #[serde(rename_all = "camelCase")]，前端类型与之对齐。
import { invoke } from './invoke';

/** mesh 角色：协调者（创建者）/ 执行者（加入者）。后端 format!("{:?}", role).to_lowercase()。 */
export type MeshRole = 'coordinator' | 'executor';

/** 后端 MeshStatus（hermes/mesh/mod.rs），camelCase 序列化。 */
export interface MeshStatus {
  role: MeshRole;
  /** 本机 EndpointId（Ed25519 公钥 hex）。 */
  endpointId: string;
  /** EndpointAddr 的 Debug 格式化串（含 EndpointId + 传输地址集）。 */
  addr: string;
  /** 创建/加入时用的 join_code。 */
  joinCode: string;
  /** 已知对端数量。 */
  peers: number;
}

/** 后端 ClientInfo（hermes/mesh/ainl.rs），camelCase 序列化。 */
export interface MeshPeer {
  /** 客户端标识（= device_fingerprint）。 */
  clientId: string;
  tenantId: string;
  deviceFingerprint: string;
  /** 当前负载（heartbeat 携带，0 = 空闲）。 */
  currentLoad: number;
  /** 该对端可执行的技能 id 列表。 */
  availableSkills: string[];
  priority: string;
  firstSeenTs: number;
  lastActiveTs: number;
}

/** mesh_create 返回：状态 + 分享给加入者的 base32 ticket。 */
export interface MeshCreateResult {
  status: MeshStatus;
  ticket: string;
}

/**
 * 作为协调者创建 mesh，返回入场 ticket。
 * 身份（tenant_id / device_fingerprint）由后端自动派生。
 */
export async function meshCreate(input: {
  joinCode: string;
  availableSkills: string[];
}): Promise<MeshCreateResult> {
  return invoke<MeshCreateResult>('mesh_create', {
    joinCode: input.joinCode,
    availableSkills: input.availableSkills,
  });
}

/**
 * 作为执行者加入已有 mesh。
 * 身份（tenant_id / device_fingerprint）由后端自动派生。
 */
export async function meshJoin(input: {
  ticket: string;
  availableSkills: string[];
}): Promise<MeshStatus> {
  return invoke<MeshStatus>('mesh_join', {
    ticket: input.ticket,
    availableSkills: input.availableSkills,
  });
}

/** 离开当前 mesh。 */
export async function meshLeave(): Promise<void> {
  return invoke<void>('mesh_leave');
}

/** 当前 mesh 状态；未激活返回 null。非 Tauri 运行时归一化为 null。 */
export async function meshStatus(): Promise<MeshStatus | null> {
  // invoke 在非 Tauri (web 预览 / jsdom) 静默返回 undefined；归一化为 null
  // 使运行时行为与类型契约一致，避免调用方忘记 undefined 守卫。
  return (await invoke<MeshStatus | null>('mesh_status')) ?? null;
}

/** 提交需求（仅协调者有意义；执行者调用后端返回错误）。 */
export async function meshSubmitRequirement(text: string): Promise<string> {
  return invoke<string>('mesh_submit_requirement', { text });
}

/** 列出已知对端的 ClientInfo 快照；mesh 未激活返回空数组。非 Tauri 运行时归一化为 []。 */
export async function meshListPeers(): Promise<MeshPeer[]> {
  // invoke 在非 Tauri (web 预览 / jsdom) 静默返回 undefined；归一化为 []
  // 使运行时行为与类型契约一致，避免调用方对 undefined 取 .length 抛错。
  return (await invoke<MeshPeer[]>('mesh_list_peers')) ?? [];
}

/** 发送文件（P1：blobs 接线；P0 后端为 stub）。 */
export async function meshSendFile(path: string): Promise<string> {
  return invoke<string>('mesh_send_file', { path });
}

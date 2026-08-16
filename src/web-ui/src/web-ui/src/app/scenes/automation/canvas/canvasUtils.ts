// 画布工具函数：分类推断、节点指纹、从当前节点往后「重新录制」的去重追加。
import { NODE_SIZE } from '../flowchart/flowchartAdapter';
import { deriveCategory } from './nodeMeta';

// ── 节点指纹（与 flowchartAdapter.mergeFlowcharts 同算法，用于去重）──
function stableStringify(v: unknown): string {
  if (v === null || typeof v !== 'object') return JSON.stringify(v);
  if (Array.isArray(v)) return '[' + v.map(stableStringify).join(',') + ']';
  const obj = v as Record<string, unknown>;
  const keys = Object.keys(obj).sort();
  return '{' + keys.map(k => JSON.stringify(k) + ':' + stableStringify(obj[k])).join(',') + '}';
}

export function nodeFingerprint(n: any): string {
  const metaStr = n.meta ? stableStringify(n.meta) : '';
  return [n.type || '', n.label || '', n.action || '', metaStr].join('\u001f');
}

const GAP_Y = 120;

export interface AppendResult {
  flowchart: any;
  added: number;
  deduped: number;
}

/**
 * 把一次录制得到的新流程图（recorded：start..end）的步骤，去重后追加到画布 anchor 节点之后。
 * - 提取 recorded 中 start→end 之间的步骤（按连接顺序）；
 * - 与画布现有节点按指纹去重（重复步骤跳过并计数）；
 * - 新步骤按锚点坐标向下排布，保持手动布局不被 dagre 覆盖；
 * - 重连：anchor → 首个新步骤；末个新步骤 → 锚点原下游首个节点（若有）。
 */
export function appendRecordingAfterNode(canvas: any, anchorId: string, recorded: any): AppendResult {
  const canvasNodes: any[] = Array.isArray(canvas?.nodes) ? canvas.nodes : [];
  const canvasConns: any[] = Array.isArray(canvas?.connections) ? canvas.connections : [];

  const recNodes: any[] = Array.isArray(recorded?.nodes) ? recorded.nodes : [];
  const recConns: any[] = Array.isArray(recorded?.connections) ? recorded.connections : [];

  const startNode = recNodes.find(n => n.type === 'start');
  const recById = new Map(recNodes.map(n => [n.id, n]));

  // 沿连接链从 start 取出步骤顺序
  const ordered: any[] = [];
  const walked = new Set<string>();
  let cur = startNode ? recConns.find(c => c.from === startNode.id)?.to : undefined;
  let guard = 0;
  while (cur && !walked.has(cur) && guard++ < 2000) {
    walked.add(cur);
    const n = recById.get(cur);
    if (n && n.type !== 'end') ordered.push(n);
    const nxt = recConns.find(c => c.from === cur && c.to !== startNode?.id);
    cur = nxt ? nxt.to : undefined;
  }

  // 去重
  const existingFps = new Set(canvasNodes.map(nodeFingerprint));
  const addedFps = new Set<string>();
  const newOps: any[] = [];
  let deduped = 0;
  const ts = Date.now();
  for (const op of ordered) {
    const fp = nodeFingerprint(op);
    if (existingFps.has(fp) || addedFps.has(fp)) {
      deduped++;
      continue;
    }
    addedFps.add(fp);
    newOps.push({ ...op, id: `re-${ts}-${newOps.length}-${op.id}` });
  }

  const anchorNode = canvasNodes.find(n => n.id === anchorId);
  const anchorPos = anchorNode?.position && typeof anchorNode.position.x === 'number'
    ? anchorNode.position
    : { x: 0, y: 0 };

  // 新步骤按锚点向下排布
  newOps.forEach((op, i) => {
    op.position = { x: anchorPos.x, y: anchorPos.y + (i + 1) * GAP_Y };
  });

  // 重连
  const oldTargets = canvasConns
    .filter(c => c.from === anchorId)
    .map(c => c.to)
    .filter(t => t !== anchorId);
  const keptConns = canvasConns.filter(c => c.from !== anchorId);
  const resultNodes = [...canvasNodes];
  const resultConns = [...keptConns];
  const nodeExists = (id: string) => resultNodes.some(n => n.id === id);

  if (newOps.length > 0) {
    resultNodes.push(...newOps);
    resultConns.push({ from: anchorId, to: newOps[0].id });
    for (let i = 0; i < newOps.length - 1; i++) {
      resultConns.push({ from: newOps[i].id, to: newOps[i + 1].id });
    }
    const tailTarget = oldTargets.find(t => nodeExists(t));
    if (tailTarget) {
      resultConns.push({ from: newOps[newOps.length - 1].id, to: tailTarget });
    }
  } else {
    // 无新步骤：还原锚点原有下游连接，保持链路不断
    for (const t of oldTargets) {
      if (nodeExists(t)) resultConns.push({ from: anchorId, to: t });
    }
  }

  return {
    flowchart: { ...(canvas || {}), nodes: resultNodes, connections: resultConns },
    added: newOps.length,
    deduped,
  };
}

// ═══════════════════════════════════════════════════════════════════
// 小循环检测与合并
// 录制产出的流程图是线性链（start → op1 → op2 → … → end）。当用户重复
// 执行同一组操作（如「点击→输入」反复多次），去重只会合并连续相同的单个
// 动作，无法识别「一段子序列整体重复」的小循环。这里提供检测 + 合并工具，
// 供 FlowchartScene 在加载后弹窗让用户确认是否合并。
// ═══════════════════════════════════════════════════════════════════

/** 沿连接链从 start 取出有序操作节点（排除 start/end 骨架）。 */
export function orderedOpNodes(flowchart: any): any[] {
  const nodes: any[] = Array.isArray(flowchart?.nodes) ? flowchart.nodes : [];
  const conns: any[] = Array.isArray(flowchart?.connections) ? flowchart.connections : [];
  const startNode = nodes.find((n) => n.type === 'start');
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const ordered: any[] = [];
  const walked = new Set<string>();
  let cur = startNode ? conns.find((c) => c.from === startNode.id)?.to : undefined;
  let guard = 0;
  while (cur && !walked.has(cur) && guard++ < 5000) {
    walked.add(cur);
    const n = byId.get(cur);
    if (n && n.type !== 'end') ordered.push(n);
    const nxt = conns.find((c) => c.from === cur && c.to !== startNode?.id);
    cur = nxt ? nxt.to : undefined;
  }
  return ordered;
}

export interface LoopCandidate {
  /** 有序操作节点列表中的起始下标 */
  startIdx: number;
  /** 重复的子序列长度（节点数） */
  patternLen: number;
  /** 重复次数（>=2） */
  repeats: number;
  /** 被覆盖的所有节点 id（含每次重复） */
  nodeIds: string[];
}

/**
 * 检测线性链中的小循环：相邻重复的子序列。
 *
 * 算法：对有序操作节点按指纹序列做相邻块重复扫描。对每个起点 i，尝试模式
 * 长度 L=1..8，若 [i, i+L) 与 [i+L, i+2L) 指纹完全相同则继续向后计数重复
 * 次数。取「重复次数 × 模式长度」最大的候选，贪心向后继续扫描，避免重叠。
 *
 * @param minRepeats 最小重复次数，默认 2（即至少重复一次才算循环）
 * @param maxPatternLen 最大模式长度，默认 8
 */
export function detectSmallLoops(flowchart: any, options: { minRepeats?: number; maxPatternLen?: number } = {}): LoopCandidate[] {
  const { minRepeats = 2, maxPatternLen = 8 } = options;
  const ops = orderedOpNodes(flowchart);
  if (ops.length < 2) return [];
  const fps = ops.map(nodeFingerprint);
  const n = fps.length;
  const candidates: LoopCandidate[] = [];
  const consumed = new Set<number>(); // 已被某个候选覆盖的下标

  for (let i = 0; i < n; i++) {
    if (consumed.has(i)) continue;
    let best: { len: number; reps: number } | null = null;
    const maxL = Math.min(maxPatternLen, Math.floor((n - i) / minRepeats));
    for (let L = 1; L <= maxL; L++) {
      // 检查 [i, i+L) 是否与后续块连续相同
      let reps = 1;
      while (i + (reps + 1) * L <= n) {
        const baseStart = i;
        const cmpStart = i + reps * L;
        let same = true;
        for (let k = 0; k < L; k++) {
          if (fps[baseStart + k] !== fps[cmpStart + k]) { same = false; break; }
        }
        if (!same) break;
        reps++;
      }
      if (reps >= minRepeats) {
        // 偏好「总覆盖节点数」更大的候选
        const score = reps * L;
        if (!best || score > best.len * best.reps) {
          best = { len: L, reps };
        }
      }
    }
    if (best) {
      const nodeIds: string[] = [];
      for (let r = 0; r < best.reps; r++) {
        for (let k = 0; k < best.len; k++) {
          const idx = i + r * best.len + k;
          nodeIds.push(ops[idx].id);
          consumed.add(idx);
        }
      }
      candidates.push({ startIdx: i, patternLen: best.len, repeats: best.reps, nodeIds });
      // 跳过已覆盖区域
      i += best.len * best.reps - 1;
    }
  }
  return candidates;
}

/**
 * 合并检测到的小循环：保留第一轮迭代的节点，删除后续重复节点，在第一轮
 * 末尾插入一个「↻ 循环 N 次」标记节点（meta.loopCount / meta.loopPattern），
 * 并重连后续链路。返回合并后的新流程图（不修改原对象）。
 */
export function mergeSmallLoops(flowchart: any, candidates: LoopCandidate[]): any {
  if (!candidates || candidates.length === 0) return flowchart;
  const nodes: any[] = Array.isArray(flowchart?.nodes) ? flowchart.nodes : [];
  const conns: any[] = Array.isArray(flowchart?.connections) ? flowchart.connections : [];
  const ops = orderedOpNodes(flowchart);

  // 收集所有要删除的节点 id（每次重复的第 2..N 轮）+ 标记信息
  const removeIds = new Set<string>();
  const markers: Array<{ afterNodeId: string; repeats: number; patternFps: string[] }> = [];

  for (const cand of candidates) {
    const firstRoundIds = cand.nodeIds.slice(0, cand.patternLen);
    for (let r = 1; r < cand.repeats; r++) {
      for (let k = 0; k < cand.patternLen; k++) {
        removeIds.add(cand.nodeIds[r * cand.patternLen + k]);
      }
    }
    const firstRoundOps = firstRoundIds.map((id) => ops.find((o) => o.id === id)).filter(Boolean);
    const patternFps = firstRoundOps.map((o: any) => nodeFingerprint(o));
    markers.push({
      afterNodeId: firstRoundIds[firstRoundIds.length - 1],
      repeats: cand.repeats,
      patternFps,
    });
  }

  // 构建新节点列表：跳过被删除节点，并在标记位置后插入循环标记节点。
  // 同时建立 afterNodeId → markerId 的直接映射，避免后续用模糊查找匹配错标记。
  const ts = Date.now();
  const afterToMarker = new Map<string, string>();
  const newNodes: any[] = [];
  for (const node of nodes) {
    if (removeIds.has(node.id)) continue;
    newNodes.push(node);
    const marker = markers.find((m) => m.afterNodeId === node.id);
    if (marker) {
      const markerId = `loop-${ts}-${newNodes.length}`;
      afterToMarker.set(node.id, markerId);
      newNodes.push({
        id: markerId,
        type: 'process',
        label: `↻ 循环 ${marker.repeats} 次`,
        action: 'loop',
        meta: {
          loopCount: marker.repeats,
          loopPattern: marker.patternFps,
          loopMarker: true,
        },
        position: node.position
          ? { x: node.position.x, y: node.position.y + GAP_Y }
          : { x: 0, y: 0 },
      });
    }
  }

  // 重连：删除涉及被移除节点的连接；对每个标记，把 afterNodeId 的下游改接到
  // 标记节点，标记节点再连到 afterNodeId 原本的下游（首个仍存在的节点）。
  const validIds = new Set(newNodes.map((n) => n.id));
  const newConns: any[] = [];
  const seenConn = new Set<string>();
  const pushConn = (c: any) => {
    if (!validIds.has(c.from) || !validIds.has(c.to)) return;
    const k = `${c.from}\u001f${c.to}\u001f${c.label ?? ''}`;
    if (seenConn.has(k)) return;
    seenConn.add(k);
    newConns.push(c);
  };

  // 计算每个 afterNode 的下游（首个未被删除的）
  const downstreamOf = (afterNodeId: string): string | undefined =>
    conns
      .filter((c) => c.from === afterNodeId)
      .map((c) => c.to)
      .find((t) => !removeIds.has(t) && t !== afterNodeId);

  for (const m of markers) {
    const markerId = afterToMarker.get(m.afterNodeId);
    if (!markerId) continue;
    const downstream = downstreamOf(m.afterNodeId);
    pushConn({ from: m.afterNodeId, to: markerId });
    if (downstream) pushConn({ from: markerId, to: downstream });
  }

  // 保留原有连接中仍有效的（两端都在，且不是被标记替换掉的 afterNode→downstream 直连）
  for (const c of conns) {
    if (removeIds.has(c.from) || removeIds.has(c.to)) continue;
    const isReplaced = markers.some((m) => {
      if (c.from !== m.afterNodeId) return false;
      return c.to === downstreamOf(m.afterNodeId);
    });
    if (isReplaced) continue;
    pushConn(c);
  }

  return { ...(flowchart || {}), nodes: newNodes, connections: newConns };
}

export { deriveCategory, NODE_SIZE };

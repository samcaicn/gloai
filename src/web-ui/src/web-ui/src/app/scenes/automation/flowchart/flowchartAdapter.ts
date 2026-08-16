// 流程图数据适配器：现有 flowchart 格式 ↔ React Flow 格式
// 含 dagre 自动分层布局、判断分支左右分叉、泳道支持、技术术语本地化、图标与状态色
import dagre from 'dagre';
import { MarkerType, type Node } from '@xyflow/react';

// ── 节点尺寸（与 CSS .rf-node-body min-width/min-height 对齐）──
// 高度增至 72 以容纳 meta 补充说明行
export const NODE_SIZE = { width: 180, height: 72 };
export const NODE_GAP = { nodesep: 36, ranksep: 64 };

// ── 技术术语中文映射 ──
export const TECH_TERM_ZH: Record<string, string> = {
  cdp: '网页检测',
  uia: '控件检测',
  ocr: '文字识别',
  vlm: '视觉理解',
  llm: '大模型',
};
export const ACTION_VERB_ZH: Record<string, string> = {
  type: '输入',
  click: '点击',
  wait: '等待',
  read: '读取',
  eval: '执行',
  screenshot: '截图',
  navigate: '跳转',
};
export function localizeTiers(tiers: string[], locale?: string): string {
  if (!Array.isArray(tiers) || tiers.length === 0) return '';
  if (locale === 'en') return tiers.join('›').toUpperCase();
  return tiers.map(t => TECH_TERM_ZH[String(t).toLowerCase()] || t).join('›');
}
export function localizeAction(action: string, locale?: string): string {
  if (!action) return action;
  const s = String(action);
  if (locale === 'en') return s;
  const m = s.match(/^([a-zA-Z]+)\.(.+)$/);
  if (m) {
    const prefix = TECH_TERM_ZH[m[1].toLowerCase()] || m[1];
    const verb = ACTION_VERB_ZH[m[2].toLowerCase()] || m[2];
    return `${prefix}·${verb}`;
  }
  return s.replace(/\b(cdp|uia|ocr|vlm|llm)\b/gi, k => TECH_TERM_ZH[k.toLowerCase()] || k);
}

// ── 边标签本地化 ──
export function localizeEdgeLabel(label: string, locale?: string): string {
  if (!label) return '';
  if (locale === 'en') return label;
  const map: Record<string, string> = {
    yes: '是', no: '否',
    true: '真', false: '假',
    success: '成功', fail: '失败', error: '错误',
    pass: '通过', fail_alt: '未通过',
  };
  return map[String(label).toLowerCase()] || label;
}

// ── 判断分支归一化 ──
export function normalizeBranch(label?: string): 'yes' | 'no' | null {
  if (!label) return null;
  const l = String(label).toLowerCase();
  if (l === 'yes' || l === '是' || l === 'true' || l === 'success' || l === 'pass') return 'yes';
  if (l === 'no' || l === '否' || l === 'false' || l === 'fail' || l === 'error') return 'no';
  return null;
}

// ── 节点类型主题 ──
export const TYPE_THEME: Record<string, { bg: string; bg2: string; shape: string; icon: string; label: string }> = {
  start:     { bg: '#22c55e', bg2: '#16a34a', shape: 'stadium',       icon: '▶',  label: '开始' },
  end:       { bg: '#ef4444', bg2: '#dc2626', shape: 'stadium',       icon: '■',  label: '结束' },
  process:   { bg: '#3b82f6', bg2: '#2563eb', shape: 'rect',          icon: '⚙',  label: '处理' },
  decision:  { bg: '#f59e0b', bg2: '#d97706', shape: 'diamond',       icon: '◆',  label: '判断' },
  io:        { bg: '#a855f7', bg2: '#9333ea', shape: 'parallelogram', icon: '⇄',  label: '输入输出' },
  connector: { bg: '#64748b', bg2: '#475569', shape: 'circle',        icon: '●',  label: '连接' },
};

// ── 状态色映射 ──
export const STATUS_THEME: Record<string, { color: string; bg: string }> = {
  running:   { color: '#3b82f6', bg: 'rgba(59,130,246,.12)' },
  ok:        { color: '#22c55e', bg: 'rgba(34,197,94,.12)' },
  completed: { color: '#22c55e', bg: 'rgba(34,197,94,.12)' },
  success:   { color: '#22c55e', bg: 'rgba(34,197,94,.12)' },
  stopped:   { color: '#f59e0b', bg: 'rgba(245,158,11,.12)' },
  pending:   { color: '#f59e0b', bg: 'rgba(245,158,11,.12)' },
  fail:      { color: '#ef4444', bg: 'rgba(239,68,68,.12)' },
  failed:    { color: '#ef4444', bg: 'rgba(239,68,68,.12)' },
  error:     { color: '#ef4444', bg: 'rgba(239,68,68,.12)' },
};

export function getStatusString(status: unknown): string | null {
  if (!status) return null;
  return typeof status === 'string' ? status : ((status as any)?.status || null);
}

// ── 默认边配置 ──
export const defaultEdgeOptions = {
  type: 'smoothstep',
  markerEnd: { type: MarkerType.ArrowClosed, color: '#64748b', width: 18, height: 18 },
  labelBgStyle: { fill: '#1f1f2e', fillOpacity: 0.92 },
  labelBgPadding: [4, 2] as [number, number],
  labelBgBorderRadius: 4,
  labelStyle: { fontSize: 11, fontWeight: 600, fill: '#e4e4e7' },
  style: { stroke: '#64748b', strokeWidth: 1.6 },
};

// ── dagre 自动分层布局 ──
function layoutWithDagre(nodes: any[], edges: any[], options: { width?: number; height?: number; rankdir?: string; nodesep?: number; ranksep?: number } = {}): Map<string, { x: number; y: number }> {
  const {
    width = NODE_SIZE.width,
    height = NODE_SIZE.height,
    rankdir = 'TB',
    nodesep = NODE_GAP.nodesep,
    ranksep = NODE_GAP.ranksep,
  } = options;
  const g = new dagre.graphlib.Graph();
  g.setGraph({
    rankdir,
    nodesep,
    ranksep,
    edgesep: 16,
    marginx: 32,
    marginy: 32,
    ranker: 'network-simplex',
  } as any);
  g.setDefaultEdgeLabel(() => ({}));
  nodes.forEach(n => {
    const theme = TYPE_THEME[n.type] || TYPE_THEME.process;
    const w = theme.shape === 'diamond' ? width + 24 : width;
    const h = theme.shape === 'diamond' ? height + 16 : height;
    g.setNode(n.id, { width: w, height: h });
  });
  edges.forEach(e => g.setEdge(e.source, e.target, { weight: 1 }));
  try {
    dagre.layout(g);
  } catch {
    // 布局失败时退化为线性排列
  }
  const positions = new Map<string, { x: number; y: number }>();
  nodes.forEach((n, i) => {
    const ln = g.node(n.id);
    const theme = TYPE_THEME[n.type] || TYPE_THEME.process;
    const w = theme.shape === 'diamond' ? width + 24 : width;
    const h = theme.shape === 'diamond' ? height + 16 : height;
    if (ln) {
      positions.set(n.id, { x: ln.x - w / 2, y: ln.y - h / 2 });
    } else {
      positions.set(n.id, { x: 0, y: i * (h + ranksep) });
    }
  });
  return positions;
}

// ── 自动生成默认泳道 ──
const LANE_LABELS_ZH: Record<string, string> = {
  'lane-start': '起止',
  'lane-process': '处理',
  'lane-decision': '判断',
  'lane-io': '输入输出',
};
const LANE_LABELS_EN: Record<string, string> = {
  'lane-start': 'Start/End',
  'lane-process': 'Process',
  'lane-decision': 'Decision',
  'lane-io': 'I/O',
};

export function autoLanes(flowchart: any, locale?: string): any[] {
  if (!flowchart || !flowchart.nodes) return [];
  const labels = locale === 'en' ? LANE_LABELS_EN : LANE_LABELS_ZH;
  const groups: Record<string, { id: string; label: string; nodeIds: string[] }> = {
    'lane-start': { id: 'lane-start', label: labels['lane-start'], nodeIds: [] },
    'lane-process': { id: 'lane-process', label: labels['lane-process'], nodeIds: [] },
    'lane-decision': { id: 'lane-decision', label: labels['lane-decision'], nodeIds: [] },
    'lane-io': { id: 'lane-io', label: labels['lane-io'], nodeIds: [] },
  };
  flowchart.nodes.forEach((n: any) => {
    const t = n.type || 'process';
    if (t === 'start' || t === 'end') groups['lane-start'].nodeIds.push(n.id);
    else if (t === 'process' || t === 'connector') groups['lane-process'].nodeIds.push(n.id);
    else if (t === 'decision') groups['lane-decision'].nodeIds.push(n.id);
    else if (t === 'io') groups['lane-io'].nodeIds.push(n.id);
  });
  return Object.values(groups).filter(g => g.nodeIds.length > 0);
}

function nodeTypeMap(nodes: any[]): Map<string, string> {
  const m = new Map<string, string>();
  nodes.forEach(n => m.set(n.id, n.type || 'process'));
  return m;
}

function styleEdgeForBranch(branch: string | null): Record<string, any> {
  if (branch === 'yes') {
    return {
      style: { stroke: '#22c55e', strokeWidth: 1.8 },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#22c55e', width: 18, height: 18 },
    };
  }
  if (branch === 'no') {
    return {
      style: { stroke: '#ef4444', strokeWidth: 1.8 },
      markerEnd: { type: MarkerType.ArrowClosed, color: '#ef4444', width: 18, height: 18 },
    };
  }
  return {};
}

function buildEdges(rawConns: any[], locale: string | undefined, _typeMap?: Map<string, string>): any[] {
  return rawConns.map((c, i) => {
    const label = c.label || '';
    const branch = normalizeBranch(label);
    const sourceHandle = branch;
    const localized = label ? localizeEdgeLabel(label, locale) : '';
    return {
      id: `e_${c.from}_${c.to}_${i}`,
      source: c.from,
      target: c.to,
      label: localized,
      sourceHandle,
      targetHandle: null,
      type: 'smoothstep',
      data: { originalLabel: label, branch },
      ...styleEdgeForBranch(branch),
    };
  });
}

// ── 转换为 React Flow 格式 ──
export function toReactFlow(flowchart: any, options: { locale?: string; statusMap?: Record<string, any>; editable?: boolean; lanes?: any[]; useAutoLanes?: boolean } = {}): { nodes: any[]; edges: any[] } {
  if (!flowchart || !flowchart.nodes) return { nodes: [], edges: [] };
  const { locale, statusMap = {}, editable = false, lanes, useAutoLanes = false } = options;
  const rawNodes = flowchart.nodes;
  const rawConns = flowchart.connections || [];

  const positions = layoutWithDagre(
    rawNodes,
    rawConns.map((c: any) => ({ source: c.from, target: c.to })),
  );

  const rfNodes = rawNodes.map((n: any) => {
    const hasSaved = n.position && typeof n.position.x === 'number' && typeof n.position.y === 'number';
    const pos = hasSaved ? n.position : (positions.get(n.id) || { x: 0, y: 0 });
    const theme = TYPE_THEME[n.type] || TYPE_THEME.process;
    // 派生节点描述：label 为主标题，meta 中的 text/selector 为补充说明
    const meta = n.meta || null;
    const metaText = meta?.text || meta?.selector || '';
    return {
      id: n.id,
      type: n.type || 'process',
      position: pos,
      data: {
        label: n.label,
        recognition: n.recognition || null,
        action: n.action || null,
        branches: n.branches || null,
        meta,
        metaText,
        status: statusMap[n.id] || null,
        locale,
        theme,
      },
      draggable: editable,
      selectable: true,
    };
  });

  const effectiveLanes = lanes && lanes.length > 0
    ? lanes
    : (useAutoLanes ? autoLanes(flowchart, locale) : []);

  if (effectiveLanes.length > 0) {
    const nodeLaneMap = new Map<string, string>();
    effectiveLanes.forEach(lane => {
      lane.nodeIds.forEach((id: string) => nodeLaneMap.set(id, lane.id));
    });
    const laneBounds = new Map<string, { minX: number; minY: number; maxX: number; maxY: number }>();
    effectiveLanes.forEach(lane => {
      const laneNodes = rfNodes.filter((n: Node) => nodeLaneMap.has(n.id) && nodeLaneMap.get(n.id) === lane.id);
      if (laneNodes.length === 0) return;
      const minX = Math.min(...laneNodes.map((n: Node) => n.position.x)) - 20;
      const minY = Math.min(...laneNodes.map((n: Node) => n.position.y)) - 40;
      const maxX = Math.max(...laneNodes.map((n: Node) => n.position.x)) + NODE_SIZE.width + 20;
      const maxY = Math.max(...laneNodes.map((n: Node) => n.position.y)) + NODE_SIZE.height + 20;
      laneBounds.set(lane.id, { minX, minY, maxX, maxY });
    });
    const laneNodes = effectiveLanes
      .filter(l => laneBounds.has(l.id))
      .map(l => {
        const b = laneBounds.get(l.id)!;
        return {
          id: l.id,
          type: 'swimlane',
          position: { x: b.minX, y: b.minY },
          style: { width: b.maxX - b.minX, height: b.maxY - b.minY },
          data: { label: l.label, locale },
          draggable: false,
          selectable: false,
        };
      });
    rfNodes.forEach((n: Node) => {
      const laneId = nodeLaneMap.get(n.id);
      if (laneId && laneBounds.has(laneId)) {
        const b = laneBounds.get(laneId)!;
        n.parentId = laneId;
        n.extent = 'parent';
        n.position = { x: n.position.x - b.minX, y: n.position.y - b.minY };
      }
    });
    return { nodes: [...laneNodes, ...rfNodes], edges: buildEdges(rawConns, locale, nodeTypeMap(rawNodes)) };
  }

  return { nodes: rfNodes, edges: buildEdges(rawConns, locale, nodeTypeMap(rawNodes)) };
}

// ── 转回原格式（用于保存）──
export function fromReactFlow(rfNodes: any[], rfEdges: any[], original: any = {}): any {
  const nodes = rfNodes
    .filter(n => n.type !== 'swimlane')
    .map(n => {
      const out: any = {
        id: n.id,
        type: n.type,
        label: n.data?.label || n.id,
      };
      if (n.position && typeof n.position.x === 'number' && typeof n.position.y === 'number') {
        out.position = { x: n.position.x, y: n.position.y };
      }
      if (n.data?.action) out.action = n.data.action;
      if (n.data?.meta) out.meta = n.data.meta;
      if (n.data?.sourceEventIdx != null) out.sourceEventIdx = n.data.sourceEventIdx;
      if (n.data?.branches) out.branches = n.data.branches;
      if (n.data?.recognition) out.recognition = n.data.recognition;
      return out;
    });

  const validIds = new Set(nodes.map((n: any) => n.id));
  const connections = rfEdges
    .filter(e => validIds.has(e.source) && validIds.has(e.target))
    .map(e => {
      const c: any = { from: e.source, to: e.target };
      const label = e.data?.originalLabel;
      if (label) c.label = label;
      return c;
    });

  return {
    ...original,
    nodes,
    connections,
  };
}

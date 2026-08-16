// React Flow 自定义节点组件（TypeScript 移植版）
import React from 'react';
import { Handle, Position } from '@xyflow/react';
import { localizeTiers, localizeAction, getStatusString, STATUS_THEME } from './flowchartAdapter';

const SHAPES: Record<string, (w: number, h: number) => string> = {
  rect:          (w, h) => `M 0 0 H ${w} V ${h} H 0 Z`,
  stadium:       (w, h) => {
    const r = h / 2;
    return `M ${r} 0 H ${w - r} A ${r} ${r} 0 0 1 ${w - r} ${h} H ${r} A ${r} ${r} 0 0 1 ${r} 0 Z`;
  },
  ellipse:       (w, h) => `M ${w / 2} 0 A ${w / 2} ${h / 2} 0 1 0 ${w / 2} ${h} A ${w / 2} ${h / 2} 0 1 0 ${w / 2} 0 Z`,
  diamond:       (w, h) => `M ${w / 2} 0 L ${w} ${h / 2} L ${w / 2} ${h} L 0 ${h / 2} Z`,
  parallelogram: (w, h) => { const s = 14; return `M ${s} 0 H ${w} L ${w - s} ${h} H 0 Z`; },
  circle:        (w, h) => {
    const r = Math.min(w, h) / 2;
    const cx = w / 2, cy = h / 2;
    return `M ${cx + r} ${cy} A ${r} ${r} 0 1 0 ${cx - r} ${cy} A ${r} ${r} 0 1 0 ${cx + r} ${cy} Z`;
  },
};

const DEFAULT_W = 180;
const DEFAULT_H = 72;

interface ShapeProps {
  shape: string;
  width: number;
  height: number;
  theme?: { bg: string; bg2: string };
  selected?: boolean;
  statusStr?: string | null;
}

const Shape: React.FC<ShapeProps> = ({ shape, width, height, theme, selected, statusStr }) => {
  const path = SHAPES[shape] || SHAPES.rect;
  const statusTheme = statusStr ? STATUS_THEME[statusStr] : null;
  const fill = statusTheme ? statusTheme.bg : 'var(--rf-node-fill, #1f1f2e)';
  const stroke = selected
    ? 'var(--rf-accent, #facc15)'
    : (statusTheme ? statusTheme.color : (theme ? theme.bg : '#3b82f6'));
  const strokeWidth = selected ? 2.5 : (statusStr ? 2 : 1.5);
  const gradId = `g-${theme?.bg?.slice(1) || 'default'}`;
  return (
    <svg
      width={width}
      height={height}
      className={`rf-shape-svg ${selected ? 'is-selected' : ''} ${statusStr ? `is-status is-status-${statusStr}` : ''}`}
      style={{ display: 'block', overflow: 'visible' }}
    >
      {!statusTheme && (
        <defs>
          <linearGradient id={gradId} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor={theme?.bg || '#3b82f6'} />
            <stop offset="100%" stopColor={theme?.bg2 || '#2563eb'} />
          </linearGradient>
        </defs>
      )}
      <path
        d={path(width, height)}
        className={`rf-shape rf-shape--${shape}`}
        fill={statusTheme ? fill : `url(#${gradId})`}
        stroke={stroke}
        strokeWidth={strokeWidth}
        strokeLinejoin="round"
      />
    </svg>
  );
};

interface NodeContentProps {
  data: any;
  theme?: { icon: string } | null;
}

const NodeContent: React.FC<NodeContentProps> = ({ data, theme }) => {
  const statusStr = getStatusString(data.status);
  const statusIdx = data.status && typeof data.status === 'object' ? data.status.idx : null;
  // 副标题：识别方式 + 动作类型组合显示
  // 如 "网页检测·点击" / "控件检测·输入" / "文字识别·读取"
  const recognitionTier = data.recognition && data.recognition.length > 0
    ? localizeTiers(data.recognition, data.locale)
    : '';
  const actionLabel = data.action ? localizeAction(data.action, data.locale) : '';
  // 组合副标题：recognition + action 用 · 分隔
  const tier = [recognitionTier, actionLabel].filter(Boolean).join(' · ');
  // 补充说明：meta 中的元素文本或选择器（截断后显示）
  const metaText: string = data.metaText || '';
  const metaDisplay = metaText ? (metaText.length > 40 ? metaText.slice(0, 38) + '…' : metaText) : '';
  return (
    <div className="rf-node-content">
      {theme?.icon && (
        <span className="rf-node-icon" aria-hidden>{theme.icon}</span>
      )}
      <div className="rf-node-text">
        <span className="rf-node-title">{data.label || actionLabel || '未命名步骤'}</span>
        {tier && <span className="rf-node-sub">{tier}</span>}
        {metaDisplay && <span className="rf-node-meta" title={metaText}>{metaDisplay}</span>}
      </div>
      {statusStr && (
        <span className={`rf-node-badge rf-badge-${statusStr}`} title={statusStr}>
          {statusStr}{statusIdx != null ? `·${statusIdx + 1}` : ''}
        </span>
      )}
    </div>
  );
};

interface NodeShellProps {
  data: any;
  selected?: boolean;
  theme?: any;
  handles?: React.ReactNode;
}

const NodeShell: React.FC<NodeShellProps> = ({ data, selected, theme, handles }) => {
  const statusStr = getStatusString(data.status);
  const w = DEFAULT_W;
  const h = theme?.shape === 'diamond' ? DEFAULT_H + 16 : DEFAULT_H;
  return (
    <div
      className={`rf-node ${selected ? 'is-selected' : ''} ${statusStr ? `is-status is-status-${statusStr}` : ''}`}
      style={{ width: w, height: h }}
    >
      <Shape
        shape={theme?.shape || 'rect'}
        width={w}
        height={h}
        theme={theme}
        selected={selected}
        statusStr={statusStr}
      />
      <div className="rf-node-overlay">
        <NodeContent data={data} theme={theme} />
      </div>
      {handles}
    </div>
  );
};

export const StartNode: React.FC<any> = ({ data, selected }) => (
  <NodeShell
    data={data}
    selected={selected}
    theme={data.theme}
    handles={<Handle type="source" position={Position.Bottom} className="rf-handle" />}
  />
);

export const EndNode: React.FC<any> = ({ data, selected }) => (
  <NodeShell
    data={data}
    selected={selected}
    theme={data.theme}
    handles={<Handle type="target" position={Position.Top} className="rf-handle" />}
  />
);

export const ProcessNode: React.FC<any> = ({ data, selected }) => (
  <NodeShell
    data={data}
    selected={selected}
    theme={data.theme}
    handles={
      <>
        <Handle type="target" position={Position.Top} className="rf-handle" />
        <Handle type="source" position={Position.Bottom} className="rf-handle" />
      </>
    }
  />
);

export const DecisionNode: React.FC<any> = ({ data, selected }) => (
  <NodeShell
    data={data}
    selected={selected}
    theme={data.theme}
    handles={
      <>
        <Handle type="target" position={Position.Top} className="rf-handle" />
        <Handle type="source" position={Position.Right} id="yes" className="rf-handle rf-handle-yes" />
        <Handle type="source" position={Position.Bottom} id="no" className="rf-handle rf-handle-no" />
      </>
    }
  />
);

export const IoNode: React.FC<any> = ({ data, selected }) => (
  <NodeShell
    data={data}
    selected={selected}
    theme={data.theme}
    handles={
      <>
        <Handle type="target" position={Position.Top} className="rf-handle" />
        <Handle type="source" position={Position.Bottom} className="rf-handle" />
      </>
    }
  />
);

export const ConnectorNode: React.FC<any> = ({ data, selected }) => (
  <NodeShell
    data={data}
    selected={selected}
    theme={data.theme}
    handles={
      <>
        <Handle type="target" position={Position.Top} className="rf-handle" />
        <Handle type="source" position={Position.Bottom} className="rf-handle" />
      </>
    }
  />
);

export const SwimlaneNode: React.FC<any> = ({ data, selected }) => (
  <div className={`rf-swimlane ${selected ? 'is-selected' : ''}`}>
    <div className="rf-swimlane-header">
      <span className="rf-swimlane-dot" />
      {data.label}
    </div>
    <div className="rf-swimlane-body" />
  </div>
);

export const nodeTypes = {
  start: StartNode,
  end: EndNode,
  process: ProcessNode,
  decision: DecisionNode,
  io: IoNode,
  connector: ConnectorNode,
  swimlane: SwimlaneNode,
};

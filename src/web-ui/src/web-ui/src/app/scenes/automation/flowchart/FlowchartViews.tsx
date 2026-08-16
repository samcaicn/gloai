// React Flow 流程图视图组件（TypeScript 移植版）
import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import {
  ReactFlow,
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  ReactFlowProvider,
  Panel,
  NodeToolbar,
  Position,
  useNodesState,
  useEdgesState,
  addEdge,
  useReactFlow,
  useNodesInitialized,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { nodeTypes } from './FlowchartNodes';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import {
  toReactFlow,
  fromReactFlow,
  localizeEdgeLabel,
  normalizeBranch,
  defaultEdgeOptions,
  TYPE_THEME,
  STATUS_THEME,
  getStatusString,
} from './flowchartAdapter';
import type { Edge, Connection, Node, NodeChange, EdgeChange } from '@xyflow/react';

// 读取主题类型（dark/light）
function getThemeType(): 'dark' | 'light' {
  const themeType = document.documentElement.getAttribute('data-theme-type');
  if (themeType === 'light' || themeType === 'dark') return themeType;
  const dataTheme = document.documentElement.getAttribute('data-theme');
  if (dataTheme?.includes('light')) return 'light';
  if (dataTheme?.includes('dark')) return 'dark';
  return 'dark';
}

// MiniMap maskColor 根据主题动态调整
function getMiniMapMaskColor(): string {
  return getThemeType() === 'dark' ? 'rgba(0,0,0,0.4)' : 'rgba(255,255,255,0.5)';
}

type TFunc = (key: string, options?: Record<string, unknown>) => string;

function miniMapColor(n: Node): string {
  const statusStr = getStatusString(n.data?.status);
  if (statusStr && STATUS_THEME[statusStr]) return STATUS_THEME[statusStr].color;
  const t = n.type || '';
  return (TYPE_THEME[t] || TYPE_THEME.process).bg;
}

function getNodeTypeOptions(t: TFunc) {
  return [
    { value: 'start',    label: t('flowchartScene.nodeTypes.start') },
    { value: 'process',  label: t('flowchartScene.nodeTypes.process') },
    { value: 'decision', label: t('flowchartScene.nodeTypes.decision') },
    { value: 'io',       label: t('flowchartScene.nodeTypes.io') },
    { value: 'end',      label: t('flowchartScene.nodeTypes.end') },
  ];
}

function getEdgeLabelOptions(t: TFunc) {
  return [
    { value: '',        label: t('flowchartScene.edgeLabels.none') },
    { value: 'yes',     label: t('flowchartScene.edgeLabels.yes') },
    { value: 'no',      label: t('flowchartScene.edgeLabels.no') },
    { value: 'success', label: t('flowchartScene.edgeLabels.success') },
    { value: 'fail',    label: t('flowchartScene.edgeLabels.fail') },
  ];
}

// ═══════════════════════════════════════════════════════════════════
// 自定义 hook：窗口恢复可见时重新 fitView
// ═══════════════════════════════════════════════════════════════════
function useFitViewOnVisible(trackValue: unknown, options: { enableDataChangeFitView?: boolean; trigger?: unknown; fitViewKey?: unknown } = {}) {
  const { enableDataChangeFitView = true, trigger = null, fitViewKey = null } = options;
  const { fitView } = useReactFlow();
  const nodesInitialized = useNodesInitialized();

  const fitViewRef = useRef(fitView);
  fitViewRef.current = fitView;
  const nodesInitializedRef = useRef(nodesInitialized);
  nodesInitializedRef.current = nodesInitialized;
  const doFitViewRef = useRef<() => void>(() => {});

  useEffect(() => {
    let cancelled = false;
    const unlistenFns: Array<() => void> = [];
    const timers = new Set<ReturnType<typeof setTimeout>>();
    let token = 0;

    const clearTimers = () => {
      timers.forEach(t => clearTimeout(t));
      timers.clear();
    };

    const doFitView = (duration = 0) => {
      if (cancelled) return;
      clearTimers();
      const myToken = ++token;

      let attempts = 0;
      const maxAttempts = 60;
      const tryFit = () => {
        if (cancelled || myToken !== token) return;
        if (nodesInitializedRef.current) {
          const el = document.querySelector('.rf-canvas');
          if (!el || (el as HTMLElement).offsetWidth === 0 || (el as HTMLElement).offsetHeight === 0) {
            if (++attempts < maxAttempts) {
              timers.add(setTimeout(tryFit, 50));
            }
            return;
          }
          try {
            fitViewRef.current({ padding: 0.3, duration, includeHiddenNodes: false, maxZoom: 1.0 });
          } catch (e) {
            console.warn('[useFitViewOnVisible] fitView failed:', e);
          }
          return;
        }
        if (++attempts < maxAttempts) {
          timers.add(setTimeout(tryFit, 50));
        }
      };
      timers.add(setTimeout(tryFit, 50));
    };
    doFitViewRef.current = () => doFitView(400);

    const onFocus = () => doFitView(400);
    const onResize = () => doFitView(0);
    const onVisibility = () => doFitView(0);
    window.addEventListener('focus', onFocus);
    window.addEventListener('resize', onResize);
    document.addEventListener('visibilitychange', onVisibility);
    unlistenFns.push(() => {
      window.removeEventListener('focus', onFocus);
      window.removeEventListener('resize', onResize);
      document.removeEventListener('visibilitychange', onVisibility);
    });

    import('@tauri-apps/api/window')
      .then(({ getCurrentWindow }) => {
        if (cancelled) return;
        const win = getCurrentWindow();
        win.onFocusChanged(({ payload: focused }) => {
          if (focused) doFitView(400);
        }).then(fn => {
          if (cancelled) { try { fn?.() } catch { /* empty */ } return }
          unlistenFns.push(fn as unknown as () => void);
        }).catch(() => {});
        win.listen('tauri://show', () => doFitView(400)).then(fn => {
          if (cancelled) { try { fn?.() } catch { /* empty */ } return }
          unlistenFns.push(fn as unknown as () => void);
        }).catch(() => {});
      })
      .catch(() => {});

    let resizeObserver: ResizeObserver | null = null;
    const tryAttachObserver = () => {
      if (cancelled || resizeObserver) return;
      const el = document.querySelector('.rf-canvas');
      if (!el) {
        timers.add(setTimeout(tryAttachObserver, 100));
        return;
      }
      let prevW = 0;
      let prevH = 0;
      resizeObserver = new ResizeObserver((entries) => {
        for (const entry of entries) {
          const { width, height } = entry.contentRect;
          if ((prevW === 0 || prevH === 0) && width > 0 && height > 0) {
            doFitView();
          }
          prevW = width;
          prevH = height;
        }
      });
      resizeObserver.observe(el);
    };
    timers.add(setTimeout(tryAttachObserver, 50));
    unlistenFns.push(() => {
      if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
    });

    return () => {
      cancelled = true;
      token++;
      clearTimers();
      unlistenFns.forEach(fn => { try { fn?.() } catch { /* empty */ } });
      unlistenFns.length = 0;
    };
  }, []);

  const isFirstRunRef = useRef(true);
  useEffect(() => {
    if (isFirstRunRef.current) {
      isFirstRunRef.current = false;
      return;
    }
    if (!enableDataChangeFitView) return;
    queueMicrotask(() => {
      try { doFitViewRef.current() } catch (e) {
        console.warn('[useFitViewOnVisible] microtask fitView failed:', e);
      }
    });
  }, [trackValue, enableDataChangeFitView]);

  const prevTriggerRef = useRef(trigger);
  useEffect(() => {
    if (prevTriggerRef.current === trigger) return;
    prevTriggerRef.current = trigger;
    const timer = setTimeout(() => {
      try { doFitViewRef.current() } catch (e) {
        console.warn('[useFitViewOnVisible] trigger fitView failed:', e);
      }
    }, 80);
    return () => clearTimeout(timer);
  }, [trigger]);

  // 显式 fitViewKey: 外部强制 fitView 触发器。每次 key 变化都做一次动画 fitView,
  // 用于「FlowchartScene 切换 app」时主动重置缩放,保证用户看到的总是标准缩放级别。
  const prevFitKeyRef = useRef(fitViewKey);
  useEffect(() => {
    if (prevFitKeyRef.current === fitViewKey) return;
    prevFitKeyRef.current = fitViewKey;
    // 等 React Flow 完成节点渲染再 fit, 防止空容器触发无效 fit。
    const timer = setTimeout(() => {
      try { doFitViewRef.current() } catch (e) {
        console.warn('[useFitViewOnVisible] fitViewKey fitView failed:', e);
      }
    }, 120);
    return () => clearTimeout(timer);
  }, [fitViewKey]);

  useEffect(() => {
    if (!nodesInitialized) return;
    if (!enableDataChangeFitView) return;
    try {
      fitViewRef.current({ padding: 0.3, duration: 0, includeHiddenNodes: false, maxZoom: 1.0 });
    } catch (e) {
      console.warn('[useFitViewOnVisible] nodesInitialized fitView failed:', e);
    }
  }, [nodesInitialized, trackValue, enableDataChangeFitView, fitView]);
}

// ═══════════════════════════════════════════════════════════════════
// 只读流程图视图
// ═══════════════════════════════════════════════════════════════════
interface FlowchartViewInnerProps {
  flowchart: any;
  trace?: any[];
  selectedNodeId?: string | null;
  onSelectNode?: (id: string | null) => void;
  locale?: string;
  useAutoLanes?: boolean;
  hasFlowchart: boolean;
  running?: boolean;
  fitViewKey?: unknown;
}

const FlowchartViewInner: React.FC<FlowchartViewInnerProps> = ({
  flowchart,
  trace,
  selectedNodeId,
  onSelectNode,
  locale,
  useAutoLanes,
  hasFlowchart,
  running,
  fitViewKey,
}) => {
  const { t } = useI18n('common');
  const statusMap = useMemo(() => {
    const m: Record<string, any> = {};
    (trace || []).forEach((t, i) => {
      if (!m[t.nodeId]) m[t.nodeId] = { ...t, idx: i };
    });
    return m;
  }, [trace]);

  const { nodes, edges } = useMemo(
    () => toReactFlow(flowchart, { locale, statusMap, editable: false, useAutoLanes }),
    [flowchart, statusMap, locale, useAutoLanes],
  );

  const nodesWithSelected = useMemo(() => nodes.map((n: Node) => ({
    ...n,
    selected: n.id === selectedNodeId,
  })), [nodes, selectedNodeId]);

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    if (!onSelectNode) return;
    if (node.type === 'start' || node.type === 'end') return;
    onSelectNode(selectedNodeId === node.id ? null : node.id);
  }, [selectedNodeId, onSelectNode]);

  useFitViewOnVisible(flowchart, { trigger: running ? 'running' : 'idle', fitViewKey });

  if (!hasFlowchart) {
    return (
      <div className="rf-canvas-empty-state" role="status">
        <div className="rf-empty-icon" aria-hidden>◌</div>
        <div className="rf-empty-text">{t('flowchartScene.noFlowchart')}</div>
        <div className="rf-empty-hint">{t('flowchartScene.emptyHint')}</div>
      </div>
    );
  }

  return (
    <ReactFlow
      nodes={nodesWithSelected}
      edges={edges}
      nodeTypes={nodeTypes}
      onNodeClick={onNodeClick}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable
      panOnDrag
      zoomOnScroll
      zoomOnPinch
      preventScrolling
      zoomOnDoubleClick={false}
      minZoom={0.15}
      maxZoom={2.5}
      fitView
      fitViewOptions={{ padding: 0.3, maxZoom: 1.0 }}
      proOptions={{ hideAttribution: true }}
      defaultEdgeOptions={defaultEdgeOptions}
      colorMode="system"
    >
      <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
      <Controls showInteractive={false} position="bottom-left" />
      <MiniMap
        nodeColor={miniMapColor}
        nodeStrokeColor={miniMapColor}
        nodeStrokeWidth={2}
        nodeBorderRadius={6}
        maskColor={getMiniMapMaskColor()}
        pannable
        zoomable
        ariaLabel={t('flowchartScene.minimapLabel')}
      />
    </ReactFlow>
  );
};

export interface FlowchartViewProps {
  flowchart: any;
  trace?: any[];
  selectedNodeId?: string | null;
  onSelectNode?: (id: string | null) => void;
  locale?: string;
  useAutoLanes?: boolean;
  running?: boolean;
  fitViewKey?: unknown;
}

export function FlowchartView(props: FlowchartViewProps) {
  const hasFlowchart = !!(props.flowchart && props.flowchart.nodes && props.flowchart.nodes.length > 0);
  return (
    <div className={hasFlowchart ? 'rf-canvas' : 'rf-canvas rf-canvas-empty'}>
      <ReactFlowProvider>
        <FlowchartViewInner {...props} hasFlowchart={hasFlowchart} />
      </ReactFlowProvider>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════════════
// 可编辑流程图视图
// ═══════════════════════════════════════════════════════════════════

interface EditableFlowchartInnerProps {
  flowchart: any;
  onChange?: (fc: any) => void;
  onSave?: () => void;
  saving?: boolean;
  saveStatus?: { ok: boolean; msg: string } | null;
  locale?: string;
  useAutoLanes?: boolean;
  fitViewKey?: unknown;
}

const EditableFlowchartInner: React.FC<EditableFlowchartInnerProps> = ({
  flowchart,
  onChange,
  onSave,
  saving,
  saveStatus,
  locale,
  useAutoLanes,
  fitViewKey,
}) => {
  const { t } = useI18n('common');
  const [rfNodes, setRfNodes, onNodesChange] = useNodesState<Node>([]);
  const [rfEdges, setRfEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [editLabel, setEditLabel] = useState('');
  const [editType, setEditType] = useState('process');
  const [edgeLabel, setEdgeLabel] = useState('');

  const needSyncRef = useRef(false);
  const syncingRef = useRef(false);
  const onChangeRef = useRef(onChange);
  const flowchartRef = useRef(flowchart);
  const deleteBlockedWarnedRef = useRef(false);
  const rfNodesRef = useRef(rfNodes);
  const rfEdgesRef = useRef(rfEdges);

  useEffect(() => { onChangeRef.current = onChange }, [onChange]);
  useEffect(() => { flowchartRef.current = flowchart }, [flowchart]);
  useEffect(() => { rfNodesRef.current = rfNodes }, [rfNodes]);
  useEffect(() => { rfEdgesRef.current = rfEdges }, [rfEdges]);

  useEffect(() => {
    if (syncingRef.current) {
      syncingRef.current = false;
      return;
    }
    if (!flowchart || !flowchart.nodes) return;
    const { nodes, edges } = toReactFlow(flowchart, { locale, editable: true, useAutoLanes });
    setRfNodes(nodes);
    setRfEdges(edges);
  }, [flowchart, locale, useAutoLanes, setRfEdges, setRfNodes]);

  useEffect(() => {
    if (!needSyncRef.current) return;
    needSyncRef.current = false;
    syncingRef.current = true;
    const fc = fromReactFlow(rfNodes, rfEdges, flowchartRef.current);
    onChangeRef.current?.(fc);
  }, [rfNodes, rfEdges]);

  const selectedNode = rfNodes.find(n => n.id === selectedNodeId);
  const selectedEdge = rfEdges.find(e => e.id === selectedEdgeId);

  useEffect(() => {
    if (!selectedNodeId) return;
    const node = rfNodesRef.current.find(n => n.id === selectedNodeId);
    if (node) {
      setEditLabel(String(node.data?.label ?? ''));
      setEditType(node.type || 'process');
    }
  }, [selectedNodeId]);

  useEffect(() => {
    if (!selectedEdgeId) return;
    const edge = rfEdgesRef.current.find(e => e.id === selectedEdgeId);
    if (edge) {
      setEdgeLabel(String(edge.data?.originalLabel ?? ''));
    }
  }, [selectedEdgeId]);

  const handleNodesChange = useCallback((changes: NodeChange[]) => {
    const blocked: string[] = [];
    const safeChanges: NodeChange[] = [];
    for (const c of changes) {
      if (c.type === 'remove') {
        const node = rfNodes.find(n => n.id === c.id);
        if (node && (node.type === 'start' || node.type === 'end')) {
          blocked.push(c.id);
          continue;
        }
      }
      safeChanges.push(c);
    }
    if (blocked.length > 0) {
      if (!deleteBlockedWarnedRef.current) {
        deleteBlockedWarnedRef.current = true;
        setTimeout(() => {
          try { alert(t('flowchartScene.protectedNodeAlert')) } catch { /* empty */ }
        }, 0);
      }
    }
    onNodesChange(safeChanges);
    if (safeChanges.some(c => c.type === 'remove')) {
      needSyncRef.current = true;
      setSelectedNodeId(null);
    }
  }, [onNodesChange, rfNodes, t]);

  const handleNodeDragStop = useCallback(() => {
    needSyncRef.current = true;
  }, []);

  const handleEdgesChange = useCallback((changes: EdgeChange[]) => {
    onEdgesChange(changes);
    if (changes.some(c => c.type === 'remove')) {
      needSyncRef.current = true;
      setSelectedEdgeId(null);
    }
  }, [onEdgesChange]);

  const handleConnect = useCallback((params: Connection) => {
    // ── 连接验证 ──────────────────────────────────────────
    // 1. 禁止自环（source === target）
    if (params.source === params.target) {
      return;
    }
    // 2. 禁止连入 start 节点（开始节点不应有入边）
    //    禁止从 end 节点连出（结束节点不应有出边）
    const srcNode = rfNodesRef.current.find(n => n.id === params.source);
    const tgtNode = rfNodesRef.current.find(n => n.id === params.target);
    if (tgtNode?.type === 'start') return;
    if (srcNode?.type === 'end') return;
    // 3. 禁止重复边（相同 source→target 已存在）
    //    避免用户多次拖拽同一条连接产生冗余边
    const edgeExists = rfEdgesRef.current.some(
      (e) => e.source === params.source && e.target === params.target
        && (e.sourceHandle ?? null) === (params.sourceHandle ?? null),
    );
    if (edgeExists) {
      return;
    }
    // 4. 禁止形成环路（DFS 检测：如果从 target 能到达 source，
    //    则加这条边会形成环）
    //    流程图应该是 DAG（有向无环图），环路会导致执行引擎死循环
    const wouldCreateCycle = (src: string, tgt: string): boolean => {
      const adj = new Map<string, string[]>();
      for (const e of rfEdgesRef.current) {
        const arr = adj.get(e.source) ?? [];
        arr.push(e.target);
        adj.set(e.source, arr);
      }
      // 假设新边已加入：从 tgt 出发能否到达 src
      const visited = new Set<string>();
      const stack = [tgt];
      while (stack.length > 0) {
        const cur = stack.pop()!;
        if (cur === src) return true;
        if (visited.has(cur)) continue;
        visited.add(cur);
        const neighbors = adj.get(cur);
        if (neighbors) stack.push(...neighbors);
      }
      return false;
    };
    if (wouldCreateCycle(params.source, params.target)) {
      // 环路防护：静默拒绝，不弹窗（拖拽体验流畅优先）
      // 用户可以通过节点工具栏的提示看到连接未生效
      return;
    }

    let label = '';
    if (params.sourceHandle === 'yes') label = 'yes';
    else if (params.sourceHandle === 'no') label = 'no';
    const newEdge: Edge = {
      ...params,
      type: 'smoothstep',
      label: label ? localizeEdgeLabel(label, locale) : '',
      data: { originalLabel: label, branch: normalizeBranch(label) },
    } as unknown as Edge;
    setRfEdges(prev => addEdge(newEdge, prev));
    needSyncRef.current = true;
  }, [locale, setRfEdges]);

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    setSelectedNodeId(node.id);
    setSelectedEdgeId(null);
  }, []);

  const onEdgeClick = useCallback((_: React.MouseEvent, edge: Edge) => {
    setSelectedEdgeId(edge.id);
    setSelectedNodeId(null);
  }, []);

  const onPaneClick = useCallback(() => {
    setSelectedNodeId(null);
    setSelectedEdgeId(null);
  }, []);

  const applyNodeEdit = useCallback(() => {
    if (!selectedNodeId) return;
    setRfNodes(prev => prev.map(n => n.id === selectedNodeId
      ? {
          ...n,
          type: editType,
          data: {
            ...n.data,
            label: editLabel.trim() || n.data.label,
            theme: TYPE_THEME[editType] || n.data?.theme,
          },
        }
      : n));
    needSyncRef.current = true;
  }, [selectedNodeId, editLabel, editType, setRfNodes]);

  const deleteSelectedNode = useCallback(() => {
    if (!selectedNode) return;
    if (selectedNode.type === 'start' || selectedNode.type === 'end') {
      alert(t('flowchartScene.protectedNodeAlert'));
      return;
    }
    setRfNodes(prev => prev.filter(n => n.id !== selectedNodeId));
    needSyncRef.current = true;
    setSelectedNodeId(null);
  }, [selectedNode, selectedNodeId, t, setRfNodes]);

  const duplicateSelectedNode = useCallback(() => {
    if (!selectedNode) return;
    if (selectedNode.type === 'start' || selectedNode.type === 'end') return;
    const id = 'n_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 6);
    const pos = selectedNode.position;
    const newNode: Node = {
      id,
      type: selectedNode.type,
      position: { x: pos.x + 32, y: pos.y + 32 },
      data: { ...selectedNode.data, label: (selectedNode.data?.label || '') + t('flowchartScene.nodeCopy') },
      draggable: selectedNode.draggable,
      selectable: true,
      selected: false,
    };
    setRfNodes(prev => [...prev, newNode]);
    // 自动连接副本到原节点的下游（若原节点有下游），保持流程连贯
    const downstreamEdge = rfEdgesRef.current.find(e => e.source === selectedNodeId);
    if (downstreamEdge && downstreamEdge.target !== id) {
      setRfEdges(prev => {
        // 移除原节点→下游的边，改为 原节点→副本→下游
        const withoutOld = prev.filter(e => !(e.source === selectedNodeId && e.target === downstreamEdge.target));
        return [
          ...withoutOld,
          { ...downstreamEdge, id: `e_${selectedNodeId}_${id}_dup`, source: selectedNodeId ?? '', target: id } as unknown as Edge,
          { ...downstreamEdge, id: `e_${id}_${downstreamEdge.target}_dup`, source: id, target: downstreamEdge.target } as unknown as Edge,
        ];
      });
    }
    needSyncRef.current = true;
  }, [selectedNode, selectedNodeId, t, setRfNodes, setRfEdges]);

  // 新增节点类型选择状态（默认 process，可由面板下拉切换）
  const [addNodeType, setAddNodeType] = useState('process');

  const addNewNode = useCallback(() => {
    const id = 'n_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 6);
    const theme = TYPE_THEME[addNodeType] || TYPE_THEME.process;
    // 若有选中节点，新节点放在其右下方并自动连接；否则随机放置
    const anchorPos = selectedNode?.position;
    const position = anchorPos
      ? { x: anchorPos.x + 60, y: anchorPos.y + 60 }
      : { x: 200 + Math.random() * 120, y: 200 + Math.random() * 120 };
    const newNode: Node = {
      id,
      type: addNodeType,
      position,
      data: { label: t('flowchartScene.newStep'), locale, theme },
      draggable: true,
    };
    setRfNodes(prev => [...prev, newNode]);
    // 自动连接：选中节点 → 新节点（end 不应连出）
    if (selectedNode && selectedNode.type !== 'end') {
      const newEdge: Edge = {
        id: `e_${selectedNode.id}_${id}_add`,
        source: selectedNode.id,
        target: id,
        type: 'smoothstep',
        data: { originalLabel: '', branch: null },
      } as unknown as Edge;
      setRfEdges(prev => addEdge(newEdge, prev));
    }
    needSyncRef.current = true;
    setSelectedNodeId(id);
  }, [locale, t, setRfNodes, setRfEdges, selectedNode, addNodeType]);

  const applyEdgeLabel = useCallback((newLabel: string) => {
    if (!selectedEdgeId) return;
    setRfEdges(prev => prev.map(e => {
      if (e.id !== selectedEdgeId) return e;
      const branch = normalizeBranch(newLabel);
      return {
        ...e,
        data: { ...e.data, originalLabel: newLabel, branch },
        label: newLabel ? localizeEdgeLabel(newLabel, locale) : '',
        sourceHandle: branch || undefined,
      } as unknown as Edge;
    }));
    needSyncRef.current = true;
  }, [selectedEdgeId, locale, setRfEdges]);

  const deleteSelectedEdge = useCallback(() => {
    if (!selectedEdgeId) return;
    setRfEdges(prev => prev.filter(e => e.id !== selectedEdgeId));
    needSyncRef.current = true;
    setSelectedEdgeId(null);
  }, [selectedEdgeId, setRfEdges]);

  const { fitView } = useReactFlow();
  const handleFitView = useCallback(() => {
    fitView({ padding: 0.3, duration: 400, maxZoom: 1.0 });
  }, [fitView]);

  useFitViewOnVisible(flowchart, { enableDataChangeFitView: false, fitViewKey });

  if (!flowchart || !flowchart.nodes) {
    return <div className="flowchart-empty">{t('flowchartScene.noFlowchart')}</div>;
  }

  const isProtectedNode = selectedNode?.type === 'start' || selectedNode?.type === 'end';
  const nodeTypeOptions = getNodeTypeOptions(t);
  const edgeLabelOptions = getEdgeLabelOptions(t);

  return (
    <div className="rf-canvas rf-canvas-editable">
      <ReactFlow
        nodes={rfNodes}
        edges={rfEdges}
        nodeTypes={nodeTypes}
        onNodesChange={handleNodesChange}
        onEdgesChange={handleEdgesChange}
        onConnect={handleConnect}
        onNodeClick={onNodeClick}
        onNodeDragStop={handleNodeDragStop}
        onEdgeClick={onEdgeClick}
        onPaneClick={onPaneClick}
        nodesDraggable
        nodesConnectable
        elementsSelectable
        panOnDrag
        zoomOnScroll
        zoomOnPinch
        preventScrolling
        zoomOnDoubleClick={false}
        minZoom={0.15}
        maxZoom={2.5}
        fitView
        fitViewOptions={{ padding: 0.3, maxZoom: 1.0 }}
        proOptions={{ hideAttribution: true }}
        defaultEdgeOptions={defaultEdgeOptions}
        deleteKeyCode={['Delete', 'Backspace']}
        colorMode="system"
      >
        <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
        <Controls showInteractive={false} position="bottom-left" />
        <MiniMap
          nodeColor={miniMapColor}
          nodeStrokeColor={miniMapColor}
          nodeStrokeWidth={2}
          nodeBorderRadius={6}
          maskColor="rgba(0,0,0,0.4)"
          pannable
          zoomable
          ariaLabel={t('flowchartScene.minimapLabel')}
        />

        <Panel position="top-center">
          <div className="rf-global-panel">
            <select
              className="rf-panel-select"
              value={addNodeType}
              onChange={e => setAddNodeType(e.target.value)}
              title={t('flowchartScene.nodeLabel')}
            >
              {nodeTypeOptions.filter(o => o.value !== 'start' && o.value !== 'end').map(o => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
            <button onClick={addNewNode} className="rf-panel-btn" title={t('flowchartScene.addNode')}>
              ＋ {t('flowchartScene.nodeLabel')}
            </button>
            <button onClick={handleFitView} className="rf-panel-btn" title={t('flowchartScene.fitViewTitle')}>
              ⊡ {t('flowchartScene.fitViewBtn')}
            </button>
            {onSave && (
              <button onClick={onSave} disabled={saving} className="rf-panel-btn rf-panel-btn-primary">
                {saving ? t('flowchartScene.savingText') : t('flowchartScene.saveBtn')}
              </button>
            )}
            {saveStatus && (
              <span className={`rf-save-status ${saveStatus.ok ? 'ok' : 'err'}`}>
                {saveStatus.msg}
              </span>
            )}
          </div>
        </Panel>

        {selectedNode && (
          <NodeToolbar nodeId={selectedNodeId || undefined} position={Position.Top} offset={8}>
            <div className="rf-toolbar">
              <button
                onClick={duplicateSelectedNode}
                className="rf-toolbar-btn"
                disabled={isProtectedNode}
                title={isProtectedNode ? t('flowchartScene.protectedNoCopy') : t('flowchartScene.copyNodeTitle')}
              >⧉</button>
              <button
                onClick={deleteSelectedNode}
                className="rf-toolbar-btn rf-toolbar-danger"
                disabled={isProtectedNode}
                title={isProtectedNode ? t('flowchartScene.protectedNoDelete') : t('flowchartScene.deleteNodeTitle')}
              >✕</button>
            </div>
          </NodeToolbar>
        )}
      </ReactFlow>

      <div className="rf-edit-panel">
        {selectedNode ? (
          <div className="rf-edit-section">
            <span className="rf-edit-title">{t('flowchartScene.nodeLabel')}</span>
            <span className="rf-edit-tag">{selectedNode.type}</span>
            <input
              className="rf-edit-input"
              value={editLabel}
              onChange={e => setEditLabel(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') applyNodeEdit() }}
              placeholder={t('flowchartScene.nodeLabelPlaceholder')}
            />
            <select
              className="rf-edit-select"
              value={editType}
              onChange={e => setEditType(e.target.value)}
            >
              {nodeTypeOptions.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
            <button onClick={applyNodeEdit} className="rf-panel-btn rf-panel-btn-primary">{t('flowchartScene.applyBtn')}</button>
          </div>
        ) : selectedEdge ? (
          <div className="rf-edit-section">
            <span className="rf-edit-title">{t('flowchartScene.edgeLabel')}</span>
            <select
              className="rf-edit-select"
              value={edgeLabel}
              onChange={e => { setEdgeLabel(e.target.value); applyEdgeLabel(e.target.value) }}
            >
              {edgeLabelOptions.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
            <button onClick={deleteSelectedEdge} className="rf-panel-btn rf-panel-btn-danger">{t('flowchartScene.deleteBtn')}</button>
          </div>
        ) : (
          <div className="rf-edit-section">
            <span className="rf-edit-hint">
              {t('flowchartScene.editHint')}
            </span>
          </div>
        )}
      </div>
    </div>
  );
};

export interface EditableFlowchartViewProps {
  flowchart: any;
  onChange?: (fc: any) => void;
  onSave?: () => void;
  saving?: boolean;
  saveStatus?: { ok: boolean; msg: string } | null;
  locale?: string;
  useAutoLanes?: boolean;
  fitViewKey?: unknown;
}

export function EditableFlowchartView(props: EditableFlowchartViewProps) {
  return (
    <ReactFlowProvider>
      <EditableFlowchartInner {...props} />
    </ReactFlowProvider>
  );
}

/**
 * PipelinesScene — 流水线管理界面。
 *
 * 功能：
 *   1. 新增流水线：选择技能（内置/已安装/市场/全部）→ 配置参数 → 创建
 *   2. 流水线列表 + 步骤管理
 *   3. 启动 / 暂停 / 停止 / 删除
 *   4. 执行历史：查看每轮执行输出 / 错误 / 耗时
 *   5. 执行日志自动写入 worker_task_log，供 AutoSkill 自进化引擎挖掘
 *   6. 点击流水线在左侧会话栏创建入口（不占用流水线页面）
 *   7. 节点间自动参数传递：自动提取输出字段，自动映射到后续步骤
 *
 * 数据持久化到 DuckDB（pipeline_def 表），通过 Rust IPC 命令操作。
 * 运行状态可跨进程恢复。
 */

import React, { useCallback, useEffect, useMemo, useReducer, useState } from 'react';
import {
  IterationCw, Plus, Play, Pause, Square, Trash2, RefreshCw,
  Loader2, X,
  Search, Box, Globe, Database, Filter, ListTodo,
  Zap, Save, FileText,
} from 'lucide-react';
import { getBuiltinSkills, skillLoad, skillSave } from '@/infrastructure/api/tupai/skill';
import { searchMultiMarket } from '@/infrastructure/api/tupai/multiMarketSkill';
import * as pipelineApi from '@/infrastructure/api/tupai/pipeline';
import type { PipelineDef, PipelineStepDef } from '@/infrastructure/api/tupai/pipeline';
import { Modal, Input, Tag } from '@/component-library';
import { createLogger } from '@/shared/utils/logger';
import { runBuiltinSkill } from '@/infrastructure/api/tupai';
import { useSceneStore } from '@/app/stores/sceneStore';
import { MEditor } from '@/tools/editor/meditor';
import type { EditorInstance } from '@/tools/editor/meditor';
import { useTheme } from '@/infrastructure/theme/hooks/useTheme';
import './PipelinesScene.scss';

const log = createLogger('PipelinesScene');

type SkillTab = 'builtin' | 'installed' | 'market' | 'all';

interface SkillItem {
  skill_id: string;
  skill_name: string;
  description: string;
  version: string;
  source: 'builtin' | 'installed' | 'market';
  category?: string;
  tags?: string[];
  params?: { name: string; type: string; description?: string; enum?: string[]; required?: boolean; defaultValue?: unknown }[];
}

// ── 流水线 ID → Step 执行状态映射 ──
type StepExecStatus = 'pending' | 'running' | 'success' | 'failed';
const stepExecMap = new Map<string, { stepIndex: number; status: StepExecStatus; result?: string; outputFields?: string[] }[]>();

function getStepExecs(pipelineId: string, stepCount: number) {
  if (!stepExecMap.has(pipelineId)) {
    stepExecMap.set(pipelineId, Array.from({ length: stepCount }, (_, i) => ({ stepIndex: i, status: 'pending' as StepExecStatus })));
  }
  return stepExecMap.get(pipelineId)!;
}

// ── 自动参数传递：从技能执行结果中提取可用输出字段 ──
// 递归扫描 result 对象，收集所有顶层和嵌套一层的字段名，
// 供后续步骤自动映射使用。
function extractOutputFields(result: any): string[] {
  if (!result || typeof result !== 'object') return [];
  const fields: string[] = [];
  // 顶层字段
  for (const key of Object.keys(result)) {
    fields.push(key);
    // 嵌套一层的数组/对象字段也收集（如 articles[0].title → articles）
    const val = result[key];
    if (Array.isArray(val) && val.length > 0 && typeof val[0] === 'object') {
      for (const subKey of Object.keys(val[0])) {
        fields.push(`${key}.${subKey}`);
      }
    } else if (typeof val === 'object' && val !== null) {
      for (const subKey of Object.keys(val)) {
        fields.push(`${key}.${subKey}`);
      }
    }
  }
  return [...new Set(fields)];
}

// ── 自动参数映射：根据参数名和可用输出字段智能匹配 ──
// 策略：
//   1. 精确匹配：参数名 === 输出字段名 → $steps[N].field
//   2. 模糊匹配：参数名包含输出字段名（或反之）→ $steps[N].field
//   3. 语义匹配：keywords ↔ products, content ↔ script 等常见映射
const SEMANTIC_MAP: Record<string, string[]> = {
  keywords: ['products', 'articles', 'category', 'title', 'trends'],
  content: ['script', 'summary', 'text', 'description', 'result'],
  sourceContent: ['summary', 'content', 'text', 'description', 'articles'],
  prompt: ['script', 'content', 'summary', 'text'],
  productInfo: ['products', 'competitors', 'price', 'title'],
  query: ['category', 'keywords', 'title', 'trends'],
};

function autoSuggestParamMapping(
  paramName: string,
  paramDesc: string | undefined,
  availableOutputs: { stepIndex: number; fields: string[] }[],
): string | null {
  const name = paramName.toLowerCase();
  const desc = (paramDesc || '').toLowerCase();

  for (const { stepIndex, fields } of availableOutputs) {
    // 1. 精确匹配
    for (const field of fields) {
      const fieldBase = field.split('.')[0];
      if (fieldBase === name || field === name) {
        return `$steps[${stepIndex}].${field}`;
      }
    }
    // 2. 模糊匹配
    for (const field of fields) {
      const fieldBase = field.split('.')[0];
      if (name.includes(fieldBase) || fieldBase.includes(name) ||
          (desc && (desc.includes(fieldBase) || fieldBase.includes(desc)))) {
        return `$steps[${stepIndex}].${field}`;
      }
    }
    // 3. 语义匹配
    const semanticTargets = SEMANTIC_MAP[name];
    if (semanticTargets) {
      for (const target of semanticTargets) {
        for (const field of fields) {
          const fieldBase = field.split('.')[0];
          if (fieldBase === target || field.includes(target)) {
            return `$steps[${stepIndex}].${field}`;
          }
        }
      }
    }
  }
  return null;
}

const PipelinesScene: React.FC = () => {
  const [pipelineList, setPipelineList] = useState<PipelineDef[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // Force re-render when stepExecMap changes (module-level Map, not React state)
  const [, forceExecUpdate] = useReducer(x => x + 1, 0);
  const [modalOpen, setModalOpen] = useState(false);
  const [newName, setNewName] = useState('');

  // 步骤选择 modal
  const [skillModalOpen, setSkillModalOpen] = useState(false);
  const [skillSearchTab, setSkillSearchTab] = useState<SkillTab>('builtin');
  const [skillSearchQuery, setSkillSearchQuery] = useState('');
  const [skillSearchLoading, setSkillSearchLoading] = useState(false);
  const [availableSkills, setAvailableSkills] = useState<SkillItem[]>([]);
  const [allSearchSkills, setAllSearchSkills] = useState<SkillItem[]>([]);
  const [selectedSkill, setSelectedSkill] = useState<SkillItem | null>(null);
  const [stepParams, setStepParams] = useState<Record<string, any>>({});
  // 自动映射建议：记录哪些参数被自动填充了
  const [autoMappedParams, setAutoMappedParams] = useState<Set<string>>(new Set());

  const [historyPipelineId, setHistoryPipelineId] = useState<string | null>(null);

  // 内置流水线模板
  const [templates, setTemplates] = useState<pipelineApi.PipelineTemplate[]>([]);

  // ── 右侧 MD 编辑器面板状态 ──
  const [editingStepIndex, setEditingStepIndex] = useState<number | null>(null);
  const [skillContent, setSkillContent] = useState<string>('');
  const [skillTitle, setSkillTitle] = useState<string>('');
  const [skillLoading, setSkillLoading] = useState(false);
  const [skillDirty, setSkillDirty] = useState(false);
  const editorRef = React.useRef<EditorInstance>(null);

  const openScene = useSceneStore((s) => s.openScene);
  const { isLight } = useTheme();

  const selectedPipeline = pipelineList.find(p => p.id === selectedId) || null;

  // 加载流水线
  const loadPipelines = useCallback(async () => {
    setLoading(true);
    try {
      const defs = await pipelineApi.pipelineList('work');
      setPipelineList(defs);
    } catch (err) {
      log.warn('loadPipelines failed', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadPipelines(); }, [loadPipelines]);

  // 加载内置流水线模板
  useEffect(() => {
    pipelineApi.pipelineGetTemplates().then(setTemplates).catch(() => {});
  }, []);

  // 加载内置技能
  useEffect(() => {
    getBuiltinSkills().then((skills) => setAvailableSkills(skills)).catch(() => {});
  }, []);

  // ── 编辑器内容同步：当 skillLoading 完成后，将内容设置到 MEditor ──
  // 不能在 handleStepClick 中直接调用 setInitialContent，因为编辑器在 loading 时未挂载
  useEffect(() => {
    if (!skillLoading && skillContent && editingStepIndex !== null) {
      // 延迟以确保 MEditor 已渲染
      const timer = setTimeout(() => {
        editorRef.current?.setInitialContent?.(skillContent);
      }, 100);
      return () => clearTimeout(timer);
    }
  }, [skillLoading, skillContent, editingStepIndex]);

  // 多源搜索
  const handleSearch = useCallback(async (tab: SkillTab, query: string) => {
    setSkillSearchLoading(true);
    try {
      let results: SkillItem[] = [];
      if (tab === 'builtin' || tab === 'all') {
        const builtin = await getBuiltinSkills();
        results = results.concat(builtin.map((s: any) => ({ ...s, source: 'builtin' as const })));
      }
      if (tab === 'installed' || tab === 'all') {
        try {
          const { skillList } = await import('@/infrastructure/api/tupai/skill');
          const installed = await skillList();
          results = results.concat(installed.map((s: any) => ({
            skill_id: s.skill_id || s.id || '', skill_name: s.skill_name || s.name || '',
            description: s.description || '', version: s.version || '', source: 'installed' as const,
            category: s.category, tags: s.tags,
          })));
        } catch { /* skip */ }
      }
      if (tab === 'market' || tab === 'all') {
        try {
          const marketResults = await searchMultiMarket(query);
          results = results.concat(marketResults.map((m: any) => ({
            skill_id: m.id || m.skillId || '', skill_name: m.name || '',
            description: m.description || '', version: m.version || '1.0.0',
            source: 'market' as const, tags: m.tags,
          })));
        } catch { /* skip */ }
      }
      const q = query.toLowerCase();
      if (q) results = results.filter(s => s.skill_name.toLowerCase().includes(q) || s.description.toLowerCase().includes(q));
      setAllSearchSkills(results);
    } finally {
      setSkillSearchLoading(false);
    }
  }, []);

  useEffect(() => { handleSearch(skillSearchTab, skillSearchQuery); }, [skillSearchTab, skillSearchQuery, handleSearch]);

  const onSearchInput = useCallback((val: string) => {
    setSkillSearchQuery(val);
    const timer = setTimeout(() => handleSearch(skillSearchTab, val), 300);
    return () => clearTimeout(timer);
  }, [skillSearchTab, handleSearch]);

  const displaySkills = useMemo(() => {
    const q = skillSearchQuery.toLowerCase();
    if (!q && skillSearchTab !== 'all') return skillSearchTab === 'builtin' ? availableSkills : allSearchSkills;
    return allSearchSkills;
  }, [skillSearchTab, skillSearchQuery, availableSkills, allSearchSkills]);

  // ── 在会话栏打开流水线（新活动入口）──
  // 通过 sessionStorage + 自定义事件把流水线上下文传递给会话场景。
  const handleOpenInSession = useCallback((pipeline: PipelineDef) => {
    try {
      sessionStorage.setItem('tupai:session:pipelineId', pipeline.id);
      sessionStorage.setItem('tupai:session:pipelineName', pipeline.name);
      sessionStorage.setItem('tupai:session:pipelineSteps', JSON.stringify(pipeline.steps));
    } catch { /* ignore */ }
    openScene('session', pipeline.name);
    window.dispatchEvent(new CustomEvent('tupai:session:openPipeline', {
      detail: { pipelineId: pipeline.id, pipelineName: pipeline.name, steps: pipeline.steps },
    }));
  }, [openScene]);

  // ── 点击流水线 → 直接在会话栏打开（新活动入口），不再展开右侧详情面板 ──
  const handlePipelineClick = useCallback((pipeline: PipelineDef) => {
    handleOpenInSession(pipeline);
  }, [handleOpenInSession]);

  // ── MD 编辑器：加载指定步骤的技能 SKILL.md 到右侧编辑器 ──
  const handleEditStep = useCallback(async (pipeline: PipelineDef, stepIndex: number) => {
    const step = pipeline.steps[stepIndex];
    if (!step) return;
    setEditingStepIndex(stepIndex);
    setSkillLoading(true);
    setSkillContent('');
    setSkillTitle(step.skillName);
    setSkillDirty(false);

    try {
      const skill = await skillLoad(step.skillId);
      const content = skill?.content || '';
      setSkillContent(content);
      setSkillTitle(skill?.title || step.skillName);
      // setInitialContent 由 useEffect 在编辑器挂载后自动调用
    } catch (err) {
      log.warn('Failed to load skill content for editor', { skillId: step.skillId, error: err });
      setSkillContent(`# ${step.skillName}\n\n> 技能内容加载失败，请重试。\n\n技能ID: ${step.skillId}`);
    } finally {
      setSkillLoading(false);
    }
  }, []);

  // ── 打开流水线的 MD 编辑器（默认编辑第一个步骤）──
  const handleOpenEditor = useCallback((pipeline: PipelineDef) => {
    setSelectedId(pipeline.id);
    setSkillModalOpen(false);
    if (pipeline.steps.length === 0) {
      setEditingStepIndex(-1);
      setSkillTitle(pipeline.name);
      setSkillContent('');
      setSkillDirty(false);
      return;
    }
    void handleEditStep(pipeline, 0);
  }, [handleEditStep]);

  // 保存技能内容
  const handleSaveSkill = useCallback(async () => {
    if (editingStepIndex === null || editingStepIndex < 0 || !selectedPipeline) return;
    const step = selectedPipeline.steps[editingStepIndex];
    if (!step || !skillContent) return;

    try {
      await skillSave({
        skill_id: step.skillId,
        title: skillTitle,
        description: '',
        content: skillContent,
        version: '',
        category: '',
      });
      setSkillDirty(false);
      editorRef.current?.markSaved?.();
      log.info('Skill content saved', { skillId: step.skillId });
    } catch (err) {
      log.warn('Failed to save skill content', { skillId: step.skillId, error: err });
    }
  }, [editingStepIndex, selectedPipeline, skillContent, skillTitle]);

  // 创建流水线
  const handleCreate = useCallback(async () => {
    if (!newName.trim()) return;
    try {
      const def = await pipelineApi.pipelineCreate({ name: newName.trim(), scene: 'work', steps: [], rounds: 1 });
      setPipelineList(prev => [...prev, def]);
      setSelectedId(def.id);
      setNewName('');
      setModalOpen(false);
    } catch (err) {
      log.warn('create failed', err);
    }
  }, [newName]);

  // 从模板创建流水线
  const handleCreateFromTemplate = useCallback(async (template: pipelineApi.PipelineTemplate) => {
    try {
      const def = await pipelineApi.pipelineCreate({
        name: template.name,
        scene: 'work',
        steps: template.steps,
        rounds: template.rounds,
      });
      setPipelineList(prev => [...prev, def]);
      setSelectedId(def.id);
    } catch (err) {
      log.warn('create from template failed', err);
    }
  }, []);

  // 删除
  const handleDelete = useCallback(async (id: string) => {
    try {
      await pipelineApi.pipelineDelete(id);
      setPipelineList(prev => prev.filter(p => p.id !== id));
      if (selectedId === id) setSelectedId(null);
    } catch (err) {
      log.warn('delete failed', err);
    }
  }, [selectedId]);

  // ── Task 4: 执行过程各节点自动做参数传递 ──
  // 每步执行后自动提取输出字段，自动注入到后续步骤的参数解析中。
  // 不再需要手动设置 $steps[N].field 占位符——系统会自动尝试匹配。
  const handleExecutePipeline = useCallback(async (pipeline: PipelineDef) => {
    if (pipeline.status !== 'running') return;
    const execs = getStepExecs(pipeline.id, pipeline.steps.length);
    // 收集每一步的输出，供占位符解析
    const stepOutputs: Record<string, any>[] = [];
    // 收集每一步的输出字段名，供自动映射
    const stepOutputFields: { stepIndex: number; fields: string[] }[] = [];

    for (let round = pipeline.currentRound; round <= pipeline.rounds; round++) {
      for (let i = 0; i < pipeline.steps.length; i++) {
        const step = pipeline.steps[i];
        execs[i] = { ...execs[i], status: 'running' };
        forceExecUpdate();
        const startTime = Date.now();
        try {
          // 运行时解析 $steps[i].field 占位符（显式引用）
          let resolvedParams = await pipelineApi.pipelineResolveParams({ params: step.params, outputs: stepOutputs });

          // ── 自动参数传递：对于未被显式引用的参数，尝试自动匹配前序步骤输出 ──
          if (stepOutputFields.length > 0) {
            const autoResolved = { ...resolvedParams };
            for (const [paramName, paramValue] of Object.entries(resolvedParams)) {
              // 跳过已有显式引用（$steps 开头）、非字符串、已有非空值
              if (typeof paramValue === 'string' && paramValue.startsWith('$steps[')) continue;
              if (paramValue !== '' && paramValue !== null && paramValue !== undefined) continue;

              // 尝试自动映射
              const suggestion = autoSuggestParamMapping(paramName, undefined, stepOutputFields);
              if (suggestion) {
                // 用自动映射的占位符重新解析
                const autoParams = { ...autoResolved, [paramName]: suggestion };
                autoResolved[paramName] = (await pipelineApi.pipelineResolveParams({ params: autoParams, outputs: stepOutputs }))[paramName];
                log.info('auto-mapped param', { step: i, paramName, suggestion, value: autoResolved[paramName] });
              }
            }
            resolvedParams = autoResolved;
          }

          const result = await runBuiltinSkill(step.skillId, resolvedParams);
          const durationMs = Date.now() - startTime;

          // ── 自动提取输出字段，供后续步骤使用 ──
          const outputFields = extractOutputFields(result);
          stepOutputs[i] = result;
          stepOutputFields.push({ stepIndex: i, fields: outputFields });

          execs[i] = { stepIndex: i, status: 'success', result: JSON.stringify(result), outputFields };
          forceExecUpdate();
          await pipelineApi.pipelineRecordStep({
            pipelineId: pipeline.id, stepIndex: i,
            skillId: step.skillId, params: step.params,
            result: JSON.stringify(result), durationMs, status: 'succeeded',
          });
        } catch (err) {
          const durationMs = Date.now() - startTime;
          execs[i] = { stepIndex: i, status: 'failed', result: String(err) };
          forceExecUpdate();
          await pipelineApi.pipelineRecordStep({
            pipelineId: pipeline.id, stepIndex: i,
            skillId: step.skillId, params: step.params,
            result: String(err), durationMs, status: 'failed',
          });
        }
      }
      const updated = await pipelineApi.pipelineCompleteRound(pipeline.id);
      setPipelineList(prev => prev.map(p => p.id === pipeline.id ? updated : p));
    }
  }, []);

  // 启动 / 暂停 / 停止
  const handleStart = useCallback(async (id: string) => {
    try {
      const def = await pipelineApi.pipelineStart(id);
      setPipelineList(prev => prev.map(p => p.id === id ? def : p));
      stepExecMap.set(id, def.steps.map((_, i) => ({ stepIndex: i, status: 'pending' as StepExecStatus })));
      forceExecUpdate();
      // 异步执行流水线步骤（fire-and-forget）
      handleExecutePipeline(def);
    } catch (err) {
      log.warn('start failed', err);
    }
  }, [handleExecutePipeline]);

  const handlePause = useCallback(async (id: string) => {
    try {
      const def = await pipelineApi.pipelinePause(id);
      setPipelineList(prev => prev.map(p => p.id === id ? def : p));
    } catch (err) {
      log.warn('pause failed', err);
    }
  }, []);

  const handleStop = useCallback(async (id: string) => {
    try {
      const def = await pipelineApi.pipelineStop(id);
      setPipelineList(prev => prev.map(p => p.id === id ? def : p));
    } catch (err) {
      log.warn('stop failed', err);
    }
  }, []);

  // ── Task 5: 添加步骤时自动映射参数 ──
  // 选中技能后，根据前序步骤的已知输出字段，自动为参数填充 $steps[N].field 占位符。
  const handleAddStep = useCallback(async () => {
    if (!selectedSkill || !selectedId) return;
    const pipeline = pipelineList.find(p => p.id === selectedId);
    if (!pipeline) return;

    // ── 自动参数传递：根据已有步骤的技能定义自动推断输出字段 ──
    // 从前序步骤的技能 params 定义中提取输出字段名（作为启发式估计）
    const inferredOutputs: { stepIndex: number; fields: string[] }[] = [];
    for (let i = 0; i < pipeline.steps.length; i++) {
      const prevSkill = availableSkills.find(s => s.skill_id === pipeline.steps[i].skillId);
      if (prevSkill?.params) {
        // 技能的输出字段通常是其 params 中的 name
        const fields = prevSkill.params.map(p => p.name);
        inferredOutputs.push({ stepIndex: i, fields });
      }
    }

    // 对新步骤的每个参数尝试自动映射
    const finalParams = { ...stepParams };
    const newAutoMapped = new Set<string>();
    if (selectedSkill.params) {
      for (const field of selectedSkill.params) {
        // 只对空值或默认值的参数进行自动映射
        const currentVal = finalParams[field.name];
        const isEmpty = currentVal === '' || currentVal === 0 || currentVal === false || currentVal === undefined;
        if (isEmpty && inferredOutputs.length > 0) {
          const suggestion = autoSuggestParamMapping(field.name, field.description, inferredOutputs);
          if (suggestion) {
            finalParams[field.name] = suggestion;
            newAutoMapped.add(field.name);
            log.info('auto-mapped param on add', { field: field.name, suggestion });
          }
        }
      }
    }

    const newSteps: PipelineStepDef[] = [
      ...pipeline.steps,
      { skillId: selectedSkill.skill_id, skillName: selectedSkill.skill_name, params: finalParams, order: pipeline.steps.length },
    ];
    try {
      const def = await pipelineApi.pipelineUpdate({ id: selectedId, name: pipeline.name, steps: newSteps, rounds: pipeline.rounds });
      setPipelineList(prev => prev.map(p => p.id === selectedId ? def : p));
      setSelectedSkill(null);
      setStepParams({});
      setAutoMappedParams(new Set());
      setSkillModalOpen(false);
    } catch (err) {
      log.warn('addStep failed', err);
    }
  }, [selectedSkill, selectedId, pipelineList, stepParams, availableSkills]);

  // ── Task 5: 选中技能时自动映射参数 ──
  const handleSelectSkill = useCallback((skill: SkillItem) => {
    setSelectedSkill(skill);
    const defaults: Record<string, any> = {};
    if (skill.params) {
      for (const field of skill.params) {
        if (field.defaultValue !== undefined) defaults[field.name] = field.defaultValue;
        else if (field.type === 'number') defaults[field.name] = 0;
        else if (field.type === 'boolean') defaults[field.name] = false;
        else if (field.enum && field.enum.length > 0) defaults[field.name] = field.enum[0];
        else defaults[field.name] = '';
      }
    }

    // ── 自动参数映射：根据当前流水线已有步骤推断输出字段 ──
    if (selectedId) {
      const pipeline = pipelineList.find(p => p.id === selectedId);
      if (pipeline) {
        const inferredOutputs: { stepIndex: number; fields: string[] }[] = [];
        for (let i = 0; i < pipeline.steps.length; i++) {
          const prevSkill = availableSkills.find(s => s.skill_id === pipeline.steps[i].skillId);
          if (prevSkill?.params) {
            const fields = prevSkill.params.map(p => p.name);
            inferredOutputs.push({ stepIndex: i, fields });
          }
        }

        // 自动映射
        if (skill.params && inferredOutputs.length > 0) {
          const newAutoMapped = new Set<string>();
          for (const field of skill.params) {
            const currentVal = defaults[field.name];
            const isEmpty = currentVal === '' || currentVal === 0 || currentVal === false || currentVal === undefined;
            if (isEmpty) {
              const suggestion = autoSuggestParamMapping(field.name, field.description, inferredOutputs);
              if (suggestion) {
                defaults[field.name] = suggestion;
                newAutoMapped.add(field.name);
              }
            }
          }
          setAutoMappedParams(newAutoMapped);
        }
      }
    }

    setStepParams(defaults);
  }, [selectedId, pipelineList, availableSkills]);

  const sortedPipelines = useMemo(() => {
    const order: Record<string, number> = { running: 0, paused: 1, idle: 2, completed: 3, stopped: 4 };
    return [...pipelineList].sort((a, b) => (order[a.status] ?? 5) - (order[b.status] ?? 5));
  }, [pipelineList]);

  const tabs: { key: SkillTab; label: string; icon: React.ReactNode }[] = [
    { key: 'builtin', label: '内置技能', icon: <Box size={13} /> },
    { key: 'installed', label: '已安装', icon: <Database size={13} /> },
    { key: 'market', label: '技能市场', icon: <Globe size={13} /> },
    { key: 'all', label: '全部', icon: <Filter size={13} /> },
  ];

  return (
    <div className="pipelines-scene">
      {/* ── 标题栏 ── */}
      <div className="pipelines-scene__header">
        <div className="pipelines-scene__title-wrap">
          <IterationCw size={16} />
          <h2 className="pipelines-scene__title">流水线</h2>
          <span className="pipelines-scene__subtitle">节点间参数自动传递 · 点击流水线在会话栏打开</span>
        </div>
        <div className="pipelines-scene__header-actions">
          <button className="pipelines-scene__btn pipelines-scene__btn--ghost" onClick={loadPipelines} disabled={loading}>
            <RefreshCw size={13} className={loading ? 'pipelines-scene__spin' : ''} />
          </button>
          <button className="pipelines-scene__btn pipelines-scene__btn--primary" onClick={() => setModalOpen(true)}>
            <Plus size={13} /><span>新建流水线</span>
          </button>
        </div>
      </div>

      <div className="pipelines-scene__body">
        {/* ── 侧边栏 ── */}
        <div className="pipelines-scene__sidebar">
          <div className="pipelines-scene__list">
            {sortedPipelines.length === 0 && !loading && (
              <div className="pipelines-scene__empty">
                <span className="pipelines-scene__empty-title">暂无流水线</span>
                <span className="pipelines-scene__empty-hint">选择下方模板快速创建，或自定义新建</span>
                {templates.length > 0 && (
                  <div className="pipelines-scene__templates">
                    <div className="pipelines-scene__templates-label">内置模板</div>
                    {templates.map(tpl => (
                      <div key={tpl.id} className="pipelines-scene__template-card" onClick={() => handleCreateFromTemplate(tpl)}>
                        <div className="pipelines-scene__template-card-name">{tpl.name}</div>
                        <div className="pipelines-scene__template-card-desc">{tpl.description}</div>
                        <div className="pipelines-scene__template-card-steps">{tpl.steps.length} 步 · {tpl.rounds} 轮</div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
            {sortedPipelines.map(pipeline => {
              const isRunning = pipeline.status === 'running';
              const isPaused = pipeline.status === 'paused';
              return (
                <div key={pipeline.id}
                  className={`pipelines-scene__item${isRunning ? ' is-running' : ''}${isPaused ? ' is-paused' : ''}${selectedId === pipeline.id ? ' is-active' : ''}`}
                  onClick={() => handlePipelineClick(pipeline)}>
                  <div className="pipelines-scene__item-main">
                    <div className="pipelines-scene__item-top">
                      <span className="pipelines-scene__item-name" title={pipeline.name}>
                        <ListTodo size={12} />{pipeline.name}
                      </span>
                      <span className={`pipelines-scene__status pipelines-scene__status--${pipeline.status}`}>
                        {pipeline.status === 'running' ? <><Loader2 size={11} className="pipelines-scene__spin" />运行中</>
                          : pipeline.status === 'paused' ? '已暂停'
                          : pipeline.status === 'completed' ? '已完成'
                          : pipeline.status === 'stopped' ? '已停止'
                          : '待执行'}
                      </span>
                    </div>
                    <div className="pipelines-scene__item-meta">
                      <span className="pipelines-scene__chip pipelines-scene__chip--round">{pipeline.steps.length} 步</span>
                      <span className="pipelines-scene__chip">{pipeline.currentRound}/{pipeline.rounds} 轮</span>
                      {pipeline.steps.length > 1 && (
                        <span className="pipelines-scene__chip pipelines-scene__chip--ok" title="节点间参数自动传递">
                          <Zap size={9} />自动传递
                        </span>
                      )}
                    </div>
                  </div>
                  <div className="pipelines-scene__item-actions">
                    <button className="pipelines-scene__icon-btn" title="编辑步骤技能 SKILL.md" onClick={(e) => { e.stopPropagation(); handleOpenEditor(pipeline); }}>
                      <FileText size={13} />
                    </button>
                    {(pipeline.status === 'idle' || pipeline.status === 'completed' || pipeline.status === 'stopped') && (
                      <button className="pipelines-scene__icon-btn pipelines-scene__icon-btn--play" onClick={(e) => { e.stopPropagation(); handleStart(pipeline.id); }}>
                        <Play size={13} />
                      </button>
                    )}
                    {pipeline.status === 'running' && (
                      <button className="pipelines-scene__icon-btn" onClick={(e) => { e.stopPropagation(); handlePause(pipeline.id); }}>
                        <Pause size={13} />
                      </button>
                    )}
                    {pipeline.status === 'paused' && (
                      <>
                        <button className="pipelines-scene__icon-btn pipelines-scene__icon-btn--play" onClick={(e) => { e.stopPropagation(); handleStart(pipeline.id); }}>
                          <Play size={13} />
                        </button>
                        <button className="pipelines-scene__icon-btn pipelines-scene__icon-btn--stop" onClick={(e) => { e.stopPropagation(); handleStop(pipeline.id); }}>
                          <Square size={13} />
                        </button>
                      </>
                    )}
                    {pipeline.status === 'running' && (
                      <button className="pipelines-scene__icon-btn pipelines-scene__icon-btn--stop" onClick={(e) => { e.stopPropagation(); handleStop(pipeline.id); }}>
                        <Square size={13} />
                      </button>
                    )}
                    <button className="pipelines-scene__icon-btn pipelines-scene__icon-btn--danger" onClick={(e) => { e.stopPropagation(); handleDelete(pipeline.id); }}>
                      <Trash2 size={13} />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* ── 右侧 MD 编辑器面板 ── */}
          {editingStepIndex !== null && selectedPipeline && (
          <div className="pipelines-scene__editor">
            <div className="pipelines-scene__editor-header">
              <div className="pipelines-scene__editor-title-wrap">
                <FileText size={14} />
                <span className="pipelines-scene__editor-title">{skillTitle}</span>
                {skillDirty && <span className="pipelines-scene__editor-dirty">•</span>}
              </div>
              <div className="pipelines-scene__editor-actions">
                <button
                  className="pipelines-scene__btn pipelines-scene__btn--primary pipelines-scene__btn--sm"
                  onClick={handleSaveSkill}
                  disabled={!skillDirty || skillLoading}
                  title="保存技能内容"
                >
                  <Save size={12} />保存
                </button>
                <button
                  className="pipelines-scene__icon-btn"
                  onClick={() => { setEditingStepIndex(null); setSkillContent(''); setSkillTitle(''); }}
                  title="关闭编辑器"
                >
                  <X size={13} />
                </button>
              </div>
            </div>
            {selectedPipeline.steps.length > 0 && (
              <div className="pipelines-scene__editor-steps">
                {selectedPipeline.steps.map((step, i) => (
                  <button
                    key={i}
                    type="button"
                    className={`pipelines-scene__editor-step-tab${editingStepIndex === i ? ' is-active' : ''}`}
                    onClick={() => handleEditStep(selectedPipeline, i)}
                    title={`${step.skillName} (${step.skillId})`}
                  >
                    {i + 1}. {step.skillName}
                  </button>
                ))}
              </div>
            )}
            <div className="pipelines-scene__editor-body">
              {skillLoading ? (
                <div className="pipelines-scene__editor-loading">
                  <Loader2 size={20} className="pipelines-scene__spin" />
                  <span>加载技能内容...</span>
                </div>
              ) : (
                <MEditor
                  ref={editorRef}
                  value={skillContent}
                  onChange={(val: string) => { setSkillContent(val); }}
                  onSave={() => handleSaveSkill()}
                  onDirtyChange={(dirty: boolean) => setSkillDirty(dirty)}
                  mode="ir"
                  theme={isLight ? 'light' : 'dark'}
                  height="100%"
                  width="100%"
                  placeholder="技能 SKILL.md 内容将在此显示，点击步骤可编辑..."
                />
              )}
            </div>
          </div>
        )}
      </div>

      {/* ── 新建 Modal ── */}
      <Modal isOpen={modalOpen} onClose={() => setModalOpen(false)} title="新建流水线" size="small">
        <div className="pipelines-scene__modal-form">
          <Input label="流水线名称" placeholder="如：热点内容视频生成" value={newName}
            onChange={(e: any) => setNewName(e.target.value)} onKeyDown={(e: any) => { if (e.key === 'Enter') handleCreate(); }} />
          <div className="pipelines-scene__modal-actions">
            <button className="pipelines-scene__btn pipelines-scene__btn--ghost" onClick={() => setModalOpen(false)}>取消</button>
            <button className="pipelines-scene__btn pipelines-scene__btn--primary" onClick={handleCreate} disabled={!newName.trim()}>创建</button>
          </div>
        </div>
      </Modal>

      {/* ── 技能选择 Modal ── */}
      <Modal isOpen={skillModalOpen} onClose={() => { setSkillModalOpen(false); setSelectedSkill(null); }} title="选择技能步骤" size="large">
        <div className="pipelines-scene__modal-form">
          <div className="pipelines-scene__search-bar">
            <Search size={14} className="pipelines-scene__search-icon" />
            <input className="pipelines-scene__search-input" placeholder="搜索技能名称或描述..." value={skillSearchQuery}
              onChange={(e) => onSearchInput(e.target.value)} autoFocus />
            {skillSearchLoading && <Loader2 size={14} className="pipelines-scene__spin" />}
          </div>
          <div className="pipelines-scene__tabs">
            {tabs.map(tab => (
              <button key={tab.key} className={`pipelines-scene__tab${skillSearchTab === tab.key ? ' is-active' : ''}`}
                onClick={() => setSkillSearchTab(tab.key)}>
                {tab.icon}<span>{tab.label}</span>
              </button>
            ))}
          </div>
          <div className="pipelines-scene__skill-grid">
            {displaySkills.length === 0 && <div className="pipelines-scene__skill-empty">未找到匹配的技能</div>}
            {displaySkills.map(skill => (
              <div key={skill.skill_id} className={`pipelines-scene__skill-card${selectedSkill?.skill_id === skill.skill_id ? ' is-selected' : ''}`}
                onClick={() => handleSelectSkill(skill)}>
                <div className="pipelines-scene__skill-card-name">{skill.skill_name}</div>
                <div className="pipelines-scene__skill-card-source"><Tag color="gray" size="small">{skill.source}</Tag></div>
                <div className="pipelines-scene__skill-card-desc">{skill.description}</div>
              </div>
            ))}
          </div>
          {selectedSkill && selectedSkill.params && selectedSkill.params.length > 0 && (
            <div className="pipelines-scene__param-section">
              <h4>{selectedSkill.skill_name} - 参数</h4>
              <div className="pipelines-scene__param-hint">
                <Zap size={10} /> 参数自动传递：空参数会自动匹配前序步骤输出。
                也可手动引用：<code>$steps[N].field</code>（N 从 0 开始）
              </div>
              {selectedSkill.params.map(field => (
                <div key={field.name} className="pipelines-scene__param-row">
                  <label>
                    {field.description || field.name}
                    {autoMappedParams.has(field.name) && (
                      <span className="pipelines-scene__param-auto-badge" title="已自动映射前序步骤输出">
                        <Zap size={9} />自动
                      </span>
                    )}
                  </label>
                  {field.enum ? (
                    <select value={stepParams[field.name] || ''} onChange={e => setStepParams(p => ({ ...p, [field.name]: e.target.value }))}>
                      {field.enum.map(o => <option key={o} value={o}>{o}</option>)}
                    </select>
                  ) : field.type === 'boolean' ? (
                    <input type="checkbox" checked={!!stepParams[field.name]} onChange={e => setStepParams(p => ({ ...p, [field.name]: e.target.checked }))} />
                  ) : (
                    <input value={stepParams[field.name] ?? ''} onChange={e => setStepParams(p => ({ ...p, [field.name]: e.target.value }))} placeholder={field.description} />
                  )}
                </div>
              ))}
            </div>
          )}
          <div className="pipelines-scene__modal-actions">
            <button className="pipelines-scene__btn pipelines-scene__btn--ghost" onClick={() => { setSkillModalOpen(false); setSelectedSkill(null); }}>取消</button>
            <button className="pipelines-scene__btn pipelines-scene__btn--primary" onClick={handleAddStep} disabled={!selectedSkill}>添加到流水线</button>
          </div>
        </div>
      </Modal>

      {/* 执行历史抽屉 */}
      {historyPipelineId && (
        <div className="pipelines-scene__drawer-mask" onClick={() => setHistoryPipelineId(null)} />
      )}
    </div>
  );
};

export default PipelinesScene;

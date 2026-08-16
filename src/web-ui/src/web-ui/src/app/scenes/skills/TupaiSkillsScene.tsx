/**
 * TupaiSkillsScene — 技能瀑布流浏览/搜索页。
 *
 * 默认视图：
 *   - 从 navSkillsStore 加载缓存的技能列表（5分钟TTL）
 *   - 按 category 字段分组展示，每组独立瀑布流网格
 *   - 顶部 tag 筛选栏，点击 category 芯片筛选对应分组
 *   - 无限滚动：每个分组内客户端分页（visibleCount）
 *
 * 搜索模式（≤5字）：
 *   - searchAllSkills 多源搜索 + IntersectionObserver 远程分页
 *
 * 对话模式（>5字）：
 *   - 跳转 session 场景
 *
 * 点击技能卡片 → sessionStorage 传递 skillId → openScene('session')
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, Loader2, Paperclip, Image, X } from 'lucide-react';
import { createImageContextFromFile } from '@/flow_chat/utils/imageUtils';
import { useSceneManager } from '@/app/hooks/useSceneManager';
import { useStarRatingStore } from '@/flow_chat/store/starRatingStore';
import { searchAllSkills, skillExecute, runBuiltinSkill, reportSkillFailure, reportSkillSuccess, fetchSkillParams } from '@/infrastructure/api/tupai';
import { useNavSkillsStore, writeLastSearch } from '@/app/components/NavPanel/sections/skills/navSkillsStore';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import { createLogger } from '@/shared/utils/logger';
import { notificationService } from '@/shared/notification-system';
import { SkillParamModal } from '@/app/components/SkillParamModal/SkillParamModal';
import type { SkillMeta } from '@/infrastructure/api/tupai';
import type { ParamField } from '@/infrastructure/api/tupai/skill';
import { imSendSkillParams, imSubscribe } from '@/infrastructure/api/tupai/im';
import type { SkillParamFieldInfo } from '@/infrastructure/api/tupai/im';
import { isTauriRuntime } from '@/infrastructure/runtime';
import './TupaiSkillsScene.scss';

const log = createLogger('TupaiSkillsScene');

const LLM_THRESHOLD = 5;
const PAGE_SIZE = 20;
const GROUP_PAGE_SIZE = 12; // 每个 category 分组初始显示数量

const GRID_ICONS = ['⚡', '🤖', '🖥️', '📋', '🔍', '🔄', '🎯', '📊', '🧩'];

// ── 平台技能判定 ──────────────────────────
// tags 中包含 'platform' 的技能视为平台技能，优先级最高，
// 在默认视图和搜索结果中均独立置顶展示。
function isPlatformSkill(skill: any): boolean {
  const tags: unknown = skill?.tags;
  if (!Array.isArray(tags)) return false;
  return tags.some((t) => typeof t === 'string' && t.toLowerCase() === 'platform');
}

// ── 按 category 分组 ──────────────────────────
function groupByCategory(skills: SkillMeta[]): Map<string, SkillMeta[]> {
  const map = new Map<string, SkillMeta[]>();
  for (const s of skills) {
    const cat = (s.category || '').trim() || '未分类';
    const arr = map.get(cat);
    if (arr) {
      arr.push(s);
    } else {
      map.set(cat, [s]);
    }
  }
  return map;
}

const TupaiSkillsScene: React.FC = () => {
  const { openScene } = useSceneManager();
  const { t } = useI18n('common');

  // ── 搜索状态 ──
  const [query, setQuery] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [searchSkills, setSearchSkills] = useState<any[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [totalCount, setTotalCount] = useState(0);

  // ── 缓存技能 + 分组 ──
  // displaySkills = 本地已安装 + 服务器市场技能(normal+automation) + 上次搜索结果
  const cachedSkills = useNavSkillsStore(s => s.displaySkills);
  const loadCachedSkills = useNavSkillsStore(s => s.loadSkills);
  const cachedLoading = useNavSkillsStore(s => s.loading);

  // ── tag 筛选 ──
  const [activeTag, setActiveTag] = useState<string | null>(null);

  // ── 客户端分页（按分组） ──
  const [groupVisibleCount, setGroupVisibleCount] = useState<Record<string, number>>({});

  const loadMoreTriggerRef = useRef<HTMLDivElement | null>(null);
  const observerRef = useRef<IntersectionObserver | null>(null);
  const remoteOffsetRef = useRef(0);
  const reqIdRef = useRef(0);
  const loadingMoreRef = useRef(false);
  const searchSkillsRef = useRef(searchSkills);

  const mode = query.trim().length > LLM_THRESHOLD ? 'chat' : 'skills';
  const submittedMode = submittedQuery.trim().length > LLM_THRESHOLD ? 'chat' : 'skills';
  const isSearchMode = !!submittedQuery.trim();

  useEffect(() => { searchSkillsRef.current = searchSkills; }, [searchSkills]);

  // ── 缓存技能：平台技能置顶 + 其余按 category 分组 ──
  // 平台技能独立抽出，作为"平台技能"分组渲染在所有 category 分组之前；
  // 其余技能按 category 分组（不含 platform 技能，避免重复展示）。
  const { platformSkills, categoryGroups } = useMemo(() => {
    const platform: SkillMeta[] = [];
    const rest: SkillMeta[] = [];
    for (const s of cachedSkills) {
      if (isPlatformSkill(s)) {
        platform.push(s);
      } else {
        rest.push(s);
      }
    }
    return { platformSkills: platform, categoryGroups: groupByCategory(rest) };
  }, [cachedSkills]);
  const allCategories = useMemo(() => Array.from(categoryGroups.keys()).sort(), [categoryGroups]);

  // ── 筛选后的分组 ──
  // 注意：平台技能始终置顶展示，不受 tag 筛选影响。
  const filteredGroups = useMemo(() => {
    if (!activeTag) return categoryGroups;
    const filtered = new Map<string, SkillMeta[]>();
    const arr = categoryGroups.get(activeTag);
    if (arr) filtered.set(activeTag, arr);
    return filtered;
  }, [categoryGroups, activeTag]);

  // ── 筛选后的技能总数（含平台技能） ──
  const filteredTotalCount = useMemo(() => {
    let count = platformSkills.length;
    for (const arr of filteredGroups.values()) count += arr.length;
    return count;
  }, [filteredGroups, platformSkills.length]);

  // ── 获取分组的 visibleCount ──
  const getGroupVisible = useCallback((cat: string) => {
    return groupVisibleCount[cat] ?? GROUP_PAGE_SIZE;
  }, [groupVisibleCount]);

  // ── Refs for IntersectionObserver (avoid stale closures) ──
  const filteredGroupsRef = useRef(filteredGroups);
  const isSearchModeRef = useRef(isSearchMode);
  useEffect(() => { filteredGroupsRef.current = filteredGroups; }, [filteredGroups]);
  useEffect(() => { isSearchModeRef.current = isSearchMode; }, [isSearchMode]);

  // ── 加载更多（搜索模式远程分页） ──
  const loadMore = useCallback(async () => {
    if (loadingMoreRef.current) return;
    if (!hasMore || submittedMode !== 'skills' || !submittedQuery.trim()) return;
    loadingMoreRef.current = true;
    setLoadingMore(true);
    const startReqId = reqIdRef.current;
    try {
      const nextOffset = remoteOffsetRef.current;
      const result = await searchAllSkills(submittedQuery, {
        offset: nextOffset,
        limit: PAGE_SIZE,
        skipLocal: true,
      });
      if (reqIdRef.current !== startReqId) return;
      const newSkills = Array.isArray(result?.results) ? result.results : [];
      const existingIds = new Set<string>();
      for (const s of searchSkillsRef.current) {
        const id = s.skill_id || s.id;
        if (id) existingIds.add(id);
      }
      const uniqueNew = newSkills.filter((s: any) => {
        const id = s.skill_id || s.id;
        if (!id) return true;
        if (existingIds.has(id)) return false;
        existingIds.add(id);
        return true;
      });
      if (uniqueNew.length === 0) {
        setHasMore(false);
        return;
      }
      setSearchSkills(prev => [...prev, ...uniqueNew]);
      remoteOffsetRef.current += PAGE_SIZE;
      setHasMore(uniqueNew.length >= PAGE_SIZE);
    } catch (e) {
      log.error('loadMore failed', e);
      setHasMore(false);
    } finally {
      loadingMoreRef.current = false;
      setLoadingMore(false);
      if (observerRef.current && loadMoreTriggerRef.current) {
        observerRef.current.unobserve(loadMoreTriggerRef.current);
        observerRef.current.observe(loadMoreTriggerRef.current);
      }
    }
  }, [hasMore, submittedMode, submittedQuery]);

  // ── IntersectionObserver ──
  const showClientLoadMore = !isSearchMode && filteredTotalCount > 0 &&
    Array.from(filteredGroups.entries()).some(([cat, arr]) => arr.length > getGroupVisible(cat));
  const showRemoteLoadMore = isSearchMode && submittedMode === 'skills' && hasMore && searchSkills.length > 0;
  const showLoadMoreTrigger = showRemoteLoadMore || showClientLoadMore;

  useEffect(() => {
    if (!showRemoteLoadMore && !showClientLoadMore) {
      if (observerRef.current) {
        observerRef.current.disconnect();
        observerRef.current = null;
      }
      return;
    }
    if (!loadMoreTriggerRef.current) return;
    if (observerRef.current) observerRef.current.disconnect();
    observerRef.current = new IntersectionObserver(
      (entries) => {
        const entry = entries[0];
        if (entry.isIntersecting && !loadingMoreRef.current) {
          const isSearch = isSearchModeRef.current;
          const groups = filteredGroupsRef.current;
          if (isSearch) {
            loadMore();
          } else {
            // 为所有未完全显示的分组增加 visibleCount
            setGroupVisibleCount(prev => {
              const next = { ...prev };
              for (const [cat, arr] of groups.entries()) {
                const cur = next[cat] ?? GROUP_PAGE_SIZE;
                if (cur < arr.length) {
                  next[cat] = cur + GROUP_PAGE_SIZE;
                }
              }
              return next;
            });
          }
        }
      },
      { threshold: 0.1, rootMargin: '200px' }
    );
    observerRef.current.observe(loadMoreTriggerRef.current);
    return () => {
      if (observerRef.current) {
        observerRef.current.disconnect();
        observerRef.current = null;
      }
    };
  }, [showRemoteLoadMore, showClientLoadMore, loadMore]);

  // ── 搜索提交 ──
  useEffect(() => {
    let cancelled = false;
    reqIdRef.current += 1;
    setError('');
    if (submittedQuery.trim()) {
      setLoading(true);
      setSearchSkills([]);
    }
    setHasMore(false);
    setTotalCount(0);
    remoteOffsetRef.current = 0;

    (async () => {
      try {
        if (submittedQuery.trim() && submittedMode === 'skills') {
          const result = await searchAllSkills(submittedQuery, {
            offset: 0,
            limit: PAGE_SIZE,
          });
          if (cancelled) return;
          const list = Array.isArray(result?.results) ? result.results : [];
          const total = result?.total || 0;
          setSearchSkills(list);
          setHasMore(list.length < total);
          setTotalCount(total);
          remoteOffsetRef.current = PAGE_SIZE;
          // 持久化上次搜索结果，供默认视图展示
          writeLastSearch(submittedQuery, list);
        }
      } catch (e: any) {
        if (cancelled) return;
        log.error('Failed to search skills', e);
        const msg = e?.message || String(e);
        if (msg.includes('Cannot read properties of undefined') && msg.includes('invoke')) {
          setError(t('skillsScene.tauriNotReady'));
        } else {
          // 直接展示后端返回的具体错误消息，而不是笼统的"搜索失败"
          // 后端 mcp_call_v2 错误格式: {"code":"...","message":"MCP skill.search returned HTTP 401: ..."}
          // 取 message 字段的前 200 字符，让用户看到具体原因
          const displayMsg = msg.length > 200 ? msg.slice(0, 200) + '...' : msg;
          setError(displayMsg);
          setSearchSkills([]);
        }
        setHasMore(false);
      }
      if (!cancelled) setLoading(false);
    })();

    return () => { cancelled = true; };
  }, [submittedQuery, submittedMode, t]);

  // ── 确保缓存已加载 ──
  useEffect(() => {
    void loadCachedSkills();
  }, [loadCachedSkills]);

  // ── 发送（搜索或对话） ──
  const handleSend = useCallback(() => {
    const q = query.trim();
    if (!q) return;
    if (mode === 'chat') {
      try {
        sessionStorage.setItem('tupai:session:chatQuery', q);
      } catch { /* ignore */ }
      window.dispatchEvent(new CustomEvent('tupai:session:chatQuery', { detail: { query: q } }));
      openScene('session');
      return;
    }
    setSubmittedQuery(query);
  }, [query, mode, openScene]);

  // ── 自动化技能检测 ──
  // 通过 category 或 tags 判定是否为"自动化技能"（点击后立即通过 CDP/UIA 等执行）。
  // 命中条件：category 包含 "automation" / "自动化"，或 tags 包含 "automation"。
  const isAutomationSkill = useCallback((skill: any): boolean => {
    const cat = String(skill.category || '').toLowerCase();
    if (cat === 'automation' || cat === '自动化' || cat.includes('automation')) return true;
    const tags: unknown = skill.tags;
    if (Array.isArray(tags)) {
      return tags.some((t) => typeof t === 'string' && t.toLowerCase() === 'automation');
    }
    return false;
  }, []);

  // ── 自动化技能：立即执行（CDP / UIA / OCR / VLM 由后端 router 路由） ──
  // builtin- 前缀技能走 runBuiltinSkill（页面内 eval + handler 调用），
  // 其他技能走 skillExecute（后端 automation engine，返回 request_id 后异步执行）。
  // 后端 router 不跨域降级：UIA 域不走 CDP，CDP 域不走 UIA，
  // 节点识别不足时逐节点调用 OCR/VLM 补充。
  const [executingSkillId, setExecutingSkillId] = useState<string | null>(null);

  // ── 技能参数确认弹窗 ──
  const [pendingSkill, setPendingSkill] = useState<{
    skillId: string;
    skillName: string;
    skillDescription: string;
    params: ParamField[];
  } | null>(null);
  const pendingSkillRef = useRef(pendingSkill);
  useEffect(() => { pendingSkillRef.current = pendingSkill; }, [pendingSkill]);
  const pendingImCorrelationRef = useRef<string | null>(null);
  const handleSkillConfirmRef = useRef<(values: Record<string, unknown>) => void>(() => {});
  const handleSkillSkipRef = useRef<() => void>(() => {});

  // 当 pendingSkill 变化时，同步发送到绑定的 IM 渠道
  useEffect(() => {
    if (!pendingSkill || !isTauriRuntime()) return;
    let bridgedIds: string[] = [];
    try { const raw = localStorage.getItem('tupai:im:lastSelectedChannel'); if (raw) bridgedIds = raw.split(',').filter(Boolean); } catch { /* ignore */ }
    let targetsMap: Record<string, string> = {};
    try { const raw = localStorage.getItem('tupai:im:targetsMap'); if (raw) targetsMap = JSON.parse(raw); } catch { /* ignore */ }
    if (bridgedIds.length === 0) return;

    const correlationId = `skill-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    pendingImCorrelationRef.current = correlationId;
    const fields: SkillParamFieldInfo[] = pendingSkill.params.map((p) => ({
      name: p.name, type: p.type, description: p.description, enumValues: p.enum, currentValue: p.defaultValue,
    }));
    for (const cid of bridgedIds) {
      const target = targetsMap[cid] || '';
      if (!target) continue;
      imSendSkillParams(cid, target, pendingSkill.skillName, pendingSkill.skillDescription, fields, correlationId)
        .catch((e) => log.warn('IM skill params send failed', { channelId: cid, error: e }));
    }
  }, [pendingSkill]);

  // IM 事件订阅：检测技能确认/跳过回复
  useEffect(() => {
    if (!isTauriRuntime()) return;
    const unsub = imSubscribe((event) => {
      if (event.kind !== 'message') return;
      const text: string = event.payload?.text || event.payload?.content || '';
      if (!text) return;
      const codeMatch = text.match(/确认码:\s*(\S+)/);
      if (!codeMatch) return;
      if (codeMatch[1].trim() !== pendingImCorrelationRef.current) return;
      if (!pendingSkillRef.current) return;
      if (text.includes('确认')) handleSkillConfirmRef.current({});
      else if (text.includes('跳过')) handleSkillSkipRef.current();
    });
    return () => { unsub(); };
  }, []);

  // ── 技能交互式提示（cap.ui.prompt → skill-prompt 事件） ──
const [skillPrompt, setSkillPrompt] = useState<{ id: number; message: string; context: string } | null>(null);
  const [promptInput, setPromptInput] = useState('');
  const [promptFiles, setPromptFiles] = useState<Array<{ id: string; name: string; dataUrl: string; mimeType: string; fileSize: number }>>([]);
  const [promptImages, setPromptImages] = useState<Array<{ id: string; name: string; dataUrl: string; mimeType: string; fileSize: number; width?: number; height?: number }>>([]);

  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail && detail.id != null) {
        setSkillPrompt({ id: detail.id, message: detail.message || '', context: detail.options?.context || '' });
        setPromptInput('');
      }
    };
    window.addEventListener('skill-prompt', handler);
    return () => window.removeEventListener('skill-prompt', handler);
  }, []);

  const submitPrompt = useCallback(() => {
    if (!skillPrompt) return;
    window.dispatchEvent(new CustomEvent('skill-prompt-response', {
      detail: {
        id: skillPrompt.id,
        response: promptInput,
        files: promptFiles,
        images: promptImages,
      },
    }));
    setSkillPrompt(null);
    setPromptInput('');
    setPromptFiles([]);
    setPromptImages([]);
  }, [skillPrompt, promptInput, promptFiles, promptImages]);

  const cancelPrompt = useCallback(() => {
    if (!skillPrompt) return;
    window.dispatchEvent(new CustomEvent('skill-prompt-cancel', {
      detail: { id: skillPrompt.id },
    }));
    setSkillPrompt(null);
    setPromptInput('');
    setPromptFiles([]);
    setPromptImages([]);
  }, [skillPrompt]);

  const readFileAsDataURL = (file: File): Promise<string> => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as string);
      reader.onerror = () => reject(new Error('File reading failed'));
      reader.readAsDataURL(file);
    });
  };

  const handlePromptFileSelect = useCallback(() => {
    const input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.onchange = async (e) => {
      const files = (e.target as HTMLInputElement).files;
      if (!files || files.length === 0) return;
      for (const file of Array.from(files)) {
        try {
          const dataUrl = await readFileAsDataURL(file);
          setPromptFiles(prev => [...prev, {
            id: `file-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
            name: file.name,
            dataUrl,
            mimeType: file.type || 'application/octet-stream',
            fileSize: file.size,
          }]);
        } catch (error) {
          log.error('Failed to read file', { fileName: file.name, error });
        }
      }
    };
    input.click();
  }, []);

  const handlePromptImageSelect = useCallback(() => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.multiple = true;
    input.onchange = async (e) => {
      const files = (e.target as HTMLInputElement).files;
      if (!files || files.length === 0) return;
      for (const file of Array.from(files)) {
        try {
          const imageContext = await createImageContextFromFile(file);
          setPromptImages(prev => [...prev, {
            id: imageContext.id,
            name: imageContext.imageName,
            dataUrl: imageContext.dataUrl || '',
            mimeType: imageContext.mimeType,
            fileSize: imageContext.fileSize,
            width: imageContext.width,
            height: imageContext.height,
          }]);
        } catch (error) {
          log.error('Failed to process image', { fileName: file.name, error });
          notificationService.error(`${file.name}: ${error instanceof Error ? error.message : '处理失败'}`, { duration: 3000 });
        }
      }
    };
    input.click();
  }, []);

  const removePromptFile = useCallback((id: string) => {
    setPromptFiles(prev => prev.filter(f => f.id !== id));
  }, []);

  const removePromptImage = useCallback((id: string) => {
    setPromptImages(prev => prev.filter(f => f.id !== id));
  }, []);

  const executeAutomationSkill = useCallback(async (skill: any) => {
    const skillId = String(skill.skill_id || skill.id || skill.skill_name || skill.name || skill.title || '');
    const skillName = String(skill.skill_name || skill.name || skill.title || skillId);
    if (!skillId) {
      notificationService.error('技能 ID 为空，无法执行');
      return;
    }
    setExecutingSkillId(skillId);
    notificationService.info(`正在执行：${skillName}`, { duration: 3000 });
    const startedAt = performance.now();

    // 使用用户填写的参数（如果有）
    const userParams = skill._userParams as Record<string, unknown> | undefined;
    const execParams = userParams ?? { action: 'execute' };

    try {
      if (skillId.startsWith('builtin-')) {
        // 内置 JS 技能：runBuiltinSkill 在页面 eval 执行 handler
        const result = await runBuiltinSkill(skillId, execParams as Record<string, any>);
        const output = typeof result === 'string' ? result : (result?.output || result?.message || JSON.stringify(result));
        notificationService.success(`${skillName} 执行完成：${output?.slice(0, 200) || 'ok'}`, { duration: 5000 });
        // 静默上报执行成功
        reportSkillSuccess(skillId, result, performance.now() - startedAt);
        // 请求用户星级评分
        useStarRatingStore.getState().promptRating(skillId, skillName);
      } else {
        // 市场技能：skillExecute 返回 request_id，后端 automation engine 异步执行
        const requestId = await skillExecute(skillId, execParams);
        log.info('automation skill execution started', { skillId, requestId });
        notificationService.success(`${skillName} 已启动执行（request_id=${requestId}）`, { duration: 4000 });
        // 市场技能异步执行，「请求已受理」视为成功，与现有通知语义一致
        reportSkillSuccess(skillId, 'request_id=' + requestId, performance.now() - startedAt);
        // 请求用户星级评分
        useStarRatingStore.getState().promptRating(skillId, skillName);
      }
    } catch (err: any) {
      const msg = err?.message || String(err);
      log.error('automation skill execution failed', { skillId, error: err });
      notificationService.error(`${skillName} 执行失败：${msg}`, { duration: 6000 });
      // 静默上报执行失败
      reportSkillFailure(skillId, msg);
    } finally {
      setExecutingSkillId(null);
    }
  }, []);

  // ── 技能参数确认弹窗回调 ──
  const proceedWithSkill = useCallback((skill: any, params: Record<string, unknown> | null) => {
    if (isAutomationSkill(skill)) {
      // 自动化技能：传入用户填写的参数
      const finalParams = params ?? { action: 'execute' };
      // runBuiltinSkill 和 skillExecute 内部处理 action 映射
      const skillWithParams = { ...skill, _userParams: finalParams };
      void executeAutomationSkill(skillWithParams);
      return;
    }
    // 聊天技能：导航到会话场景
    const skillId = skill.skill_id || skill.id || skill.skill_name || skill.name || skill.title || '';
    const skillName = skill.skill_name || skill.name || skill.title || skillId;
    try {
      sessionStorage.setItem('tupai:session:skillId', skillId);
      sessionStorage.setItem('tupai:session:skillName', skillName);
    } catch { /* ignore */ }
    window.dispatchEvent(new CustomEvent('tupai:session:openSkill', {
      detail: { skillId, skillName },
    }));
    openScene('session');
  }, [openScene, isAutomationSkill, executeAutomationSkill]);

  const handleSkillConfirm = useCallback((values: Record<string, unknown>) => {
    if (!pendingSkill) return;
    const skillId = pendingSkill.skillId;
    const skill = { skill_id: skillId, id: skillId, skill_name: pendingSkill.skillName, description: pendingSkill.skillDescription };
    setPendingSkill(null);
    pendingImCorrelationRef.current = null;
    proceedWithSkill(skill, values);
  }, [pendingSkill, proceedWithSkill]);
  useEffect(() => { handleSkillConfirmRef.current = handleSkillConfirm; }, [handleSkillConfirm]);

  const handleSkillSkip = useCallback(() => {
    if (!pendingSkill) return;
    const skillId = pendingSkill.skillId;
    const skill = { skill_id: skillId, id: skillId, skill_name: pendingSkill.skillName, description: pendingSkill.skillDescription };
    setPendingSkill(null);
    pendingImCorrelationRef.current = null;
    proceedWithSkill(skill, null);
  }, [pendingSkill, proceedWithSkill]);
  useEffect(() => { handleSkillSkipRef.current = handleSkillSkip; }, [handleSkillSkip]);

  const handleSkillModalClose = useCallback(() => {
    setPendingSkill(null);
  }, []);

  // ── 点击技能卡片 ──
  // 仅当技能有参数定义时才弹确认窗，否则直接执行/导航（减少不必要的交互）
  const handleSkillClick = useCallback(async (skill: any) => {
    const skillId = String(skill.skill_id || skill.id || skill.skill_name || skill.name || skill.title || '');
    const skillName = String(skill.skill_name || skill.name || skill.title || skillId);
    const skillDescription = String(skill.description || '');
    if (!skillId) return;

    // 异步获取参数 schema（仅 builtin 技能有）
    let params: ParamField[] = [];
    try {
      const fetched = await fetchSkillParams(skillId);
      if (fetched) params = fetched;
    } catch { /* ignore */ }

    if (params.length > 0) {
      // 有参数定义 → 弹窗让用户填写
      setPendingSkill({ skillId, skillName, skillDescription, params });
    } else {
      // 无参数 → 直接执行/导航
      proceedWithSkill(skill, null);
    }
  }, [proceedWithSkill]);

  // ── 点击 tag 筛选 ──
  const handleTagClick = useCallback((tag: string | null) => {
    setActiveTag(prev => prev === tag ? null : tag);
    setGroupVisibleCount({});
  }, []);

  // ── 无 ID 技能的稳定 key ──
  const skillKeyMapRef = useRef(new WeakMap());
  const getSkillKey = useCallback((skill: any) => {
    if (skill.skill_id) return skill.skill_id;
    if (skill.id) return skill.id;
    const map = skillKeyMapRef.current as WeakMap<any, string>;
    if (map.has(skill)) return map.get(skill)!;
    const uid = (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : `tmp-${Math.random().toString(36).slice(2)}`;
    map.set(skill, uid);
    return uid;
  }, []);

  // ── 渲染技能卡片 ──
  const renderSkillCard = useCallback((skill: any, index: number) => {
    const skillId = String(skill.skill_id || skill.id || '');
    const isExecuting = executingSkillId === skillId;
    const isAutomation = isAutomationSkill(skill);
    const tooltip = isAutomation
      ? `${t('skillsScene.clickToOpen')} (自动化技能 · 立即执行)`
      : t('skillsScene.clickToOpen');
    return (
      <div
        key={getSkillKey(skill)}
        className={`tupai-skills__skill-card${isAutomation ? ' is-automation' : ''}${isExecuting ? ' is-executing' : ''}`}
        onClick={() => handleSkillClick(skill)}
        title={tooltip}
      >
        <span className="tupai-skills__skill-icon">
          {isExecuting ? <Loader2 size={14} className="tupai-skills__skill-spinner" /> : GRID_ICONS[index % GRID_ICONS.length]}
        </span>
        <span className="tupai-skills__skill-name">
          {skill.skill_name || skill.name || skill.title || skill.skill_id || skill.id}
        </span>
        {skill.description && (
          <span className="tupai-skills__skill-desc">{skill.description}</span>
        )}
        <span className="tupai-skills__skill-meta">
          {skill.version && (
            <span className="tupai-skills__skill-version">v{skill.version}</span>
          )}
          {isAutomation && (
            <span className="tupai-skills__skill-badge-auto">AUTO</span>
          )}
        </span>
      </div>
    );
  }, [handleSkillClick, t, getSkillKey, executingSkillId, isAutomationSkill]);

  // ── UI 状态 ──
  const showSendBtn = query.trim().length > 0;

  // ── 搜索模式结果：平台技能置顶，其余作为常规结果 ──
  const { platformSearchResults, regularSearchResults } = useMemo(() => {
    if (!isSearchMode) return { platformSearchResults: [], regularSearchResults: [] };
    const platform: any[] = [];
    const regular: any[] = [];
    for (const s of searchSkills) {
      if (isPlatformSkill(s)) {
        platform.push(s);
      } else {
        regular.push(s);
      }
    }
    return { platformSearchResults: platform, regularSearchResults: regular };
  }, [isSearchMode, searchSkills]);
  const searchResults = isSearchMode ? searchSkills : [];

  return (
    <div className="tupai-skills">
      {/* 搜索栏 */}
      <div className="tupai-skills__search-bar">
        <input
          className="tupai-skills__search-input"
          type="text"
          placeholder={t('skillsScene.searchPlaceholder')}
          value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && showSendBtn) handleSend(); }}
          autoFocus
        />
        {showSendBtn && (
          <button
            className="tupai-skills__send-btn"
            type="button"
            onClick={handleSend}
            disabled={loading}
          >
            {loading ? t('skillsScene.searching') : (mode === 'chat' ? t('skillsScene.send') : t('skillsScene.search'))}
          </button>
        )}
      </div>

      {/* 对话模式提示 */}
      {mode === 'chat' && (
        <div className="tupai-skills__mode-hint">
          <span>{t('skillsScene.chatMode')}</span>
        </div>
      )}

      {/* tag 筛选栏（仅默认视图，有缓存技能时显示） */}
      {!isSearchMode && allCategories.length > 0 && (
        <div className="tupai-skills__filter-tags">
          <span className="tupai-skills__filter-tags-prefix">分类</span>
          <div className="tupai-skills__filter-tags-list">
            <button
              type="button"
              className={`tupai-skills__filter-tag-chip${activeTag === null ? ' is-active' : ''}`}
              onClick={() => handleTagClick(null)}
            >
              全部
            </button>
            {allCategories.map(cat => (
              <button
                key={cat}
                type="button"
                className={`tupai-skills__filter-tag-chip${activeTag === cat ? ' is-active' : ''}`}
                onClick={() => handleTagClick(cat)}
              >
                {cat}
                <span className="tupai-skills__filter-tag-count">
                  {categoryGroups.get(cat)?.length ?? 0}
                </span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* 主体 */}
      <main className="tupai-skills__main">
        {/* ── 搜索模式 ── */}
        {isSearchMode ? (
          loading && searchResults.length === 0 ? (
            <div className="tupai-skills__status">
              <div className="tupai-skills__spinner" />
              <span>{t('skillsScene.searching')}</span>
            </div>
          ) : error && searchResults.length === 0 ? (
            <div className="tupai-skills__status">
              <AlertTriangle size={24} />
              <span>{error}</span>
            </div>
          ) : searchResults.length === 0 ? (
            <div className="tupai-skills__status">
              <span>{t('skillsScene.noSkills')}</span>
            </div>
          ) : (
            <div className="tupai-skills__waterfall">
              {totalCount > 0 && (
                <div className="tupai-skills__waterfall-header">
                  {t('skillsScene.totalCount', { count: totalCount })}
                </div>
              )}
              {/* 平台技能置顶（搜索结果中也优先展示） */}
              {platformSearchResults.length > 0 && (
                <div className="tupai-skills__category-section">
                  <div className="tupai-skills__category-header">
                    <span className="tupai-skills__category-name">平台技能</span>
                    <span className="tupai-skills__category-count">{platformSearchResults.length}</span>
                  </div>
                  <div className="tupai-skills__waterfall-grid">
                    {platformSearchResults.map((skill, i) => renderSkillCard(skill, i))}
                  </div>
                </div>
              )}
              {/* 常规搜索结果 */}
              {regularSearchResults.length > 0 && (
                <div className="tupai-skills__category-section">
                  {platformSearchResults.length > 0 && (
                    <div className="tupai-skills__category-header">
                      <span className="tupai-skills__category-name">搜索结果</span>
                      <span className="tupai-skills__category-count">{regularSearchResults.length}</span>
                    </div>
                  )}
                  <div className="tupai-skills__waterfall-grid">
                    {regularSearchResults.map((skill, i) => renderSkillCard(skill, i))}
                  </div>
                </div>
              )}
              {showLoadMoreTrigger && (
                <div ref={loadMoreTriggerRef} className="tupai-skills__loadmore">
                  {loadingMore ? (
                    <div className="tupai-skills__spinner-small" />
                  ) : (
                    <span>{t('skillsScene.loadMore')}</span>
                  )}
                </div>
              )}
              {!hasMore && searchResults.length > 0 && totalCount > 0 && (
                <div className="tupai-skills__waterfall-end">
                  {t('skillsScene.allLoaded', { count: totalCount })}
                </div>
              )}
            </div>
          )
        ) : (
          /* ── 默认视图：平台技能置顶 + 其余按 category 分组 ── */
          <>
            {cachedLoading && cachedSkills.length === 0 ? (
              <div className="tupai-skills__status">
                <div className="tupai-skills__spinner" />
                <span>{t('skillsScene.loadingSkills')}</span>
              </div>
            ) : cachedSkills.length === 0 ? (
              <div className="tupai-skills__status">
                <span>{t('skillsScene.noSkills')}</span>
              </div>
            ) : (
              <div className="tupai-skills__waterfall">
                {filteredTotalCount > 0 && (
                  <div className="tupai-skills__waterfall-header">
                    共 {filteredTotalCount} 个技能
                  </div>
                )}
                {/* 平台技能置顶（始终展示，不受 tag 筛选影响） */}
                {platformSkills.length > 0 && (
                  <div className="tupai-skills__category-section">
                    <div className="tupai-skills__category-header">
                      <span className="tupai-skills__category-name">平台技能</span>
                      <span className="tupai-skills__category-count">{platformSkills.length}</span>
                    </div>
                    <div className="tupai-skills__waterfall-grid">
                      {platformSkills.map((skill, i) => renderSkillCard(skill, i))}
                    </div>
                  </div>
                )}
                {Array.from(filteredGroups.entries()).map(([cat, catSkills]) => {
                  const visible = getGroupVisible(cat);
                  const visibleSkills = catSkills.slice(0, visible);
                  const hasMoreInGroup = catSkills.length > visible;
                  return (
                    <div key={cat} className="tupai-skills__category-section">
                      <div className="tupai-skills__category-header">
                        <span className="tupai-skills__category-name">{cat}</span>
                        <span className="tupai-skills__category-count">{catSkills.length}</span>
                      </div>
                      <div className="tupai-skills__waterfall-grid">
                        {visibleSkills.map((skill, i) => renderSkillCard(skill, i))}
                      </div>
                      {hasMoreInGroup && (
                        <div className="tupai-skills__category-more">
                          还有 {catSkills.length - visible} 个技能...
                        </div>
                      )}
                    </div>
                  );
                })}
                {showLoadMoreTrigger && (
                  <div ref={loadMoreTriggerRef} className="tupai-skills__loadmore">
                    <div className="tupai-skills__spinner-small" />
                  </div>
                )}
                {!showClientLoadMore && cachedSkills.length > 0 && (
                  <div className="tupai-skills__waterfall-end">
                    已加载全部 {cachedSkills.length} 个技能
                  </div>
                )}
              </div>
            )}
          </>
        )}
      </main>

      {/* ── 技能参数确认弹窗 ── */}
      <SkillParamModal
        isOpen={!!pendingSkill}
        skillName={pendingSkill?.skillName ?? ''}
        skillDescription={pendingSkill?.skillDescription ?? ''}
        skillContent=""
        params={pendingSkill?.params ?? []}
        onConfirm={handleSkillConfirm}
        onSkip={handleSkillSkip}
        onClose={handleSkillModalClose}
      />

      {/* ── 技能交互式提示对话框 ── */}
      {skillPrompt && (
        <div className="tupai-skills__prompt-overlay" onClick={cancelPrompt}>
          <div className="tupai-skills__prompt-dialog" onClick={e => e.stopPropagation()}>
            <div className="tupai-skills__prompt-title">{skillPrompt.message}</div>
            {skillPrompt.context && (
              <div className="tupai-skills__prompt-context">{skillPrompt.context}</div>
            )}
            <input
              className="tupai-skills__prompt-input"
              type="text"
              value={promptInput}
              onChange={e => setPromptInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') submitPrompt(); if (e.key === 'Escape') cancelPrompt(); }}
              autoFocus
              placeholder="请输入..."
            />
            <div className="tupai-skills__prompt-upload-row">
              <button
                type="button"
                className="tupai-skills__prompt-upload-btn"
                onClick={handlePromptFileSelect}
                title="添加文件"
              >
                <Paperclip size={16} />
              </button>
              <button
                type="button"
                className="tupai-skills__prompt-upload-btn"
                onClick={handlePromptImageSelect}
                title="添加图片"
              >
                <Image size={16} />
              </button>
            </div>
            {promptFiles.length > 0 && (
              <div className="tupai-skills__prompt-file-previews">
                {promptFiles.map(f => (
                  <div key={f.id} className="tupai-skills__prompt-file-preview">
                    <span className="tupai-skills__prompt-file-name">{f.name}</span>
                    <span className="tupai-skills__prompt-file-size">({(f.fileSize / 1024).toFixed(1)} KB)</span>
                    <button
                      type="button"
                      className="tupai-skills__prompt-file-remove"
                      onClick={() => removePromptFile(f.id)}
                    >
                      <X size={12} />
                    </button>
                  </div>
                ))}
              </div>
            )}
            {promptImages.length > 0 && (
              <div className="tupai-skills__prompt-image-previews">
                {promptImages.map(img => (
                  <div key={img.id} className="tupai-skills__prompt-image-preview">
                    <img
                      src={img.dataUrl}
                      alt={img.name}
                      className="tupai-skills__prompt-image-thumbnail"
                    />
                    <button
                      type="button"
                      className="tupai-skills__prompt-image-remove"
                      onClick={() => removePromptImage(img.id)}
                    >
                      <X size={12} />
                    </button>
                  </div>
                ))}
              </div>
            )}
            <div className="tupai-skills__prompt-actions">
              <button type="button" className="tupai-skills__prompt-cancel" onClick={cancelPrompt}>取消</button>
              <button type="button" className="tupai-skills__prompt-submit" onClick={submitPrompt} disabled={!promptInput.trim() && promptFiles.length === 0 && promptImages.length === 0}>确认</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default TupaiSkillsScene;

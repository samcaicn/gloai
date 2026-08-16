import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { open } from '@tauri-apps/plugin-dialog';
import { invoke } from '@tauri-apps/api/core';
import { useTranslation } from 'react-i18next';
import { configAPI } from '@/infrastructure/api';
import type { SkillInfo, SkillLevel, SkillValidationResult } from '@/infrastructure/config/types';
import { useWorkspaceManagerSync } from '@/infrastructure/hooks/useWorkspaceManagerSync';
import { useNotification } from '@/shared/notification-system';
import { createLogger } from '@/shared/utils/logger';
import { addImportedSkill, getImportedSkills, removeImportedSkill, type ImportedSkill } from './importedSkills';
import type { InstalledFilter } from '../skillsSceneStore';

const log = createLogger('SkillsScene:useInstalledSkills');

/**
 * 从 SKILL.md 文本解析 name / description 用于前端展示。
 * 与后端 uirpa_import_skill 的 parse_skill_frontmatter 保持同样的最小规则：
 * 优先读 YAML frontmatter，name 缺省时回退到首个 `# 标题`。
 */
export function parseSkillNameDesc(md: string): { name: string; description: string } {
  const trimmed = md.trimStart();
  let name = '';
  let description = '';
  if (trimmed.startsWith('---')) {
    const rest = trimmed.slice(3);
    const end = rest.indexOf('\n---');
    if (end >= 0) {
      for (const line of rest.slice(0, end).split('\n')) {
        const idx = line.indexOf(':');
        if (idx < 0) {
          continue;
        }
        const key = line.slice(0, idx).trim().toLowerCase();
        const val = line.slice(idx + 1).trim().replace(/^["']|["']$/g, '');
        if (key === 'name') {
          name = val;
        } else if (key === 'description') {
          description = val;
        }
      }
    }
  }
  if (!name) {
    for (const line of trimmed.split('\n')) {
      const l = line.trim();
      if (l.startsWith('# ')) {
        name = l.slice(2).trim();
        break;
      }
    }
  }
  if (!name) {
    name = 'imported-skill';
  }
  return { name, description };
}

interface UseInstalledSkillsOptions {
  searchQuery: string;
  activeFilter: InstalledFilter;
}

export function useInstalledSkills({ searchQuery, activeFilter }: UseInstalledSkillsOptions) {
  const { t } = useTranslation('scenes/skills');
  const notification = useNotification();
  const { workspacePath, hasWorkspace, isRemoteWorkspace } = useWorkspaceManagerSync();

  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [formLevel, setFormLevel] = useState<SkillLevel>('user');
  const [formPath, setFormPath] = useState('');
  const [validationResult, setValidationResult] = useState<SkillValidationResult | null>(null);
  const [isValidating, setIsValidating] = useState(false);
  const [isAdding, setIsAdding] = useState(false);
  const loadRequestIdRef = useRef(0);

  const loadSkills = useCallback(async (forceRefresh?: boolean) => {
    const requestId = ++loadRequestIdRef.current;

    try {
      setLoading(true);
      setError(null);
      const list = await configAPI.getSkillConfigs({
        forceRefresh,
        workspacePath: workspacePath || undefined,
      });
      if (requestId !== loadRequestIdRef.current) {
        return;
      }
      // 导入的技能固定展示在列表首位、其后顺位（localStorage 已按最新优先排序）。
      const imported = getImportedSkills();
      setSkills(imported.length > 0 ? [...imported, ...list] : list);
    } catch (err) {
      if (requestId !== loadRequestIdRef.current) {
        return;
      }
      log.error('Failed to load skills', err);
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      if (requestId === loadRequestIdRef.current) {
        setLoading(false);
      }
    }
  }, [workspacePath]);

  useEffect(() => {
    loadSkills();
  }, [loadSkills]);

  const validatePath = useCallback(async (path: string) => {
    if (!path.trim()) {
      setValidationResult(null);
      return;
    }
    try {
      setIsValidating(true);
      const result = await configAPI.validateSkillPath(path);
      setValidationResult(result);
    } catch (err) {
      setValidationResult({
        valid: false,
        error: err instanceof Error ? err.message : String(err),
      });
    } finally {
      setIsValidating(false);
    }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      validatePath(formPath);
    }, 300);
    return () => window.clearTimeout(timer);
  }, [formPath, validatePath]);

  const handleBrowse = useCallback(async () => {
    try {
      const selected = await open({
        directory: true,
        multiple: false,
        title: t('form.path.label'),
      });
      if (selected) {
        setFormPath(selected as string);
      }
    } catch (err) {
      log.error('Failed to open file dialog', err);
    }
  }, [t]);

  const resetForm = useCallback(() => {
    setFormPath('');
    setFormLevel('user');
    setValidationResult(null);
  }, []);

  const handleAdd = useCallback(async () => {
    if (!validationResult?.valid || !formPath.trim()) {
      notification.warning(t('messages.invalidPath'));
      return false;
    }
    if (formLevel === 'project' && !hasWorkspace) {
      notification.warning(t('messages.noWorkspace'));
      return false;
    }
    if (formLevel === 'project' && isRemoteWorkspace) {
      notification.warning('Remote workspaces do not support project skill installation yet.');
      return false;
    }
    try {
      setIsAdding(true);
      await configAPI.addSkill({
        sourcePath: formPath,
        level: formLevel,
        workspacePath: workspacePath || undefined,
      });
      notification.success(t('messages.addSuccess', { name: validationResult.name }));
      resetForm();
      await loadSkills(true);
      return true;
    } catch (err) {
      notification.error(
        t('messages.addFailed', {
          error: err instanceof Error ? err.message : String(err),
        }),
      );
      return false;
    } finally {
      setIsAdding(false);
    }
  }, [formLevel, formPath, hasWorkspace, isRemoteWorkspace, loadSkills, notification, resetForm, t, validationResult, workspacePath]);

  /**
   * 导入一个 SKILL.md：选文件 → 读内容 → 后端落盘并回传元数据 → 前端写入
   * 导入列表（固定首位）、刷新。仅此导入功能，不涉及 UIRPA 的其他命令。
   */
  const handleImport = useCallback(async (rawMarkdown: string): Promise<boolean> => {
    const { name, description } = parseSkillNameDesc(rawMarkdown);
    try {
      const meta = await invoke<{ skillId: string }>('uirpa_import_skill', {
        skillMd: rawMarkdown,
      });
      const imported: ImportedSkill = {
        key: `imported-${meta.skillId}`,
        name,
        description,
        path: '',
        level: 'user',
        sourceSlot: 'imported',
        dirName: meta.skillId,
        isBuiltin: false,
        addedAt: new Date().toISOString(),
      };
      addImportedSkill(imported);
      notification.success(t('messages.importSuccess', { name }));
      await loadSkills(true);
      return true;
    } catch (err) {
      notification.error(
        t('messages.importFailed', {
          error: err instanceof Error ? err.message : String(err),
        }),
      );
      return false;
    }
  }, [loadSkills, notification, t]);

  const handleDelete = useCallback(async (skill: SkillInfo) => {
    try {
      if (skill.sourceSlot === 'imported') {
        // 导入的技能：本地删除（磁盘目录 + localStorage 快照）。
        // 后端 uirpa_delete_skill 删除 app_data/skills/<id>/ 目录，
        // 与 uirpa_import_skill 的落盘位置对应，纯本地、可幂等。
        await invoke('uirpa_delete_skill', {
          skillId: skill.dirName,
        });
        removeImportedSkill(skill.key);
        notification.success(t('messages.deleteSuccess', { name: skill.name }));
        await loadSkills(true);
        return true;
      }
      // 下载 / 用户安装的技能：走真实后端命令做本地卸载
      // （uninstall_skill 执行 `hermes skills uninstall <name>`，仅移除本机文件）。
      await invoke('uninstall_skill', { name: skill.name });
      notification.success(t('messages.deleteSuccess', { name: skill.name }));
      await loadSkills(true);
      return true;
    } catch (err) {
      notification.error(
        t('messages.deleteFailed', {
          error: err instanceof Error ? err.message : String(err),
        }),
      );
      return false;
    }
  }, [loadSkills, notification, t]);

  const normalizedQuery = searchQuery.trim().toLowerCase();

  const filteredSkills = useMemo(() => {
    return skills.filter((skill) => {
      let matchesFilter = true;
      if (activeFilter === 'user') {
        matchesFilter = skill.level === 'user' && !skill.isBuiltin;
      } else if (activeFilter === 'project') {
        matchesFilter = skill.level === 'project' && !skill.isBuiltin;
      } else if (activeFilter === 'builtin') {
        matchesFilter = skill.isBuiltin;
      } else if (activeFilter === 'suite') {
        matchesFilter = skill.isBuiltin;
      }

      const matchesQuery = !normalizedQuery || [
        skill.name,
        skill.description,
        skill.path,
      ].some((field) => field?.toLowerCase().includes(normalizedQuery));
      return matchesFilter && matchesQuery;
    });
  }, [activeFilter, normalizedQuery, skills]);

  const counts = useMemo(() => ({
    all: skills.length,
    builtin: skills.filter((skill) => skill.isBuiltin).length,
    user: skills.filter((skill) => skill.level === 'user' && !skill.isBuiltin).length,
    project: skills.filter((skill) => skill.level === 'project' && !skill.isBuiltin).length,
    suite: skills.filter((skill) => skill.isBuiltin).length,
  }), [skills]);

  return {
    skills,
    filteredSkills,
    counts,
    loading,
    error,
    loadSkills,
    handleDelete,
    formLevel,
    setFormLevel,
    formPath,
    setFormPath,
    validationResult,
    isValidating,
    isAdding,
    handleBrowse,
    handleAdd,
    handleImport,
    resetForm,
    workspacePath,
    hasWorkspace,
    isRemoteWorkspace,
  };
}

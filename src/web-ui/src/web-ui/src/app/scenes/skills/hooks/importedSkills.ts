// Copyright (c) 2026 AIMarketing
//
// 导入技能的有序存储（localStorage）。
//
// 用户在「技能页」通过导入模态框导入的技能，按「最新优先」顺序保存在这里，
// 渲染时固定展示在技能列表首位、其后依次顺位。后端 uirpa_import_skill 已把
// SKILL.md 落盘到 app_data/skills/<id>/SKILL.md，这里只保存用于前端展示的
// SkillInfo 快照 + 导入时间，避免重复解析。

import type { SkillInfo } from '@/infrastructure/config/types';

const STORAGE_KEY = 'tupai.importedSkills.v1';
const MAX_IMPORTED = 100;

export type ImportedSkill = SkillInfo & { addedAt: string };

function safeRead(): ImportedSkill[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return [];
    }
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed as ImportedSkill[];
  } catch {
    return [];
  }
}

function safeWrite(list: ImportedSkill[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list));
  } catch {
    // 存储不可用时静默忽略（导入功能仍可用，只是重启后不保留顺序）
  }
}

/** 固定首位、其后顺位；按 skillId 去重（已存在的移到最前）。 */
export function addImportedSkill(skill: ImportedSkill): ImportedSkill[] {
  const rest = safeRead().filter((s) => s.key !== skill.key);
  const next = [skill, ...rest].slice(0, MAX_IMPORTED);
  safeWrite(next);
  return next;
}

/** 读取当前导入列表（最新优先）。 */
export function getImportedSkills(): ImportedSkill[] {
  return safeRead();
}

/** 本地删除一条导入技能（按 key 移除）。返回剩余列表。 */
export function removeImportedSkill(key: string): ImportedSkill[] {
  const next = safeRead().filter((s) => s.key !== key);
  safeWrite(next);
  return next;
}

/** 本地删除一条导入技能（按 skillId 移除）。返回剩余列表。 */
export function removeImportedSkillById(skillId: string): ImportedSkill[] {
  const next = safeRead().filter((s) => s.dirName !== skillId);
  safeWrite(next);
  return next;
}

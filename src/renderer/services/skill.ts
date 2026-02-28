import { Skill } from '../types/skill';
import { tauriApi } from './tauriApi';

type EmailConnectivityCheck = {
  code: 'imap_connection' | 'smtp_connection';
  level: 'pass' | 'fail';
  message: string;
  durationMs: number;
};

type EmailConnectivityTestResult = {
  testedAt: number;
  verdict: 'pass' | 'fail';
  checks: EmailConnectivityCheck[];
};

class SkillService {
  private skills: Skill[] = [];
  private initialized = false;

  async init(): Promise<void> {
    if (this.initialized) return;
    await this.loadSkills();
    this.initialized = true;
  }

  async loadSkills(): Promise<Skill[]> {
    try {
      const rawSkills = await tauriApi.skills.list();
      this.skills = rawSkills.map((skill: any) => ({
        id: skill.id,
        name: skill.name,
        description: skill.description || '',
        enabled: skill.enabled,
        isOfficial: skill.is_official !== undefined ? skill.is_official : true,
        isBuiltIn: skill.is_built_in !== undefined ? skill.is_built_in : true,
        updatedAt: skill.updated_at || Date.now(),
        prompt: skill.prompt || '',
        skillPath: skill.skill_path || skill.path || '',
      }));
      return this.skills;
    } catch (error) {
      console.error('Failed to load skills:', error);
      this.skills = [];
      return this.skills;
    }
  }

  async setSkillEnabled(id: string, enabled: boolean): Promise<Skill[]> {
    try {
      await tauriApi.skills.setEnabled(id, enabled);
      return await this.loadSkills();
    } catch (error) {
      console.error('Failed to update skill:', error);
      throw error;
    }
  }

  async deleteSkill(id: string): Promise<{ success: boolean; skills?: Skill[]; error?: string }> {
    try {
      await tauriApi.skills.delete(id);
      const skills = await this.loadSkills();
      return { success: true, skills };
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to delete skill';
      console.error('Failed to delete skill:', error);
      return { success: false, error: message };
    }
  }

  async downloadSkill(_source: string): Promise<{ success: boolean; skills?: Skill[]; error?: string }> {
    try {
      console.warn('downloadSkill not implemented yet for Tauri');
      return { success: false, error: 'Not implemented' };
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to download skill';
      console.error('Failed to download skill:', error);
      return { success: false, error: message };
    }
  }

  async getSkillsRoot(): Promise<string | null> {
    try {
      return await tauriApi.skills.getRoot();
    } catch (error) {
      console.error('Failed to get skills root:', error);
      return null;
    }
  }

  onSkillsChanged(_callback: () => void): () => void {
    console.warn('onSkillsChanged not implemented yet for Tauri');
    return () => {};
  }

  getSkills(): Skill[] {
    return this.skills;
  }

  getEnabledSkills(): Skill[] {
    return this.skills.filter(s => s.enabled);
  }

  getSkillById(id: string): Skill | undefined {
    return this.skills.find(s => s.id === id);
  }

  async getSkillConfig(skillId: string): Promise<Record<string, string>> {
    try {
      const config = await tauriApi.store.get(`skill_config_${skillId}`);
      return config || {};
    } catch (error) {
      console.error('Failed to get skill config:', error);
      return {};
    }
  }

  async setSkillConfig(skillId: string, config: Record<string, string>): Promise<boolean> {
    try {
      await tauriApi.store.set(`skill_config_${skillId}`, config);
      return true;
    } catch (error) {
      console.error('Failed to set skill config:', error);
      return false;
    }
  }

  async testEmailConnectivity(
    _skillId: string,
    _config: Record<string, string>
  ): Promise<EmailConnectivityTestResult | null> {
    try {
      console.warn('testEmailConnectivity not implemented yet for Tauri');
      return null;
    } catch (error) {
      console.error('Failed to test email connectivity:', error);
      return null;
    }
  }

  async getAutoRoutingPrompt(): Promise<string | null> {
    try {
      console.warn('getAutoRoutingPrompt not implemented yet for Tauri');
      return null;
    } catch (error) {
      console.error('Failed to get auto-routing prompt:', error);
      return null;
    }
  }
}

export const skillService = new SkillService();

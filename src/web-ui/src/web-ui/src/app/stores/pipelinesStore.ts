import { create } from 'zustand';
import { createLogger } from '@/shared/utils/logger';

export interface ParamField {
  name: string;
  type: 'string' | 'number' | 'boolean';
  description?: string;
  enum?: string[];
  required?: boolean;
  defaultValue?: unknown;
}

export interface SkillItem {
  skill_id: string;
  skill_name: string;
  description: string;
  version: string;
  source: 'builtin' | 'installed';
  category?: string;
  tags?: string[];
  params?: ParamField[];
}

const log = createLogger('pipelinesStore');

export interface PipelineStep {
  skillId: string;
  skillName: string;
  params: Record<string, any>;
  order: number;
}

export type PipelineStatus = 'idle' | 'running' | 'paused' | 'completed' | 'stopped';

export interface StepExecution {
  stepIndex: number;
  status: 'pending' | 'running' | 'success' | 'failed';
  result?: string;
  startedAt?: number;
  completedAt?: number;
}

export interface Pipeline {
  id: string;
  name: string;
  steps: PipelineStep[];
  createdAt: number;
  updatedAt: number;
  rounds: number;
  currentRound: number;
  status: PipelineStatus;
  executions: StepExecution[];
}

interface PipelinesState {
  pipelines: Pipeline[];
  selectedPipelineId: string | null;
  isNewPipelineModalOpen: boolean;
  isSkillSelectModalOpen: boolean;
  availableSkills: SkillItem[];
  editingPipeline: Pipeline | null;
  loadPipelines: () => void;
  savePipelines: () => void;
  selectPipeline: (id: string | null) => void;
  createPipeline: (name: string) => void;
  deletePipeline: (id: string) => void;
  addStepToPipeline: (pipelineId: string, skill: SkillItem, params: Record<string, any>) => void;
  removeStep: (pipelineId: string, stepIndex: number) => void;
  updateStepParams: (pipelineId: string, stepIndex: number, params: Record<string, any>) => void;
  setAvailableSkills: (skills: SkillItem[]) => void;
  setNewPipelineModalOpen: (open: boolean) => void;
  setSkillSelectModalOpen: (open: boolean) => void;
  startPipeline: (id: string) => void;
  pausePipeline: (id: string) => void;
  stopPipeline: (id: string) => void;
  completeRound: (id: string) => void;
  updateStepExecution: (pipelineId: string, stepIndex: number, update: Partial<StepExecution>) => void;
}

const STORAGE_KEY = 'tupai:pipelines';

function generateId(): string {
  return 'pl_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 8);
}

function loadFromStorage(): Pipeline[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    return JSON.parse(raw) as Pipeline[];
  } catch (err) {
    log.warn('Failed to load pipelines from storage', err);
    return [];
  }
}

export const usePipelinesStore = create<PipelinesState>((set, get) => ({
  pipelines: [],
  selectedPipelineId: null,
  isNewPipelineModalOpen: false,
  isSkillSelectModalOpen: false,
  availableSkills: [],
  editingPipeline: null,

  loadPipelines: () => {
    const pipelines = loadFromStorage();
    set({ pipelines });
  },

  savePipelines: () => {
    const { pipelines } = get();
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(pipelines));
    } catch (err) {
      log.warn('Failed to save pipelines', err);
    }
  },

  selectPipeline: (id) => set({ selectedPipelineId: id }),

  createPipeline: (name) => {
    const pipeline: Pipeline = {
      id: generateId(),
      name,
      steps: [],
      createdAt: Date.now(),
      updatedAt: Date.now(),
      rounds: 1,
      currentRound: 0,
      status: 'idle',
      executions: [],
    };
    set((state) => ({ pipelines: [...state.pipelines, pipeline], editingPipeline: pipeline }));
    get().savePipelines();
  },

  deletePipeline: (id) => {
    set((state) => ({
      pipelines: state.pipelines.filter((p) => p.id !== id),
      selectedPipelineId: state.selectedPipelineId === id ? null : state.selectedPipelineId,
    }));
    get().savePipelines();
  },

  addStepToPipeline: (pipelineId, skill, params) => {
    set((state) => ({
      pipelines: state.pipelines.map((p) => {
        if (p.id !== pipelineId) return p;
        return {
          ...p,
          steps: [...p.steps, { skillId: skill.skill_id, skillName: skill.skill_name, params, order: p.steps.length }],
          updatedAt: Date.now(),
        };
      }),
    }));
    get().savePipelines();
  },

  removeStep: (pipelineId, stepIndex) => {
    set((state) => ({
      pipelines: state.pipelines.map((p) => {
        if (p.id !== pipelineId) return p;
        return {
          ...p,
          steps: p.steps.filter((_, i) => i !== stepIndex).map((s, i) => ({ ...s, order: i })),
          updatedAt: Date.now(),
        };
      }),
    }));
    get().savePipelines();
  },

  updateStepParams: (pipelineId, stepIndex, params) => {
    set((state) => ({
      pipelines: state.pipelines.map((p) => {
        if (p.id !== pipelineId) return p;
        return {
          ...p,
          steps: p.steps.map((s, i) => (i === stepIndex ? { ...s, params } : s)),
          updatedAt: Date.now(),
        };
      }),
    }));
    get().savePipelines();
  },

  setAvailableSkills: (skills) => set({ availableSkills: skills }),
  setNewPipelineModalOpen: (open) => set({ isNewPipelineModalOpen: open }),
  setSkillSelectModalOpen: (open) => set({ isSkillSelectModalOpen: open }),

  startPipeline: (id) => {
    set((state) => ({
      pipelines: state.pipelines.map((p) => {
        if (p.id !== id) return p;
        const executions = p.steps.map((_, i) => ({ stepIndex: i, status: 'pending' as const }));
        return { ...p, status: 'running', currentRound: 1, executions };
      }),
    }));
    get().savePipelines();
  },

  pausePipeline: (id) => {
    set((state) => ({
      pipelines: state.pipelines.map((p) => (p.id !== id ? p : { ...p, status: 'paused' as const })),
    }));
    get().savePipelines();
  },

  stopPipeline: (id) => {
    set((state) => ({
      pipelines: state.pipelines.map((p) => (p.id !== id ? p : { ...p, status: 'stopped' as const })),
    }));
    get().savePipelines();
  },

  completeRound: (id) => {
    set((state) => ({
      pipelines: state.pipelines.map((p) => {
        if (p.id !== id) return p;
        const nextRound = p.currentRound + 1;
        const done = nextRound > p.rounds;
        return {
          ...p,
          currentRound: nextRound,
          status: done ? ('completed' as const) : ('running' as const),
          executions: done ? p.executions : p.steps.map((_, i) => ({ stepIndex: i, status: 'pending' as const })),
        };
      }),
    }));
    get().savePipelines();
  },

  updateStepExecution: (pipelineId, stepIndex, update) => {
    set((state) => ({
      pipelines: state.pipelines.map((p) => {
        if (p.id !== pipelineId) return p;
        return {
          ...p,
          executions: p.executions.map((e, i) => (i === stepIndex ? { ...e, ...update } : e)),
        };
      }),
    }));
  },
}));

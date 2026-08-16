import { invoke } from './invoke';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('pipelineApi');

export interface PipelineStepDef {
  skillId: string;
  skillName: string;
  params: Record<string, any>;
  order: number;
}

export interface PipelineDef {
  id: string;
  name: string;
  scene: string;
  steps: PipelineStepDef[];
  rounds: number;
  currentRound: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreatePipelineInput {
  name: string;
  scene: string;
  steps: PipelineStepDef[];
  rounds: number;
}

export interface UpdatePipelineInput {
  id: string;
  name: string;
  steps: PipelineStepDef[];
  rounds: number;
}

export interface RecordStepInput {
  pipelineId: string;
  stepIndex: number;
  skillId: string;
  params: Record<string, any>;
  result: string;
  durationMs: number;
  status: string;
}

export async function pipelineCreate(input: CreatePipelineInput): Promise<PipelineDef> {
  return invoke<PipelineDef>('pipeline_create', { input });
}

export async function pipelineList(scene: string): Promise<PipelineDef[]> {
  try {
    return await invoke<PipelineDef[]>('pipeline_list', { scene }) ?? [];
  } catch (err) {
    log.warn('pipelineList failed', { error: err });
    return [];
  }
}

export async function pipelineGet(id: string): Promise<PipelineDef | null> {
  try {
    return await invoke<PipelineDef | null>('pipeline_get', { id });
  } catch (err) {
    log.warn('pipelineGet failed', { error: err });
    return null;
  }
}

export async function pipelineUpdate(input: UpdatePipelineInput): Promise<PipelineDef> {
  return invoke<PipelineDef>('pipeline_update', { input });
}

export async function pipelineDelete(id: string): Promise<boolean> {
  return invoke<boolean>('pipeline_delete', { id });
}

export async function pipelineStart(id: string): Promise<PipelineDef> {
  return invoke<PipelineDef>('pipeline_start', { id });
}

export async function pipelinePause(id: string): Promise<PipelineDef> {
  return invoke<PipelineDef>('pipeline_pause', { id });
}

export async function pipelineStop(id: string): Promise<PipelineDef> {
  return invoke<PipelineDef>('pipeline_stop', { id });
}

export async function pipelineCompleteRound(id: string): Promise<PipelineDef> {
  return invoke<PipelineDef>('pipeline_complete_round', { id });
}

export async function pipelineRecordStep(input: RecordStepInput): Promise<void> {
  return invoke<void>('pipeline_record_step', { input });
}

export interface PipelineTemplate {
  id: string;
  name: string;
  description: string;
  steps: PipelineStepDef[];
  rounds: number;
}

export async function pipelineGetTemplates(): Promise<PipelineTemplate[]> {
  try {
    return await invoke<PipelineTemplate[]>('pipeline_get_templates') ?? [];
  } catch (err) {
    log.warn('pipelineGetTemplates failed', { error: err });
    return [];
  }
}

export interface ResolveParamsInput {
  params: Record<string, any>;
  outputs: Record<string, any>[];
}

export async function pipelineResolveParams(input: ResolveParamsInput): Promise<Record<string, any>> {
  return invoke<Record<string, any>>('pipeline_resolve_params', { input });
}

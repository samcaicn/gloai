import { store } from '../store';
import {
  setLoading,
  setError,
  setTasks,
  addTask,
  updateTask,
  removeTask,
  updateTaskState,
  setRuns,
  addOrUpdateRun,
  setAllRuns,
  appendAllRuns,
} from '../store/slices/scheduledTaskSlice';
import type {
  ScheduledTaskInput,
  ScheduledTaskStatusEvent,
  ScheduledTaskRunEvent,
} from '../types/scheduledTask';

class ScheduledTaskService {
  private cleanupFns: (() => void)[] = [];
  private initialized = false;

  async init(): Promise<void> {
    if (this.initialized) return;
    this.initialized = true;

    this.setupListeners();
    await this.loadTasks();
  }

  destroy(): void {
    this.cleanupFns.forEach((fn) => fn());
    this.cleanupFns = [];
    this.initialized = false;
  }

  private setupListeners(): void {
    const api = window.electron?.scheduledTasks;
    if (!api) return;

    const cleanupStatus = api.onStatusUpdate(
      (event: ScheduledTaskStatusEvent) => {
        store.dispatch(
          updateTaskState({
            taskId: event.taskId,
            taskState: event.state,
          })
        );
      }
    );
    this.cleanupFns.push(cleanupStatus);

    const cleanupRun = api.onRunUpdate(
      (event: ScheduledTaskRunEvent) => {
        store.dispatch(addOrUpdateRun(event.run));
      }
    );
    this.cleanupFns.push(cleanupRun);
  }

  async loadTasks(): Promise<void> {
    const api = window.electron?.scheduledTasks;
    if (!api) return;

    store.dispatch(setLoading(true));
    try {
      const result = await api.list();
      if (result.success && result.tasks) {
        store.dispatch(setTasks(result.tasks));
      }
    } catch (err: unknown) {
      store.dispatch(setError(err instanceof Error ? err.message : String(err)));
    }
  }

  async createTask(input: ScheduledTaskInput): Promise<void> {
    const api = window.electron?.scheduledTasks;
    if (!api) return;

    try {
      console.log('[scheduledTask] Creating task with input:', input);
      const result = await api.create(input);
      console.log('[scheduledTask] Create task result:', result);
      if (result.success && result.task) {
        console.log('[scheduledTask] Task created successfully:', result.task);
        store.dispatch(addTask(result.task));
      } else {
        const errorMessage = result.error || 'Failed to create task';
        console.error('[scheduledTask] Failed to create task:', errorMessage);
        throw new Error(errorMessage);
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      console.error('[scheduledTask] Error creating task:', errorMessage);
      store.dispatch(setError(errorMessage));
      throw err;
    }
  }

  async updateTaskById(
    id: string,
    input: Partial<ScheduledTaskInput>
  ): Promise<void> {
    const api = window.electron?.scheduledTasks;
    if (!api) return;

    try {
      console.log('[scheduledTask] Updating task', id, 'with input:', input);
      const result = await api.update(id, input);
      console.log('[scheduledTask] Update task result:', result);
      if (result.success && result.task) {
        console.log('[scheduledTask] Task updated successfully:', result.task);
        store.dispatch(updateTask(result.task));
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      console.error('[scheduledTask] Error updating task:', errorMessage);
      store.dispatch(setError(errorMessage));
      throw err;
    }
  }

  async deleteTask(id: string): Promise<void> {
    const api = window.electron?.scheduledTasks;
    if (!api) return;

    try {
      console.log('[scheduledTask] Deleting task:', id);
      const result = await api.delete(id);
      console.log('[scheduledTask] Delete task result:', result);
      if (result.success) {
        console.log('[scheduledTask] Task deleted successfully:', id);
        store.dispatch(removeTask(id));
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      console.error('[scheduledTask] Error deleting task:', errorMessage);
      store.dispatch(setError(errorMessage));
      throw err;
    }
  }

  async toggleTask(id: string, enabled: boolean): Promise<string | null> {
    const api = window.electron?.scheduledTasks;
    if (!api) return null;

    try {
      console.log('[scheduledTask] Toggling task', id, 'to:', enabled);
      const result = await api.toggle(id, enabled);
      console.log('[scheduledTask] Toggle task result:', result);
      if (result.success && result.task) {
        console.log('[scheduledTask] Task toggled successfully:', result.task);
        store.dispatch(updateTask(result.task));
      }
      return result.warning ?? null;
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      console.error('[scheduledTask] Error toggling task:', errorMessage);
      store.dispatch(setError(errorMessage));
      throw err;
    }
  }

  async runManually(id: string): Promise<any> {
    const api = window.electron?.scheduledTasks;
    if (!api) return null;

    try {
      // 立即更新任务状态，让用户看到任务正在执行
      store.dispatch(updateTaskState({ taskId: id, taskState: 'running' }));
      
      // 添加超时机制，确保任务执行不会无限等待
      const timeoutPromise = new Promise<never>((_, reject) => {
        setTimeout(() => reject(new Error('Task execution timeout')), 30000); // 30秒超时
      });
      
      const result = await Promise.race([api.runManually(id), timeoutPromise]);
      
      // 任务执行成功，更新状态为 idle
      store.dispatch(updateTaskState({ taskId: id, taskState: 'idle' }));
      return result;
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      store.dispatch(setError(errorMessage));
      console.error('Failed to run task manually:', err);
      // 任务执行失败，更新状态为 idle
      store.dispatch(updateTaskState({ taskId: id, taskState: 'idle' }));
      // 不抛出错误，确保任务执行失败不会导致整个流程卡死
      return { success: false, error: errorMessage };
    }
  }

  async stopTask(id: string): Promise<void> {
    const api = window.electron?.scheduledTasks;
    if (!api) return;

    try {
      await api.stop(id);
    } catch (err: unknown) {
      store.dispatch(setError(err instanceof Error ? err.message : String(err)));
      throw err;
    }
  }

  async loadRuns(taskId: string, limit?: number, offset?: number): Promise<void> {
    const api = window.electron?.scheduledTasks;
    if (!api) return;

    try {
      const result = await api.listRuns(taskId, limit, offset);
      if (result.success && result.runs) {
        store.dispatch(setRuns({ taskId, runs: result.runs }));
      }
    } catch (err: unknown) {
      store.dispatch(setError(err instanceof Error ? err.message : String(err)));
    }
  }

  async loadAllRuns(limit?: number, offset?: number): Promise<void> {
    const api = window.electron?.scheduledTasks;
    if (!api) return;

    try {
      const result = await api.listAllRuns(limit, offset);
      if (result.success && result.runs) {
        if (offset && offset > 0) {
          store.dispatch(appendAllRuns(result.runs));
        } else {
          store.dispatch(setAllRuns(result.runs));
        }
      }
    } catch (err: unknown) {
      store.dispatch(setError(err instanceof Error ? err.message : String(err)));
    }
  }
}

export const scheduledTaskService = new ScheduledTaskService();

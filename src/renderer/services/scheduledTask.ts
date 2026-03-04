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
import { tauriApi, isTauriReady } from './tauriApi';
import { coworkService } from './cowork';
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

    // 添加超时机制，确保初始化过程不会卡住
    try {
      const timeoutPromise = new Promise<void>((_, reject) => {
        setTimeout(() => reject(new Error('Scheduled task service initialization timeout')), 5000);
      });

      await Promise.race([
        (async () => {
          await this.setupListeners();
          await this.loadTasks();
        })(),
        timeoutPromise
      ]);
    } catch (error) {
      console.error('Scheduled task service initialization failed:', error);
      // 即使失败也继续，不影响应用启动
    }
  }

  destroy(): void {
    this.cleanupFns.forEach((fn) => fn());
    this.cleanupFns = [];
    this.initialized = false;
  }

  private async setupListeners(): Promise<void> {
    if (!isTauriReady()) return;

    // 监听任务状态更新
    const cleanupStatus = await tauriApi.on('scheduler_task_status', (event: ScheduledTaskStatusEvent) => {
      store.dispatch(
        updateTaskState({
          taskId: event.taskId,
          taskState: event.state,
        })
      );
    });
    this.cleanupFns.push(cleanupStatus);

    // 监听任务运行更新
    const cleanupRun = await tauriApi.on('scheduler_task_run', (event: ScheduledTaskRunEvent) => {
      store.dispatch(addOrUpdateRun(event.run));
    });
    this.cleanupFns.push(cleanupRun);
  }

  async loadTasks(): Promise<void> {
    if (!isTauriReady()) return;

    store.dispatch(setLoading(true));
    try {
      const tasks = await tauriApi.invoke('scheduler_list_tasks');
      if (tasks && Array.isArray(tasks)) {
        store.dispatch(setTasks(tasks));
      }
    } catch (err: unknown) {
      store.dispatch(setError(err instanceof Error ? err.message : String(err)));
    }
  }

  async createTask(input: ScheduledTaskInput): Promise<void> {
    if (!isTauriReady()) return;

    try {
      console.log('[scheduledTask] Creating task with input:', input);
      const result = await tauriApi.invoke<{ success: boolean; task?: any; error?: string }>('scheduler_create_task', { input });
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
    if (!isTauriReady()) return;

    try {
      console.log('[scheduledTask] Updating task', id, 'with input:', input);
      const result = await tauriApi.invoke<{ success: boolean; task?: any }>('scheduler_update_task', { id, input });
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
    if (!isTauriReady()) return;

    try {
      console.log('[scheduledTask] Deleting task:', id);
      const result = await tauriApi.invoke<{ success: boolean }>('scheduler_delete_task', { id });
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
    if (!isTauriReady()) return null;

    try {
      console.log('[scheduledTask] Toggling task', id, 'to:', enabled);
      const result = await tauriApi.invoke<{ success: boolean; task?: any; warning?: string }>('scheduler_toggle_task', { id, enabled });
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
    if (!isTauriReady()) return null;

    try {
      // 立即更新任务状态，让用户看到任务正在执行
      store.dispatch(updateTaskState({ 
        taskId: id, 
        taskState: {
          nextRunAtMs: null,
          lastRunAtMs: null,
          lastStatus: 'running',
          lastError: null,
          lastDurationMs: null,
          runningAtMs: Date.now(),
          consecutiveErrors: 0
        }
      }));
      
      // 非阻塞执行任务
      (async () => {
        try {
          // 执行后端任务
          await tauriApi.invoke('scheduler_execute_task', { id });
          
          // 创建会话
          await coworkService.startSession({
            prompt: `执行定时任务: ${id}`,
            title: `定时任务执行: ${id}`,
          });
          
          // 任务执行成功，更新状态
          store.dispatch(updateTaskState({ 
            taskId: id, 
            taskState: {
              nextRunAtMs: null,
              lastRunAtMs: Date.now(),
              lastStatus: 'success',
              lastError: null,
              lastDurationMs: 0,
              runningAtMs: null,
              consecutiveErrors: 0
            }
          }));
        } catch (err) {
          const errorMessage = err instanceof Error ? err.message : String(err);
          store.dispatch(setError(errorMessage));
          console.error('Failed to run task manually:', err);
          // 任务执行失败，更新状态
          store.dispatch(updateTaskState({ 
            taskId: id, 
            taskState: {
              nextRunAtMs: null,
              lastRunAtMs: Date.now(),
              lastStatus: 'error',
              lastError: errorMessage,
              lastDurationMs: 0,
              runningAtMs: null,
              consecutiveErrors: 1
            }
          }));
        }
      })();
      
      // 立即返回，不等待任务执行完成
      return { success: true, message: 'Task started in background' };
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      store.dispatch(setError(errorMessage));
      console.error('Failed to run task manually:', err);
      // 任务执行失败，更新状态
      store.dispatch(updateTaskState({ 
        taskId: id, 
        taskState: {
          nextRunAtMs: null,
          lastRunAtMs: Date.now(),
          lastStatus: 'error',
          lastError: errorMessage,
          lastDurationMs: 0,
          runningAtMs: null,
          consecutiveErrors: 1
        }
      }));
      // 不抛出错误，确保任务执行失败不会导致整个流程卡死
      return { success: false, error: errorMessage };
    }
  }

  async stopTask(id: string): Promise<void> {
    if (!isTauriReady()) return;

    try {
      await tauriApi.invoke('scheduler_stop_task', { id });
    } catch (err: unknown) {
      store.dispatch(setError(err instanceof Error ? err.message : String(err)));
      throw err;
    }
  }

  async loadRuns(taskId: string, limit?: number, offset?: number): Promise<void> {
    if (!isTauriReady()) return;

    try {
      const result = await tauriApi.invoke<{ success: boolean; runs?: any[] }>('scheduler_list_task_runs', { taskId, limit, offset });
      if (result.success && result.runs) {
        store.dispatch(setRuns({ taskId, runs: result.runs }));
      }
    } catch (err: unknown) {
      store.dispatch(setError(err instanceof Error ? err.message : String(err)));
    }
  }

  async loadAllRuns(limit?: number, offset?: number): Promise<void> {
    if (!isTauriReady()) return;

    try {
      const result = await tauriApi.invoke<{ success: boolean; runs?: any[] }>('scheduler_list_task_runs', { taskId: null, limit, offset });
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

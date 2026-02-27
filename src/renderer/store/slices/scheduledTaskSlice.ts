import { createSlice, PayloadAction } from '@reduxjs/toolkit';
import type {
  ScheduledTask,
  ScheduledTaskRun,
  ScheduledTaskRunWithName,
  TaskState,
  ScheduledTaskViewMode,
} from '../../types/scheduledTask';

interface ScheduledTaskState {
  tasks: ScheduledTask[];
  selectedTaskId: string | null;
  viewMode: ScheduledTaskViewMode;
  runs: Record<string, ScheduledTaskRun[]>;
  allRuns: ScheduledTaskRunWithName[];
  loading: boolean;
  error: string | null;
}

const initialState: ScheduledTaskState = {
  tasks: [],
  selectedTaskId: null,
  viewMode: 'list',
  runs: {},
  allRuns: [],
  loading: false,
  error: null,
};

const scheduledTaskSlice = createSlice({
  name: 'scheduledTask',
  initialState,
  reducers: {
    setLoading(state, action: PayloadAction<boolean>) {
      state.loading = action.payload;
    },
    setError(state, action: PayloadAction<string | null>) {
      state.error = action.payload;
    },
    setTasks(state, action: PayloadAction<ScheduledTask[]>) {
      state.tasks = action.payload;
      state.loading = false;
    },
    addTask(state, action: PayloadAction<ScheduledTask>) {
      state.tasks.unshift(action.payload);
    },
    updateTask(state, action: PayloadAction<ScheduledTask>) {
      const index = state.tasks.findIndex((t) => t.id === action.payload.id);
      if (index !== -1) {
        state.tasks[index] = action.payload;
      }
    },
    removeTask(state, action: PayloadAction<string>) {
      state.tasks = state.tasks.filter((t) => t.id !== action.payload);
      if (state.selectedTaskId === action.payload) {
        state.selectedTaskId = null;
        state.viewMode = 'list';
      }
      delete state.runs[action.payload];
      state.allRuns = state.allRuns.filter((r) => r.taskId !== action.payload);
    },
    updateTaskState(
      state,
      action: PayloadAction<{ taskId: string; taskState: TaskState }>
    ) {
      const task = state.tasks.find((t) => t.id === action.payload.taskId);
      if (task) {
        task.state = action.payload.taskState;
      }
    },
    selectTask(state, action: PayloadAction<string | null>) {
      state.selectedTaskId = action.payload;
      state.viewMode = action.payload ? 'detail' : 'list';
    },
    setViewMode(state, action: PayloadAction<ScheduledTaskViewMode>) {
      state.viewMode = action.payload;
    },
    setRuns(
      state,
      action: PayloadAction<{ taskId: string; runs: ScheduledTaskRun[] }>
    ) {
      state.runs[action.payload.taskId] = action.payload.runs;
    },
    addOrUpdateRun(state, action: PayloadAction<ScheduledTaskRun>) {
      const { taskId } = action.payload;
      if (!state.runs[taskId]) {
        state.runs[taskId] = [];
      }
      const existingIndex = state.runs[taskId].findIndex(
        (r) => r.id === action.payload.id
      );
      if (existingIndex !== -1) {
        state.runs[taskId][existingIndex] = action.payload;
      } else {
        state.runs[taskId].unshift(action.payload);
      }
    },
    setAllRuns(state, action: PayloadAction<ScheduledTaskRunWithName[]>) {
      state.allRuns = action.payload;
    },
    appendAllRuns(state, action: PayloadAction<ScheduledTaskRunWithName[]>) {
      state.allRuns = [...state.allRuns, ...action.payload];
    },
  },
});

export const {
  setLoading,
  setError,
  setTasks,
  addTask,
  updateTask,
  removeTask,
  updateTaskState,
  selectTask,
  setViewMode,
  setRuns,
  addOrUpdateRun,
  setAllRuns,
  appendAllRuns,
} = scheduledTaskSlice.actions;

export default scheduledTaskSlice.reducer;

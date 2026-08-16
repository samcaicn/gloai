import {
  backgroundTaskScheduler,
  type BackgroundTaskHandle,
  type BackgroundTaskScheduler,
} from '@/shared/utils/backgroundTaskScheduler';
import { createLogger } from '@/shared/utils/logger';
import { startupTrace } from '@/shared/utils/startupTrace';
import { isTauriRuntime } from '@/infrastructure/runtime';

const log = createLogger('DeferredStartupSystems');

interface DeferredStartupLog {
  debug: (message: string, data?: unknown) => void;
  warn: (message: string, data?: unknown) => void;
  error: (message: string, data?: unknown) => void;
}

interface DeferredStartupTrace {
  markPhase: (phase: string, data?: Record<string, unknown>) => void;
}

export interface DeferredStartupSystemsDependencies {
  scheduler?: Pick<BackgroundTaskScheduler, 'schedule'>;
  log?: DeferredStartupLog;
  trace?: DeferredStartupTrace;
  initializeIdeControl?: () => Promise<void>;
  initializeMcpServers?: () => Promise<void>;
  initializeAcpClients?: () => Promise<void>;
  probeAcpClientRequirements?: () => Promise<void>;
  preloadDeferredRenderers?: () => Promise<void>;
}

async function initializeIdeControlDefault(): Promise<void> {
  const { initializeIdeControl } = await import('@/shared/services/ide-control');
  await initializeIdeControl();
}

async function initializeMcpServersDefault(): Promise<void> {
  // No-op: the app uses remote MCP via mcp_call_v2 (proxying to ai.tuptup.top).
  // Local MCP server initialization is not needed — the initialize_mcp_servers
  // command was never implemented in the Rust backend, causing a noisy
  // "Command not found" error on every startup. Remote MCP works independently.
  // Kept as a function (not deleted) to preserve the deferred startup step slot.
}

async function initializeAcpClientsDefault(): Promise<void> {
  const { ACPClientAPI } = await import('@/infrastructure/api/service-api/ACPClientAPI');
  await ACPClientAPI.initializeClients();
}

async function probeAcpClientRequirementsDefault(): Promise<void> {
  const { ACPClientAPI } = await import('@/infrastructure/api/service-api/ACPClientAPI');
  await ACPClientAPI.probeClientRequirements();
}

async function preloadDeferredRenderersDefault(): Promise<void> {
  const [
    { preloadMarkdownMathRenderer },
    { preloadTerminalOutputRenderer },
  ] = await Promise.all([
    import('@/component-library/components/Markdown/Markdown'),
    import('@/tools/terminal/components/LazyTerminalOutputRenderer'),
  ]);

  await Promise.all([
    preloadMarkdownMathRenderer(),
    preloadTerminalOutputRenderer(),
  ]);
}

export function scheduleDeferredStartupSystems(
  dependencies: DeferredStartupSystemsDependencies = {}
): BackgroundTaskHandle<void> {
  const scheduler = dependencies.scheduler ?? backgroundTaskScheduler;
  const logger = dependencies.log ?? log;
  const trace = dependencies.trace ?? startupTrace;
  const initializeIdeControl = dependencies.initializeIdeControl ?? initializeIdeControlDefault;
  const initializeMcpServers = dependencies.initializeMcpServers ?? initializeMcpServersDefault;
  const initializeAcpClients = dependencies.initializeAcpClients ?? initializeAcpClientsDefault;
  const probeAcpClientRequirements =
    dependencies.probeAcpClientRequirements ?? probeAcpClientRequirementsDefault;
  const preloadDeferredRenderers = dependencies.preloadDeferredRenderers ?? preloadDeferredRenderersDefault;

  return scheduler.schedule(async signal => {
    if (signal.aborted) {
      return;
    }

    // 非 Tauri 环境下所有 deferred startup 系统都依赖后端命令，静默跳过避免错误日志
    if (!isTauriRuntime()) {
      trace.markPhase('deferred_startup_systems_skipped_non_tauri');
      return;
    }

    trace.markPhase('deferred_startup_systems_start');

    const runStep = async (name: string, step: () => Promise<void>) => {
      if (signal.aborted) {
        return;
      }
      try {
        await step();
        logger.debug('Deferred startup system initialized', { system: name });
      } catch (error) {
        logger.error('Deferred startup system failed', { system: name, error });
      }
    };

    await runStep('ide_control', initializeIdeControl);
    await runStep('mcp_servers', initializeMcpServers);
    await runStep('acp_clients', initializeAcpClients);
    await runStep('acp_client_requirements', probeAcpClientRequirements);
    await runStep('renderer_preloads', preloadDeferredRenderers);

    if (!signal.aborted) {
      trace.markPhase('deferred_startup_systems_end');
    }
  }, {
    idle: true,
    priority: 'low',
    inFlightKey: 'startup:deferred-systems',
  });
}

/**
 * IM Service
 * IPC wrapper for IM gateway operations
 */

import { store } from '../store';
import { tauriApi, isTauriReady } from './tauriApi';
import {
  setConfig,
  setStatus,
  setLoading,
  setError,
} from '../store/slices/imSlice';
import type {
  IMGatewayConfig,
  IMGatewayStatus,
  IMPlatform,
  IMConfigResult,
  IMStatusResult,
  IMGatewayResult,
  IMConnectivityTestResult,
  IMConnectivityTestResponse,
} from '../types/im';

class IMService {
  private statusUnsubscribe: (() => void) | null = null;
  private messageUnsubscribe: (() => void) | null = null;

  /**
   * Initialize IM service
   */
  async init(): Promise<void> {
    if (!isTauriReady()) return;

    // Set up status change listener
    this.statusUnsubscribe = await tauriApi.on('im_status_change', (status) => {
      store.dispatch(setStatus(status as IMGatewayStatus));
    });

    // Set up message listener (for logging/monitoring)
    this.messageUnsubscribe = await tauriApi.on('im_message_received', (message) => {
      console.log('[IM Service] Message received:', message);
    });

    // Load initial config and status
    await this.loadConfig();
    await this.loadStatus();
  }

  /**
   * Clean up listeners
   */
  destroy(): void {
    if (this.statusUnsubscribe) {
      this.statusUnsubscribe();
      this.statusUnsubscribe = null;
    }
    if (this.messageUnsubscribe) {
      this.messageUnsubscribe();
      this.messageUnsubscribe = null;
    }
  }

  /**
   * Load configuration from main process
   */
  async loadConfig(): Promise<IMGatewayConfig | null> {
    if (!isTauriReady()) return null;

    try {
      store.dispatch(setLoading(true));
      const result = await tauriApi.invoke('im_get_config') as IMConfigResult;
      if (result.success && result.config) {
        store.dispatch(setConfig(result.config));
        return result.config;
      } else {
        store.dispatch(setError(result.error || 'Failed to load IM config'));
        return null;
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to load IM config';
      store.dispatch(setError(message));
      return null;
    } finally {
      store.dispatch(setLoading(false));
    }
  }

  /**
   * Load status from main process
   */
  async loadStatus(): Promise<IMGatewayStatus | null> {
    if (!isTauriReady()) return null;

    try {
      const result = await tauriApi.invoke('im_get_status') as IMStatusResult;
      if (result.success && result.status) {
        store.dispatch(setStatus(result.status));
        return result.status;
      }
      return null;
    } catch (error) {
      console.error('[IM Service] Failed to load status:', error);
      return null;
    }
  }

  /**
   * Update configuration
   */
  async updateConfig(config: Partial<IMGatewayConfig>): Promise<boolean> {
    if (!isTauriReady()) return false;

    try {
      store.dispatch(setLoading(true));
      const result = await tauriApi.invoke('im_set_config', { config }) as IMGatewayResult;
      if (result.success) {
        // Reload config to get merged values
        await this.loadConfig();
        return true;
      } else {
        store.dispatch(setError(result.error || 'Failed to update IM config'));
        return false;
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to update IM config';
      store.dispatch(setError(message));
      return false;
    } finally {
      store.dispatch(setLoading(false));
    }
  }

  /**
   * Start a gateway
   */
  async startGateway(platform: IMPlatform): Promise<boolean> {
    if (!isTauriReady()) return false;

    try {
      store.dispatch(setLoading(true));
      store.dispatch(setError(null));
      const result = await tauriApi.invoke('im_start_gateway', { platform }) as IMGatewayResult;
      if (result.success) {
        await this.loadStatus();
        return true;
      } else {
        store.dispatch(setError(result.error || `Failed to start ${platform} gateway`));
        return false;
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : `Failed to start ${platform} gateway`;
      store.dispatch(setError(message));
      return false;
    } finally {
      store.dispatch(setLoading(false));
    }
  }

  /**
   * Stop a gateway
   */
  async stopGateway(platform: IMPlatform): Promise<boolean> {
    if (!isTauriReady()) return false;

    try {
      store.dispatch(setLoading(true));
      const result = await tauriApi.invoke('im_stop_gateway', { platform }) as IMGatewayResult;
      if (result.success) {
        await this.loadStatus();
        return true;
      } else {
        store.dispatch(setError(result.error || `Failed to stop ${platform} gateway`));
        return false;
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : `Failed to stop ${platform} gateway`;
      store.dispatch(setError(message));
      return false;
    } finally {
      store.dispatch(setLoading(false));
    }
  }

  /**
   * Test gateway connectivity and conversation readiness
   */
  async testGateway(
    platform: IMPlatform,
    configOverride?: Partial<IMGatewayConfig>
  ): Promise<IMConnectivityTestResult | null> {
    if (!isTauriReady()) return null;

    try {
      store.dispatch(setLoading(true));
      const result = await tauriApi.invoke('im_test_gateway', { platform, configOverride }) as IMConnectivityTestResponse;
      if (result.success && result.result) {
        return result.result;
      }
      store.dispatch(setError(result.error || `Failed to test ${platform} connectivity`));
      return null;
    } catch (error) {
      const message = error instanceof Error ? error.message : `Failed to test ${platform} connectivity`;
      store.dispatch(setError(message));
      return null;
    } finally {
      store.dispatch(setLoading(false));
    }
  }

  /**
   * Get current config from store
   */
  getConfig(): IMGatewayConfig {
    return store.getState().im.config;
  }

  /**
   * Get current status from store
   */
  getStatus(): IMGatewayStatus {
    return store.getState().im.status;
  }

  /**
   * Check if any gateway is connected
   */
  isAnyConnected(): boolean {
    const status = this.getStatus();
    return status.dingtalk.connected || status.feishu.connected || status.telegram.connected || status.discord.connected || status.wework.connected;
  }
}

export const imService = new IMService();

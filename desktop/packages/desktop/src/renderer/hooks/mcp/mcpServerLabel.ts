import type { TFunction } from 'i18next';
import { BUILTIN_BROWSER_MCP_NAME } from '@/common/config/constants';
import type { IMcpServer } from '@/common/config/storage';

/**
 * The name an MCP server is shown by: its own, except the built-in browser's, whose name is an internal id kept for
 * the config it is stored under.
 */
export const mcpServerLabel = (server: Pick<IMcpServer, 'name'>, t: TFunction): string =>
  server.name === BUILTIN_BROWSER_MCP_NAME ? t('settings.mcpBuiltinBrowserName') : server.name;

import React from 'react';

interface GoclawConfig {
  enabled: boolean;
  binaryPath: string;
  configPath: string;
  workDir: string;
  wsUrl: string;
  httpUrl: string;
  autoStart: boolean;
}

interface GoclawSettingsProps {
  config: GoclawConfig;
  onChange: (config: Partial<GoclawConfig>) => void;
}

const GoclawSettings: React.FC<GoclawSettingsProps> = ({ config }) => {

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold dark:text-claude-darkText text-claude-text">claw 信息</h3>
      </div>

      <div className="space-y-5">
        {/* HTTP 地址 */}
        <div>
          <label className="block text-sm font-medium dark:text-claude-darkText text-claude-text mb-2">
            HTTP 网关地址
          </label>
          <div className="w-full rounded-xl bg-claude-surfaceInset dark:bg-claude-darkSurfaceInset dark:border-claude-darkBorder border-claude-border border px-3 py-2">
            <span className="dark:text-claude-darkText text-claude-text">
              {config.httpUrl}
            </span>
          </div>
        </div>

        {/* WebSocket 地址 */}
        <div>
          <label className="block text-sm font-medium dark:text-claude-darkText text-claude-text mb-2">
            WebSocket 地址
          </label>
          <div className="w-full rounded-xl bg-claude-surfaceInset dark:bg-claude-darkSurfaceInset dark:border-claude-darkBorder border-claude-border border px-3 py-2">
            <span className="dark:text-claude-darkText text-claude-text">
              {config.wsUrl}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
};

export default GoclawSettings;

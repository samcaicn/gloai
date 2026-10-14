import React from 'react';
import { useTranslation } from 'react-i18next';
import { Tooltip } from '@arco-design/web-react';
import type { IMcpServer } from '@/common/config/storage';

interface McpServerToolsListProps {
  server: IMcpServer;
}

const McpServerToolsList: React.FC<McpServerToolsListProps> = ({ server }) => {
  const { t } = useTranslation();

  if (!server.tools || server.tools.length === 0) {
    return null;
  }

  // A server's tools, one quiet row each between hairlines: no box inside the server's row.
  return (
    <div className='settings-list'>
      {server.tools.map((tool, index) => (
        <div key={index} className='flex gap-16px py-8px'>
          <div className='flex-shrink-0 min-w-0 w-1/3'>
            <div className='break-words text-13px font-500 text-t-primary'>{tool.name}</div>
          </div>
          <div className='flex-1 min-w-0'>
            <Tooltip content={tool.description || t('settings.mcpNoDescription')}>
              <div className='line-clamp-1 cursor-pointer text-12px leading-5 text-t-secondary'>
                {tool.description || t('settings.mcpNoDescription')}
              </div>
            </Tooltip>
          </div>
        </div>
      ))}
    </div>
  );
};

export default McpServerToolsList;

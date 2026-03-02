import React from 'react';
import { useSelector, useDispatch } from 'react-redux';
import { RootState } from '../../store';
import { selectPrompt } from '../../store/slices/quickActionSlice';
import type { LocalizedQuickAction, LocalizedPrompt } from '../../types/quickAction';
import { ArrowRightIcon } from '@heroicons/react/24/outline';

interface PromptPanelProps {
  action: LocalizedQuickAction;
  onPromptSelect: (prompt: string) => void;
}

const PromptPanel: React.FC<PromptPanelProps> = ({ action, onPromptSelect }) => {
  const dispatch = useDispatch();
  const selectedPromptId = useSelector(
    (state: RootState) => state.quickAction.selectedPromptId
  );

  const handlePromptClick = (prompt: LocalizedPrompt) => {
    dispatch(selectPrompt(prompt.id));
    onPromptSelect(prompt.prompt);
  };

  if (!action.prompts || action.prompts.length === 0) {
    return null;
  }

  return (
    <div className="w-full animate-fade-in-up">
      {/* 标题 */}
      <div className="mb-2.5 px-0.5">
        <span className="text-xs font-medium dark:text-claude-darkTextSecondary text-claude-textSecondary">
          {action.label}
        </span>
      </div>

      {/* 提示词卡片网格 */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        {action.prompts.map((prompt) => {
          const isPromptSelected = selectedPromptId === prompt.id;

          return (
            <button
              key={prompt.id}
              type="button"
              onClick={() => handlePromptClick(prompt)}
              className={`
                group relative flex flex-col items-start gap-1.5 px-3.5 py-3 rounded-lg
                border text-left transition-all duration-200
                ${
                  isPromptSelected
                    ? 'dark:bg-claude-accentMuted bg-claude-accentMuted border-claude-accent/50'
                    : 'dark:bg-claude-darkSurface bg-claude-surface dark:border-claude-darkBorder border-claude-border dark:hover:border-claude-darkBorder hover:border-claude-border dark:hover:bg-claude-darkSurfaceHover hover:bg-claude-surfaceHover'
                }
              `}
            >
              {/* 标题 */}
              <div className="flex items-center justify-between w-full">
                <span className={`text-sm font-medium ${isPromptSelected ? 'text-claude-accent' : 'dark:text-claude-darkText text-claude-text'}`}>
                  {prompt.label}
                </span>
                <ArrowRightIcon
                  className={`
                    w-3.5 h-3.5 transition-all duration-200
                    ${
                      isPromptSelected
                        ? 'text-claude-accent translate-x-0 opacity-100'
                        : 'dark:text-claude-darkTextSecondary text-claude-textSecondary -translate-x-1 opacity-0 group-hover:translate-x-0 group-hover:opacity-100'
                    }
                  `}
                />
              </div>

              {/* 描述 */}
              {prompt.description && (
                <p className="text-xs dark:text-claude-darkTextSecondary text-claude-textSecondary line-clamp-2">
                  {prompt.description}
                </p>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default PromptPanel;

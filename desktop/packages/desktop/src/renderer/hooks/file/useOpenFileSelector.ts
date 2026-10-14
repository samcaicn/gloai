import { ipcBridge } from '@/common';
import { useCallback } from 'react';

interface UseOpenFileSelectorOptions {
  onFilesSelected: (files: string[]) => void;
}

interface UseOpenFileSelectorResult {
  openFileSelector: () => void;
  onSlashBuiltinCommand: (name: string) => void;
}

/**
 * Shared open-file selector behavior for send boxes.
 * Unifies '+' button and '/open' builtin command handling.
 *
 * Opens the native Electron file dialog.
 */
export function useOpenFileSelector(options: UseOpenFileSelectorOptions): UseOpenFileSelectorResult {
  const { onFilesSelected } = options;

  const openFileSelector = useCallback(() => {
    void ipcBridge.dialog.showOpen
      .invoke({ properties: ['openFile', 'multiSelections'] })
      .then((files) => {
        if (!files || files.length === 0) {
          return;
        }
        onFilesSelected(files);
      })
      .catch((error) => {
        // The dialog can still fail if the bridge is not connected. Log for debugging.
        console.warn('[useOpenFileSelector] Failed to open file selector:', error);
      });
  }, [onFilesSelected]);

  const onSlashBuiltinCommand = useCallback(
    (name: string) => {
      if (name === 'open') {
        openFileSelector();
      }
    },
    [openFileSelector]
  );

  return {
    openFileSelector,
    onSlashBuiltinCommand,
  };
}

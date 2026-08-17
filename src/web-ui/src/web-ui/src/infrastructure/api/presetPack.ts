// Preset Pack API — the dsh-equivalent portable agent mechanism.
//
// Bridges the Rust `commands::preset_pack` surface. Packages are `.dshpreset`
// ZIP blobs moved as binary (`Uint8Array` args / `ArrayBuffer` returns), so a
// preset can be exported, shared, and re-imported with a safe preview.

import { invoke } from '@tauri-apps/api/core';

export interface PresetManifest {
  format: string;
  version: number;
  id: string;
  name: string;
  description: string;
  sourceDshVersion: string;
  exportedAt: string;
}

export interface PresetWarning {
  warningType: string;
  packageVersion?: string;
  appVersion?: string;
}

export interface PackagePreview {
  manifest: PresetManifest;
  fileCount: number;
  totalBytes: number;
  warnings: PresetWarning[];
  suggestedTargetId: string;
}

export interface PresetInfo {
  id: string;
  name: string;
  description: string;
  sourceDshVersion: string;
  exportedAt: string;
  fileCount: number;
  path: string;
}

function toUint8Array(buf: ArrayBuffer | number[]): Uint8Array {
  if (buf instanceof ArrayBuffer) return new Uint8Array(buf);
  return Uint8Array.from(buf as number[]);
}

export const presetPackAPI = {
  list(): Promise<PresetInfo[]> {
    return invoke<PresetInfo[]>('preset_list');
  },
  preview(bytes: Uint8Array): Promise<PackagePreview> {
    return invoke<PackagePreview>('preset_preview', { bytes });
  },
  import(bytes: Uint8Array, targetId: string): Promise<PresetInfo> {
    return invoke<PresetInfo>('preset_import', { bytes, targetId });
  },
  export(presetId: string): Promise<ArrayBuffer | number[]> {
    return invoke<ArrayBuffer | number[]>('preset_export', { presetId });
  },
  remove(presetId: string): Promise<void> {
    return invoke<void>('preset_delete', { presetId });
  },
  /** Export a preset and trigger a `.dshpreset` download in the browser. */
  async download(presetId: string, filename: string): Promise<void> {
    const buf = await this.export(presetId);
    const bytes = toUint8Array(buf);
    const blob = new Blob([bytes], { type: 'application/zip' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename.endsWith('.dshpreset') ? filename : `${filename}.dshpreset`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  },
};

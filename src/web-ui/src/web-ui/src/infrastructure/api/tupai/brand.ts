// Copyright (c) 2026 MeeJoy
//
// Runtime brand info — replaces hard-coded VITE_APP_* env vars.

import { invoke } from '@tauri-apps/api/core';

export interface BrandInfo {
  /** Product name shown in UI (e.g., "AIMarketing") */
  product_name: string;
  /** Unique identifier (e.g., "ai.aimarketing.desktop", "com.aimarketing.desktop") */
  identifier: string;
  /** Version from Cargo.toml / tauri.conf.json */
  version: string;
  /** Publisher name for dialogs/about */
  publisher: string;
  /** Short description for tooltips */
  short_description: string;
  /** Homepage URL */
  homepage: string;
  /** Deep-link scheme (e.g., "aimarketing") */
  deep_link_scheme: string;
  /** Whether this is an OEM/safeopc build */
  is_oem: boolean;
}

/**
 * Returns runtime brand info so the UI can adapt text/logos
 * without relying on build-time env vars.
 */
export async function getBrandInfo(): Promise<BrandInfo> {
  return invoke<BrandInfo>('get_brand_info');
}

/**
 * Returns true if running the OEM/safeopc branded build.
 */
export async function isOemBuild(): Promise<boolean> {
  return invoke<boolean>('is_oem_build');
}

// Singleton cache for synchronous reads after first async load.
let _brandInfo: BrandInfo | null = null;
let _brandPromise: Promise<BrandInfo> | null = null;

/**
 * Initialize brand info (call once at app startup).
 */
export async function initBrandInfo(): Promise<BrandInfo> {
  if (_brandPromise) return _brandPromise;
  _brandPromise = getBrandInfo().then(info => {
    _brandInfo = info;
    return info;
  });
  return _brandPromise;
}

/**
 * Synchronous access to cached brand info (after initBrandInfo).
 * Falls back to safe defaults if not yet loaded.
 */
export function getBrandInfoSync(): BrandInfo {
  if (_brandInfo) return _brandInfo;
  // Safe defaults — matches AIMarketing brand
  return {
    product_name: 'AIMarketing',
    identifier: 'ai.aimarketing.desktop',
    version: '1.8.9',
    publisher: 'AIMarketing',
    short_description: 'AIMarketing - Self-Evolving AI Workspace',
    homepage: 'https://aimarketing.example.com',
    deep_link_scheme: 'aimarketing',
    is_oem: false,
  };
}

/**
 * React hook friendly: returns cached info or defaults.
 * Use initBrandInfo() in your root component to populate.
 */
export function useBrandInfo(): BrandInfo {
  return getBrandInfoSync();
}

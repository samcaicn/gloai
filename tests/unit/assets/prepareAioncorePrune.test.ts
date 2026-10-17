import { afterEach, describe, expect, it } from 'vitest';
import {
  mkdirSync,
  mkdtempSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const { pruneManagedNodePackageManagers } = require('../../../packages/shared-scripts/src/prepare-aioncore');
const { verifyBundledAioncoreResources } = require('../../../packages/shared-scripts/src/verify-bundled-aioncore-resources');

const RUNTIME_KEY = 'win32-x64';
const NODE_ROOT = 'node/node-v24.11.0-win-x64';

// A node dir that looks like a real Windows Node.js distribution: node.exe plus
// the npm/npx/corepack package-manager tooling we strip from the shipped bundle.
function seedNodeDistribution(nodeDir: string) {
  writeFileSync(join(nodeDir, 'node.exe'), '');
  writeFileSync(join(nodeDir, 'npm'), '');
  writeFileSync(join(nodeDir, 'npm.cmd'), '');
  writeFileSync(join(nodeDir, 'npm.ps1'), '');
  writeFileSync(join(nodeDir, 'npx'), '');
  writeFileSync(join(nodeDir, 'npx.cmd'), '');
  writeFileSync(join(nodeDir, 'npx.ps1'), '');
  writeFileSync(join(nodeDir, 'corepack'), '');
  writeFileSync(join(nodeDir, 'corepack.cmd'), '');
  writeFileSync(join(nodeDir, 'blank_global_npmrc'), '');
  writeFileSync(join(nodeDir, 'blank_user_npmrc'), '');
  writeFileSync(join(nodeDir, 'install_tools.bat'), '');
  writeFileSync(join(nodeDir, 'nodevars.bat'), '');
  writeFileSync(join(nodeDir, 'LICENSE'), '');
  writeFileSync(join(nodeDir, 'README.md'), '');
  // npm + corepack ship their dependency trees under node_modules.
  mkdirSync(join(nodeDir, 'node_modules', 'npm', 'bin'), { recursive: true });
  writeFileSync(join(nodeDir, 'node_modules', 'npm', 'bin', 'npm-cli.js'), '');
  writeFileSync(join(nodeDir, 'node_modules', 'npm', 'package.json'), '');
  mkdirSync(join(nodeDir, 'node_modules', 'corepack', 'dist'), { recursive: true });
  writeFileSync(join(nodeDir, 'node_modules', 'corepack', 'dist', 'corepack.cjs'), '');
}

describe('pruneManagedNodePackageManagers', () => {
  let tmp: string;
  let resourcesDir: string;
  let managedResourcesDir: string;
  let nodeDir: string;

  beforeEach(() => {
    tmp = mkdtempSync(join(tmpdir(), 'aionui-prune-'));
    resourcesDir = join(tmp, 'resources');
    const bundleDir = join(resourcesDir, 'bundled-aioncore', RUNTIME_KEY);
    mkdirSync(bundleDir, { recursive: true });
    writeFileSync(join(bundleDir, 'aioncore.exe'), '');
    writeFileSync(join(bundleDir, 'manifest.json'), JSON.stringify({ platform: 'win32', arch: 'x64' }));
    managedResourcesDir = join(bundleDir, 'managed-resources');
    mkdirSync(managedResourcesDir, { recursive: true });
    writeFileSync(
      join(managedResourcesDir, 'manifest.json'),
      JSON.stringify({
        schemaVersion: 2,
        runtimeKey: RUNTIME_KEY,
        node: { version: '24.11.0', root: NODE_ROOT, executable: 'node.exe' },
        clis: [],
      })
    );
    nodeDir = join(managedResourcesDir, ...NODE_ROOT.split('/'));
    mkdirSync(nodeDir, { recursive: true });
    seedNodeDistribution(nodeDir);
  });

  afterEach(() => {
    rmSync(tmp, { recursive: true, force: true });
  });

  it('removes npm/npx/corepack and their node_modules but keeps node.exe', () => {
    pruneManagedNodePackageManagers(managedResourcesDir, RUNTIME_KEY);

    const remaining = readdirSync(nodeDir);
    expect(remaining).toContain('node.exe');
    // npm/npx/corepack tooling is gone.
    for (const gone of [
      'npm', 'npm.cmd', 'npm.ps1',
      'npx', 'npx.cmd', 'npx.ps1',
      'corepack', 'corepack.cmd',
      'node_modules',
      'blank_global_npmrc', 'blank_user_npmrc',
      'install_tools.bat', 'nodevars.bat',
    ]) {
      expect(remaining).not.toContain(gone);
    }
  });

  it('leaves the bundle passing the managed-resources contract verification', () => {
    pruneManagedNodePackageManagers(managedResourcesDir, RUNTIME_KEY);

    const result = verifyBundledAioncoreResources({
      resourcesDir,
      electronPlatformName: 'win32',
      targetArch: 'x64',
    });
    expect(result.missing).toEqual([]);
    expect(result.failures).toEqual([]);
  });

  it('is a no-op when AIONUI_KEEP_NODE_PACKAGE_MANAGERS is set', () => {
    const previous = process.env.AIONUI_KEEP_NODE_PACKAGE_MANAGERS;
    process.env.AIONUI_KEEP_NODE_PACKAGE_MANAGERS = '1';
    try {
      pruneManagedNodePackageManagers(managedResourcesDir, RUNTIME_KEY);
    } finally {
      if (previous === undefined) delete process.env.AIONUI_KEEP_NODE_PACKAGE_MANAGERS;
      else process.env.AIONUI_KEEP_NODE_PACKAGE_MANAGERS = previous;
    }

    const remaining = readdirSync(nodeDir);
    expect(remaining).toContain('npm');
    expect(remaining).toContain('node_modules');
  });
});

#!/usr/bin/env node
// Puts mu inside the app: the npm package mu-agent, with its dependencies, in resources/harness/mu-agent, which
// electron-builder copies to <resources>/harness (packages/desktop/electron-builder.yml, extraResources). The app runs
// it on its own binary as Node (resources/mu/acp, process/agent/kyrn/harness.ts), so the machine the app runs on needs
// no Node, no npm and no download.
//
//   node scripts/kyrn/bundle-harness.mjs [--platform darwin|linux|win32] [--arch arm64|x64]
//
// Which mu: MU_HARNESS_TARBALL, a tarball packed from a harness checkout (`node kyrn/npm/build.mjs --pack`; the MU
// repository's CI packs it from the same commit as the app), else mu-agent from the npm registry at the version
// pinned in this repository's package.json, "muAgentVersion". Its dependencies are installed with npm for the target
// platform and arch, without install scripts: npm is needed here, at build time, and nowhere else.
//
// scripts/build-with-builder.js runs this before electron-builder; scripts/afterPack.js checks the packaged app with
// the check this ends with (packages/shared-scripts/src/verify-bundled-harness.js).
import { spawnSync } from 'node:child_process';
import { lstatSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

const require = createRequire(import.meta.url);
const { verifyBundledHarness } = require('../../packages/shared-scripts/src/verify-bundled-harness.js');

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

/** The mu-agent version the app carries when no tarball is given. Pinned in one place: the root package.json. */
export function pinnedVersion(root = projectRoot) {
  const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).muAgentVersion;
  if (typeof version !== 'string' || !/^\d+\.\d+\.\d+(-[\w.]+)?$/.test(version)) {
    throw new Error('package.json has no "muAgentVersion": the version of mu-agent the app carries');
  }
  return version;
}

/** npm's arguments for mu-agent's own dependencies, for the system and processor the app is built for. */
export function installArgs(platform, arch) {
  return [
    'install',
    '--omit=dev',
    '--ignore-scripts',
    '--no-audit',
    '--no-fund',
    // Nothing of npm's own is left in the app: no lockfile, no node_modules/.bin links.
    '--no-package-lock',
    '--no-bin-links',
    // What npm has cached is used as it is: a build downloads a package once.
    '--prefer-offline',
    `--os=${platform}`,
    `--cpu=${arch}`,
  ];
}

/**
 * Unpacks an npm package tarball (every entry under `package/`) into `dest`. npm writes plain ustar entries, with a
 * pax or GNU header for a long path; a package has no links.
 */
export function extractPackage(tarball, dest) {
  const data = gunzipSync(readFileSync(tarball));
  let longName;
  for (let offset = 0; offset + 512 <= data.length; ) {
    const header = data.subarray(offset, offset + 512);
    if (header.every((byte) => byte === 0)) break;
    const field = (start, length) => {
      const raw = header.subarray(start, start + length);
      const end = raw.indexOf(0);
      return raw.subarray(0, end < 0 ? length : end).toString('utf8');
    };
    const size = Number.parseInt(field(124, 12).trim() || '0', 8);
    const type = header[156] === 0 ? '0' : String.fromCharCode(header[156]);
    const body = data.subarray(offset + 512, offset + 512 + size);
    offset += 512 + Math.ceil(size / 512) * 512;
    if (type === 'x') {
      longName = /(?:^|\n)\d+ path=([^\n]*)\n/.exec(body.toString('utf8'))?.[1] ?? longName;
      continue;
    }
    if (type === 'L') {
      longName = body.toString('utf8').replace(/\0+$/, '');
      continue;
    }
    if (type === 'g') continue;
    const prefix = field(345, 155);
    const name = longName ?? (prefix ? `${prefix}/${field(0, 100)}` : field(0, 100));
    longName = undefined;
    const parts = name.replace(/\/$/, '').split('/');
    if (parts[0] !== 'package' || parts.length < 2 || parts.some((part) => !part || part === '.' || part === '..')) {
      throw new Error(`${tarball} is not an npm package: it holds ${name}`);
    }
    const target = join(dest, ...parts.slice(1));
    if (type === '5') {
      mkdirSync(target, { recursive: true });
      continue;
    }
    if (type !== '0') throw new Error(`${tarball} holds ${name}, which is not a plain file`);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, body, { mode: Number.parseInt(field(100, 8).trim() || '644', 8) & 0o777 });
  }
}

/** npm, which is `npm.cmd` on Windows: that runs only through a shell, so there every argument is quoted. */
function runNpm(args, cwd) {
  const windows = process.platform === 'win32';
  const env = { ...process.env };
  // A surrounding npm or bun run must not turn this install into a global one or one of its workspaces.
  for (const name of ['prefix', 'global', 'location', 'workspace', 'workspaces']) delete env[`npm_config_${name}`];
  const result = spawnSync(windows ? 'npm.cmd' : 'npm', windows ? args.map((arg) => `"${arg}"`) : args, {
    cwd,
    env,
    encoding: 'utf8',
    shell: windows,
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  if (result.error) throw new Error(`npm did not start: ${result.error.message}`);
  if (result.status !== 0) throw new Error(`npm ${args[0]} failed (exit code ${result.status}): ${result.stdout}`);
  return result.stdout;
}

/** Files and bytes under a folder. */
function sizeOf(dir) {
  let files = 0;
  let bytes = 0;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      const inner = sizeOf(path);
      files += inner.files;
      bytes += inner.bytes;
    } else {
      files += 1;
      bytes += lstatSync(path).size;
    }
  }
  return { files, bytes };
}

/** Bundles mu into resources/harness and checks the result; throws when anything is missing. */
export function bundleHarness({
  root = projectRoot,
  platform = process.platform,
  arch = process.arch,
  tarball = process.env.MU_HARNESS_TARBALL,
  npm = runNpm,
  log = console.log,
} = {}) {
  const resources = join(root, 'resources');
  const harness = join(resources, 'harness');
  const target = join(harness, 'mu-agent');
  rmSync(harness, { recursive: true, force: true });
  mkdirSync(target, { recursive: true });
  const scratch = mkdtempSync(join(tmpdir(), 'mu-harness-'));
  try {
    let file = tarball ? resolve(tarball) : undefined;
    if (!file) {
      const version = pinnedVersion(root);
      log(`Getting mu-agent ${version} from the npm registry`);
      let packed;
      try {
        packed = JSON.parse(npm(['pack', `mu-agent@${version}`, '--json', '--pack-destination', scratch], scratch));
      } catch (error) {
        throw new Error(
          `mu-agent ${version} could not be fetched (${error instanceof Error ? error.message : String(error)}). ` +
            'Set MU_HARNESS_TARBALL to a tarball packed from the harness (node kyrn/npm/build.mjs --pack), or publish that version.'
        );
      }
      file = join(scratch, packed[0].filename);
    }
    extractPackage(file, target);
    const manifest = JSON.parse(readFileSync(join(target, 'package.json'), 'utf8'));
    if (manifest.name !== 'mu-agent') throw new Error(`${file} is ${manifest.name}, not mu-agent`);
    log(`Installing what mu-agent ${manifest.version} depends on, for ${platform}-${arch}`);
    npm(installArgs(platform, arch), target);
    const record = { name: manifest.name, version: manifest.version, platform, arch };
    writeFileSync(join(harness, 'bundle.json'), `${JSON.stringify(record, null, 2)}\n`);
    const check = verifyBundledHarness({ resourcesDir: resources, electronPlatformName: platform, targetArch: arch });
    if (check.missing.length > 0) throw new Error(`The bundled mu is incomplete: ${check.missing.join(', ')}`);
    const { files, bytes } = sizeOf(harness);
    log(
      `Bundled mu-agent ${manifest.version} for ${platform}-${arch} in resources/harness: ${files} files, ${(bytes / 1048576).toFixed(1)} MiB`
    );
    return { version: manifest.version, files, bytes };
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const option = (name) => {
    const index = process.argv.indexOf(`--${name}`);
    return index > 1 ? process.argv[index + 1] : undefined;
  };
  try {
    bundleHarness({ platform: option('platform'), arch: option('arch') });
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exit(1);
  }
}

/**
 * Prepare aioncore binary for packaging.
 *
 * Resolution order:
 *  1. GitHub Actions artifact download when AIONUI_BACKEND_RUN_ID is set
 *  2. GitHub release download (requires version or defaults to "latest")
 *  3. Complete local bundle from AIONUI_BACKEND_LOCAL_BUNDLE_DIR
 *  4. Local binary fallback from AIONUI_BACKEND_LOCAL_BINARY
 *
 * Output: {projectRoot}/resources/bundled-aioncore/{platform}-{arch}/
 *   - aioncore[.exe]
 *   - manifest.json
 *   - managed-resources/...
 *
 * @module prepare-aioncore
 */

const { execSync, execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { verifyBundledAioncoreResources } = require('./verify-bundled-aioncore-resources');

const GITHUB_OWNER = 'iOfficeAI';
const GITHUB_REPO = 'AionCore';

const ACTIONS_ARTIFACT_TARGETS = {
  'darwin-arm64': {
    artifactName: 'aioncore-manual-macos-arm64',
    manualPlatform: 'macos-arm64',
  },
  'darwin-x64': {
    artifactName: 'aioncore-manual-macos-x64',
    manualPlatform: 'macos-x64',
  },
  'linux-arm64': {
    artifactName: 'aioncore-manual-linux-arm64',
    manualPlatform: 'linux-arm64',
  },
  'linux-x64': {
    artifactName: 'aioncore-manual-linux-x64',
    manualPlatform: 'linux-x64',
  },
  'win32-arm64': {
    artifactName: 'aioncore-manual-windows-arm64',
    manualPlatform: 'windows-arm64',
  },
  'win32-x64': {
    artifactName: 'aioncore-manual-windows-x64',
    manualPlatform: 'windows-x64',
  },
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function ensureDirectory(dirPath) {
  if (!fs.existsSync(dirPath)) {
    fs.mkdirSync(dirPath, { recursive: true });
  }
}

function removeDirectorySafe(dirPath) {
  fs.rmSync(dirPath, { recursive: true, force: true });
}

function copyFileSafe(sourcePath, targetPath) {
  ensureDirectory(path.dirname(targetPath));
  fs.copyFileSync(sourcePath, targetPath);
}

function copyDirectorySafe(sourcePath, targetPath) {
  ensureDirectory(path.dirname(targetPath));
  // Links stay as they are: Node's own bin/npm and bin/npx are relative links, which cpSync would otherwise turn into
  // absolute ones into the source folder, broken on every other machine (and refused by a strict codesign check).
  fs.cpSync(sourcePath, targetPath, { recursive: true, force: true, verbatimSymlinks: true });
}

function ensureExecutableMode(filePath) {
  if (process.platform === 'win32') return;
  try {
    fs.chmodSync(filePath, 0o755);
  } catch {}
}

function writeJson(filePath, payload) {
  fs.writeFileSync(filePath, JSON.stringify(payload, null, 2) + '\n', 'utf-8');
}

// Entries that ship inside a bundled Node.js distribution but are package-manager
// tooling we never use at runtime — the agent kernel only needs node.exe. They
// account for ~75% of the installed file count, which is what makes the NSIS
// unpack step slow, so we strip them from the packaged bundle. Safe: the
// managed-resources contract (verify-bundled-aioncore-resources) only ever
// requires node.exe + a valid manifest, never npm/npx/corepack.
const NODE_PACKAGE_MANAGER_ENTRIES = [
  'npm', 'npm.cmd', 'npm.ps1',
  'npx', 'npx.cmd', 'npx.ps1',
  'corepack', 'corepack.cmd',
  'node_modules',
  'blank_global_npmrc', 'blank_user_npmrc',
  'install_tools.bat', 'nodevars.bat',
];

function listFilesRecursive(dir) {
  const out = [];
  const stack = [dir];
  while (stack.length) {
    const cur = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(cur, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(cur, entry.name);
      if (entry.isDirectory()) stack.push(full);
      else out.push(full);
    }
  }
  return out;
}

function pruneManagedNodePackageManagers(managedResourcesDir, runtimeKey) {
  if (process.env.AIONUI_KEEP_NODE_PACKAGE_MANAGERS === '1') {
    console.log('  Skipping node package-manager prune (AIONUI_KEEP_NODE_PACKAGE_MANAGERS=1)');
    return;
  }

  // Locate the node root from the contract so we never hard-code a version.
  const contractPath = path.join(managedResourcesDir, 'manifest.json');
  let nodeRoot = null;
  try {
    const contract = JSON.parse(fs.readFileSync(contractPath, 'utf8'));
    if (contract && contract.node && typeof contract.node.root === 'string') {
      nodeRoot = contract.node.root;
    }
  } catch {}
  if (!nodeRoot) {
    console.warn('  Skipping node package-manager prune: managed-resources contract missing node.root');
    return;
  }

  const nodeDir = path.join(managedResourcesDir, nodeRoot);
  if (!fs.existsSync(nodeDir)) {
    console.warn(`  Skipping node package-manager prune: node root not found at ${nodeRoot}`);
    return;
  }

  let removedFiles = 0;
  let freedBytes = 0;
  for (const entry of NODE_PACKAGE_MANAGER_ENTRIES) {
    const target = path.join(nodeDir, entry);
    if (!fs.existsSync(target)) continue;
    try {
      const stat = fs.statSync(target);
      if (stat.isDirectory()) {
        const files = listFilesRecursive(target);
        for (const f of files) {
          try {
            freedBytes += fs.statSync(f).size;
          } catch {}
        }
        removedFiles += files.length;
      } else {
        freedBytes += stat.size;
        removedFiles += 1;
      }
      fs.rmSync(target, { recursive: true, force: true });
    } catch (err) {
      console.warn(`  Failed to prune ${entry}: ${err.message}`);
    }
  }

  if (removedFiles > 0) {
    console.log(
      `  Pruned ${removedFiles} npm/corepack file(s) from ${nodeRoot} (freed ~${(freedBytes / 1e6).toFixed(1)} MB)`
    );
  }
}

function getBinaryName(platform) {
  return platform === 'win32' ? 'aioncore.exe' : 'aioncore';
}

function getActionsTarget(platform, arch) {
  return ACTIONS_ARTIFACT_TARGETS[`${platform}-${arch}`] || null;
}

function getActionsArtifactName(platform, arch) {
  return getActionsTarget(platform, arch)?.artifactName || null;
}

function getActionsManualPlatform(platform, arch) {
  return getActionsTarget(platform, arch)?.manualPlatform || `${platform}-${arch}`;
}

function getActionsArtifactMissingMessage({ runId, platform, arch, expectedArtifactName, availableArtifactNames }) {
  const available =
    Array.isArray(availableArtifactNames) && availableArtifactNames.length > 0
      ? availableArtifactNames.join(', ')
      : '(none)';
  return [
    `AionCore run ${runId} does not contain artifact [ ${expectedArtifactName} ] required for [ ${platform}-${arch} ].`,
    `Available artifacts: ${available}.`,
    `Re-run AionCore Manual Build with platform [ ${getActionsManualPlatform(platform, arch)} ] or all.`,
  ].join(' ');
}

function prepareManagedResources(binaryPath, targetDir) {
  const bundleOut = path.join(targetDir, 'managed-resources');
  const dataDir = path.join(targetDir, '.prepare-data');

  removeDirectorySafe(bundleOut);
  removeDirectorySafe(dataDir);
  ensureDirectory(bundleOut);
  ensureDirectory(dataDir);

  console.log(`  Preparing managed resources under ${path.relative(process.cwd(), bundleOut)}`);
  execFileSync(binaryPath, ['--data-dir', dataDir, 'prepare-managed-resources', '--bundle-out', bundleOut], {
    stdio: 'inherit',
    env: {
      ...process.env,
      AIONUI_BUNDLED_MANAGED_RESOURCES: '',
    },
  });

  removeDirectorySafe(dataDir);
  return bundleOut;
}

function verifyPreparedAioncoreBundle(projectRoot, platform, arch) {
  const result = verifyBundledAioncoreResources({
    resourcesDir: path.join(projectRoot, 'resources'),
    electronPlatformName: platform,
    targetArch: arch,
  });
  if (result.missing.length > 0 || result.failures.length > 0) {
    const summary = result.missing.length > 0 ? result.missing.join(', ') : JSON.stringify(result.failures);
    throw new Error(`Prepared aioncore bundle is missing required bundled resource(s): ${summary}`);
  }
  return result;
}

// ---------------------------------------------------------------------------
// Source resolvers
// ---------------------------------------------------------------------------

/**
 * Resolve the actual version tag when "latest" is requested.
 * Uses GitHub API via `gh` CLI (needs GH_TOKEN in CI) or falls back to
 * `curl` with an optional Authorization header (GITHUB_TOKEN / GH_TOKEN).
 */
function resolveLatestTag() {
  const token = process.env.GH_TOKEN || process.env.GITHUB_TOKEN || '';

  // 1. Try gh CLI (honours GH_TOKEN automatically)
  try {
    const out = execSync(`gh api repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases/latest --jq .tag_name`, {
      encoding: 'utf-8',
      timeout: 15000,
    }).trim();
    if (out) return out;
  } catch {
    // gh CLI not available or no token — fall back to curl
  }

  // 2. Curl with optional token to avoid rate-limit 403
  try {
    const authArgs = token ? ['-H', `Authorization: token ${token}`] : [];
    const args = ['-fsSL', ...authArgs, `https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases/latest`];
    const out = execFileSync('curl', args, { encoding: 'utf-8', timeout: 15000 });
    const tag = JSON.parse(out).tag_name;
    if (tag) return tag;
  } catch {
    // network issue or rate-limited
  }

  return null;
}

/**
 * Build the release asset filename for the given platform/arch/tag.
 *
 * Expected asset naming convention:
 *   aioncore-v0.1.0-aarch64-apple-darwin.tar.gz
 */
function getAssetName(platform, arch, tag) {
  const archMap = { x64: 'x86_64', arm64: 'aarch64' };
  const platformMap = {
    darwin: 'apple-darwin',
    linux: 'unknown-linux-gnu',
    win32: 'pc-windows-msvc',
  };
  const normalizedArch = archMap[arch];
  const normalizedPlatform = platformMap[platform];
  if (!normalizedArch || !normalizedPlatform) return null;
  const ext = platform === 'win32' ? '.zip' : '.tar.gz';
  return `aioncore-${tag}-${normalizedArch}-${normalizedPlatform}${ext}`;
}

function getDownloadUrl(assetName, tag) {
  return `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}/releases/download/${tag}/${assetName}`;
}

function downloadFile(url, outputPath) {
  console.log(`  Downloading aioncore from ${url}`);
  if (process.platform === 'win32') {
    const ps = `$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '${url}' -OutFile '${outputPath.replace(/'/g, "''")}'`;
    execFileSync('powershell', ['-NoProfile', '-NonInteractive', '-Command', ps], {
      timeout: 120000,
    });
    return;
  }
  try {
    execFileSync('curl', ['-L', '--fail', '--silent', '--show-error', '-o', outputPath, url], { timeout: 120000 });
  } catch {
    execFileSync('wget', ['-q', '-O', outputPath, url], { timeout: 120000 });
  }
}

function extractArchive(archivePath, outputDir, platform) {
  ensureDirectory(outputDir);
  if (platform === 'win32' || archivePath.endsWith('.zip')) {
    if (platform === 'win32') {
      const ps = `Expand-Archive -LiteralPath '${archivePath.replace(/'/g, "''")}' -DestinationPath '${outputDir.replace(/'/g, "''")}' -Force`;
      execFileSync('powershell', ['-NoProfile', '-NonInteractive', '-Command', ps]);
    } else {
      execFileSync('unzip', ['-o', archivePath, '-d', outputDir]);
    }
  } else {
    execFileSync('tar', ['-xzf', archivePath, '-C', outputDir]);
  }
}

function findBinaryInDir(dir, binaryName) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isFile() && entry.name === binaryName) return fullPath;
    if (entry.isDirectory()) {
      const found = findBinaryInDir(fullPath, binaryName);
      if (found) return found;
    }
  }
  return null;
}

function findAioncoreArchiveInDir(dir) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (
      entry.isFile() &&
      entry.name.startsWith('aioncore-') &&
      (entry.name.endsWith('.zip') || entry.name.endsWith('.tar.gz'))
    ) {
      return fullPath;
    }
    if (entry.isDirectory()) {
      const found = findAioncoreArchiveInDir(fullPath);
      if (found) return found;
    }
  }
  return null;
}

function getGitHubToken() {
  return process.env.GH_TOKEN || process.env.GITHUB_TOKEN || '';
}

function githubApiGetJson(apiPath) {
  const token = getGitHubToken();

  try {
    return JSON.parse(
      execFileSync('gh', ['api', apiPath], {
        encoding: 'utf-8',
        timeout: 15000,
        env: {
          ...process.env,
          GH_TOKEN: token || process.env.GH_TOKEN,
        },
      })
    );
  } catch {
    // gh CLI not available or failed — fall back to curl.
  }

  const headers = ['-H', 'Accept: application/vnd.github+json'];
  if (token) {
    headers.push('-H', `Authorization: Bearer ${token}`);
  }

  const url = `https://api.github.com/${apiPath}`;
  const out = execFileSync('curl', ['-fsSL', ...headers, url], {
    encoding: 'utf-8',
    timeout: 15000,
  });
  return JSON.parse(out);
}

function downloadFileWithAuth(url, outputPath) {
  const token = getGitHubToken();
  const headers = ['-H', 'Accept: application/vnd.github+json'];
  if (token) {
    headers.push('-H', `Authorization: Bearer ${token}`);
  }

  try {
    execFileSync('curl', ['-L', '--fail', '--silent', '--show-error', ...headers, '-o', outputPath, url], {
      timeout: 120000,
    });
    return;
  } catch {
    // curl may be unavailable in some local environments; try gh before failing.
  }

  execFileSync('gh', ['api', url, '--output', outputPath], {
    timeout: 120000,
    env: {
      ...process.env,
      GH_TOKEN: token || process.env.GH_TOKEN,
    },
  });
}

function listActionsArtifacts(runId) {
  const response = githubApiGetJson(
    `repos/${GITHUB_OWNER}/${GITHUB_REPO}/actions/runs/${runId}/artifacts?per_page=100`
  );
  return Array.isArray(response?.artifacts) ? response.artifacts : [];
}

function downloadAndExtractActionsArtifact(platform, arch, runId) {
  const expectedArtifactName = getActionsArtifactName(platform, arch);
  if (!expectedArtifactName) {
    throw new Error(`Unsupported AionCore Actions artifact target: ${platform}-${arch}`);
  }

  const artifacts = listActionsArtifacts(runId);
  const availableArtifactNames = artifacts
    .map((artifact) => artifact.name)
    .filter(Boolean)
    .toSorted();
  const artifact = artifacts.find((candidate) => candidate.name === expectedArtifactName);
  if (!artifact) {
    throw new Error(
      getActionsArtifactMissingMessage({
        runId,
        platform,
        arch,
        expectedArtifactName,
        availableArtifactNames,
      })
    );
  }

  const tempDir = path.join(os.tmpdir(), 'aioncore-prepare-actions', runId, `${platform}-${arch}`);
  const artifactZipPath = path.join(tempDir, `${expectedArtifactName}.zip`);
  const artifactExtractDir = path.join(tempDir, 'artifact');
  const binaryExtractDir = path.join(tempDir, 'binary');

  removeDirectorySafe(tempDir);
  ensureDirectory(tempDir);

  const downloadUrl =
    artifact.archive_download_url ||
    `https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/actions/artifacts/${artifact.id}/zip`;
  console.log(`  Downloading aioncore from AionCore run ${runId} artifact ${expectedArtifactName}`);
  downloadFileWithAuth(downloadUrl, artifactZipPath);
  extractArchive(artifactZipPath, artifactExtractDir, platform);

  const archivePath = findAioncoreArchiveInDir(artifactExtractDir);
  if (!archivePath) {
    throw new Error(`AionCore artifact ${expectedArtifactName} from run ${runId} does not contain an aioncore archive`);
  }

  extractArchive(archivePath, binaryExtractDir, platform);

  const binaryName = getBinaryName(platform);
  const binaryPath = findBinaryInDir(binaryExtractDir, binaryName);
  if (!binaryPath) {
    throw new Error(`Binary ${binaryName} not found in AionCore artifact ${expectedArtifactName} from run ${runId}`);
  }

  return {
    binaryPath,
    tempDir,
    artifactName: expectedArtifactName,
    archivePath,
    url: downloadUrl,
  };
}

function downloadAndExtract(platform, arch, tag) {
  const assetName = getAssetName(platform, arch, tag);
  if (!assetName) {
    throw new Error(`Unsupported aioncore target: ${platform}-${arch}`);
  }

  const url = getDownloadUrl(assetName, tag);
  const tempDir = path.join(os.tmpdir(), 'aioncore-prepare', tag, `${platform}-${arch}`);
  const archivePath = path.join(tempDir, assetName);
  const extractDir = path.join(tempDir, 'extracted');

  removeDirectorySafe(tempDir);
  ensureDirectory(tempDir);

  downloadFile(url, archivePath);
  extractArchive(archivePath, extractDir, platform);

  const binaryName = getBinaryName(platform);
  const binaryPath = findBinaryInDir(extractDir, binaryName);
  if (!binaryPath) {
    throw new Error(`Binary ${binaryName} not found in downloaded archive`);
  }

  return { binaryPath, tempDir, url };
}

// ---------------------------------------------------------------------------
// Main export
// ---------------------------------------------------------------------------

/**
 * Prepare aioncore binary for packaging.
 *
 * @param {object} options - Configuration options
 * @param {string} options.projectRoot - Project root directory
 * @param {string} options.platform - Target platform (process.platform)
 * @param {string} options.arch - Target architecture (process.arch)
 * @param {string} options.version - Backend version (default: 'latest')
 * @returns {{ prepared: true; dir: string; sourceType: string }}
 */
function prepareAioncore(options) {
  const { projectRoot, platform, arch, version = 'latest' } = options;
  const runtimeKey = `${platform}-${arch}`;
  const actionsRunId = (process.env.AIONUI_BACKEND_RUN_ID || '').trim();

  let tag = null;
  if (!actionsRunId) {
    // Resolve the actual version tag — release asset filenames include the tag.
    if (version === 'latest') {
      const resolved = resolveLatestTag();
      if (!resolved) {
        throw new Error('Failed to resolve latest aioncore release tag from GitHub API');
      }
      tag = resolved;
      console.log(`Resolved aioncore "latest" → ${tag}`);
    } else {
      tag = version.startsWith('v') ? version : `v${version}`;
    }
  }

  const targetDir = path.join(projectRoot, 'resources', 'bundled-aioncore', runtimeKey);
  const binaryName = getBinaryName(platform);
  const targetBinaryPath = path.join(targetDir, binaryName);

  console.log(
    `Preparing aioncore for ${runtimeKey} (${actionsRunId ? `actions run: ${actionsRunId}` : `version: ${tag}`})`
  );

  removeDirectorySafe(targetDir);
  ensureDirectory(targetDir);

  const localBundleDir = (process.env.AIONUI_BACKEND_LOCAL_BUNDLE_DIR || '').trim();
  if (localBundleDir) {
    const resolvedLocalBundleDir = path.resolve(localBundleDir);
    const localBinaryPath = path.join(resolvedLocalBundleDir, binaryName);
    const localManagedResourcesDir = path.join(resolvedLocalBundleDir, 'managed-resources');
    if (
      fs.existsSync(resolvedLocalBundleDir) &&
      fs.statSync(resolvedLocalBundleDir).isDirectory() &&
      fs.existsSync(localBinaryPath) &&
      fs.existsSync(localManagedResourcesDir)
    ) {
      copyDirectorySafe(resolvedLocalBundleDir, targetDir);
      pruneManagedNodePackageManagers(path.join(targetDir, 'managed-resources'), runtimeKey);
      ensureExecutableMode(targetBinaryPath);
      const manifest = {
        platform,
        arch,
        version: tag || `actions-run-${actionsRunId}` || 'local-bundle',
        generatedAt: new Date().toISOString(),
        sourceType: 'local-bundle',
        source: { path: resolvedLocalBundleDir },
        files: [binaryName, 'managed-resources/'],
      };
      writeJson(path.join(targetDir, 'manifest.json'), manifest);
      verifyPreparedAioncoreBundle(projectRoot, platform, arch);
      console.log(`  Using local aioncore bundle: ${resolvedLocalBundleDir}`);
      return { prepared: true, dir: targetDir, sourceType: 'local-bundle' };
    }
    console.warn(`  Local aioncore bundle is incomplete or missing: ${resolvedLocalBundleDir}`);
  }

  let sourcePath = null;
  let sourceType = 'none';
  let sourceDetail = {};
  let tempDir = null;

  // 1. Download from GitHub Actions artifacts when manual build run id is provided.
  if (actionsRunId) {
    const result = downloadAndExtractActionsArtifact(platform, arch, actionsRunId);
    sourcePath = result.binaryPath;
    tempDir = result.tempDir;
    sourceType = 'actions-artifact';
    sourceDetail = {
      runId: actionsRunId,
      artifactName: result.artifactName,
      url: result.url,
    };
    console.log(`  Downloaded from GitHub Actions artifact`);
  }

  // 2. Download from GitHub releases.
  if (!sourcePath && tag) {
    try {
      const result = downloadAndExtract(platform, arch, tag);
      sourcePath = result.binaryPath;
      tempDir = result.tempDir;
      sourceType = 'download';
      sourceDetail = { url: result.url };
      console.log(`  Downloaded from GitHub releases`);
    } catch (error) {
      console.warn(`  Download failed: ${error.message}`);
    }
  }

  // 3. Use an explicitly supplied local cache when network download is unavailable.
  if (!sourcePath) {
    const localBinary = (process.env.AIONUI_BACKEND_LOCAL_BINARY || '').trim();
    if (localBinary) {
      const resolvedLocalBinary = path.resolve(localBinary);
      if (fs.existsSync(resolvedLocalBinary) && fs.statSync(resolvedLocalBinary).isFile()) {
        sourcePath = resolvedLocalBinary;
        sourceType = 'local-binary';
        sourceDetail = { path: resolvedLocalBinary };
        console.log(`  Using local aioncore binary: ${resolvedLocalBinary}`);
      } else {
        console.warn(`  Local aioncore binary not found: ${resolvedLocalBinary}`);
      }
    }
  }

  // Write result
  if (sourcePath) {
    copyFileSafe(sourcePath, targetBinaryPath);
    ensureExecutableMode(targetBinaryPath);
    const bundledManagedResourcesDir = prepareManagedResources(targetBinaryPath, targetDir);
    pruneManagedNodePackageManagers(bundledManagedResourcesDir, runtimeKey);

    // The release tag is the authoritative version — the aioncore
    // binary does not expose a --version flag (it has --app-version which
    // takes a value, not a self-report).
    const manifest = {
      platform,
      arch,
      version: tag || `actions-run-${actionsRunId}`,
      generatedAt: new Date().toISOString(),
      sourceType,
      source: sourceDetail,
      files: [binaryName, 'managed-resources/'],
    };

    writeJson(path.join(targetDir, 'manifest.json'), manifest);
    verifyPreparedAioncoreBundle(projectRoot, platform, arch);
    console.log(
      `  Bundled aioncore prepared: resources/bundled-aioncore/${runtimeKey}/${binaryName} [source=${sourceType}]`
    );
    console.log(`  Bundled managed resources prepared: ${bundledManagedResourcesDir}`);

    if (tempDir) removeDirectorySafe(tempDir);
    return { prepared: true, dir: targetDir, sourceType };
  }

  throw new Error(`aioncore binary not found for ${runtimeKey} (tag: ${tag})`);
}

module.exports = {
  getActionsArtifactMissingMessage,
  getActionsArtifactName,
  prepareAioncore,
  pruneManagedNodePackageManagers,
  verifyPreparedAioncoreBundle,
};

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const cwd = 'c:\\code\\safeopcAPP';
const logFile = path.join(cwd, 'commit_result.txt');

function log(msg) {
  fs.appendFileSync(logFile, msg + '\n', 'utf8');
}

// Reset log file
fs.writeFileSync(logFile, '', 'utf8');

try {
  // Stage files
  const files = [
    'CLAUDE.md',
    'src/web-ui/src/web-ui/src/app/components/SceneBar/SceneBar.scss',
    'src/web-ui/src/web-ui/src/app/components/SceneBar/SceneBar.tsx',
    'src/web-ui/src/web-ui/src/app/App.tsx',
    'src/web-ui/src/web-ui/src/infrastructure/api/tupai/device.ts',
    'src/web-ui/src/web-ui/src/infrastructure/api/tupai/device.test.ts',
    'src/web-ui/src/web-ui/src/app/scenes/skills/TupaiSkillsScene.tsx'
  ];
  log('=== Staging files ===');
  for (const f of files) {
    try {
      execSync(`git add "${f}"`, { cwd, stdio: 'pipe' });
      log(`staged: ${f}`);
    } catch (e) {
      log(`FAILED to stage ${f}: ${e.message}`);
    }
  }

  // Write commit message
  const commitMsg = `fix: brand display in SceneBar + solidify plugin config lessons

- Move brand loading logic from WelcomeScene (dead code, not in SCENE_TAB_REGISTRY) to SceneBar (always visible 32px top bar). Brand name + website load via MCP tenantInfo -> local BrandInfo -> fallback ('tupai' / https://safeopc.cn). Click invokes open_external to open in system browser.
- Add SCSS styles for .bitfun-scene-bar__brand / __brand-name / __brand-ext.
- Document tauri.conf.json plugin config rules in CLAUDE.md: unit-type plugins (store/global-shortcut/os/process/clipboard-manager/opener) must OMIT the key, not write empty {} - {} triggers 'invalid type: map, expected unit' at runtime.
- Document global UI element mounting rule in CLAUDE.md: always-visible UI must be in SceneBar/NavPanel/AppLayout, not in scenes that may be removed from SCENE_TAB_REGISTRY.
- Device fingerprint: refactor ensureDeviceToken to fingerprint + MCP verify (covers reinstall/upgrade auto-registration).`;
  const msgFile = path.join(cwd, '.git', 'COMMIT_MSG.txt');
  fs.writeFileSync(msgFile, commitMsg, 'utf8');

  // Commit
  log('=== Committing ===');
  try {
    const out = execSync('git commit -F .git/COMMIT_MSG.txt', { cwd, stdio: 'pipe' });
    log('commit output: ' + out.toString('utf8'));
  } catch (e) {
    log('commit failed: ' + (e.stdout ? e.stdout.toString('utf8') : '') + (e.stderr ? e.stderr.toString('utf8') : '') + e.message);
  }

  // Push
  log('=== Pushing ===');
  try {
    const out = execSync('git push origin v2', { cwd, stdio: 'pipe' });
    log('push output: ' + out.toString('utf8'));
  } catch (e) {
    log('push failed: ' + (e.stdout ? e.stdout.toString('utf8') : '') + (e.stderr ? e.stderr.toString('utf8') : '') + e.message);
  }

  // Verify
  log('=== Verification ===');
  log('HEAD: ' + execSync('git rev-parse HEAD', { cwd, stdio: 'pipe' }).toString('utf8').trim());
  log('origin/v2: ' + execSync('git rev-parse origin/v2', { cwd, stdio: 'pipe' }).toString('utf8').trim());
  log('status: ' + execSync('git status --porcelain', { cwd, stdio: 'pipe' }).toString('utf8').trim() || 'clean');
} catch (e) {
  log('FATAL: ' + e.message);
}

#!/usr/bin/env node
/**
 * CI Status Check — 本地预检查脚本
 *
 * 在 push 前本地运行 CI 验证的关键步骤，减少 CI 失败率。
 * 用法:
 *   node scripts/ci-status-check.cjs          # 运行全部检查
 *   node scripts/ci-status-check.cjs --quick   # 仅运行快速检查（typecheck + i18n）
 *   node scripts/ci-status-check.cjs --skills  # 仅检查 skills
 *
 * 检查项:
 *   1. TypeScript typecheck（阻断性）
 *   2. i18n locale JSON 合法性（阻断性）
 *   3. Skills SKILL.md frontmatter（阻断性）
 *   4. manifest.json 合法性（阻断性）
 */

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const WEB_UI = path.join(ROOT, 'src/web-ui/src/web-ui');
const SKILLS_DIR = path.join(ROOT, 'skills');

const COLORS = {
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  cyan: '\x1b[36m',
  gray: '\x1b[90m',
  reset: '\x1b[0m',
  bold: '\x1b[1m',
};

let errors = 0;
let warnings = 0;

function log(msg, color = '') {
  console.log(`${color}${msg}${COLORS.reset}`);
}

function ok(msg) {
  console.log(`  ${COLORS.green}✅${COLORS.reset} ${msg}`);
}

function fail(msg) {
  console.log(`  ${COLORS.red}❌${COLORS.reset} ${msg}`);
  errors++;
}

function warn(msg) {
  console.log(`  ${COLORS.yellow}⚠️${COLORS.reset} ${msg}`);
  warnings++;
}

function section(title) {
  console.log(`\n${COLORS.bold}${COLORS.cyan}── ${title} ──${COLORS.reset}\n`);
}

// ── 1. TypeScript typecheck ──
function checkTypecheck() {
  section('TypeScript Typecheck (blocking)');

  const tsconfig = path.join(WEB_UI, 'tsconfig.json');
  if (!fs.existsSync(tsconfig)) {
    warn('tsconfig.json not found, skipping typecheck');
    return;
  }

  try {
    execSync('npx tsc --noEmit', {
      cwd: WEB_UI,
      stdio: 'pipe',
      encoding: 'utf8',
    });
    ok('TypeScript typecheck passed');
  } catch (e) {
    fail('TypeScript typecheck failed');
    const output = e.stdout || e.stderr || e.message;
    const lines = output.split('\n').filter(l => l.trim()).slice(0, 20);
    for (const line of lines) {
      console.log(`    ${COLORS.gray}${line}${COLORS.reset}`);
    }
    if (lines.length > 0) {
      console.log(`    ${COLORS.gray}... (showing first 20 lines)${COLORS.reset}`);
    }
  }
}

// ── 2. i18n locale JSON ──
function checkI18n() {
  section('i18n Locale JSON (blocking)');

  let localeDir = path.join(WEB_UI, 'src/infrastructure/i18n/locales');
  if (!fs.existsSync(localeDir)) {
    localeDir = path.join(WEB_UI, 'public/locales');
  }

  if (!fs.existsSync(localeDir)) {
    warn('Locale directory not found, skipping i18n check');
    return;
  }

  const files = [];
  function findJson(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const fullPath = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        findJson(fullPath);
      } else if (entry.name.endsWith('.json')) {
        files.push(fullPath);
      }
    }
  }
  findJson(localeDir);

  if (files.length === 0) {
    warn('No locale JSON files found');
    return;
  }

  for (const file of files) {
    try {
      JSON.parse(fs.readFileSync(file, 'utf8'));
      ok(path.relative(ROOT, file));
    } catch (e) {
      fail(`${path.relative(ROOT, file)} — invalid JSON: ${e.message}`);
    }
  }
}

// ── 3. Skills SKILL.md frontmatter ──
function checkSkills() {
  section('Skills SKILL.md (blocking)');

  if (!fs.existsSync(SKILLS_DIR)) {
    warn('Skills directory not found, skipping');
    return;
  }

  // ── manifest.json ──
  const manifestPath = path.join(SKILLS_DIR, 'manifest.json');
  let manifest = null;
  if (fs.existsSync(manifestPath)) {
    try {
      manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
      ok('manifest.json — valid JSON');
    } catch (e) {
      fail(`manifest.json — invalid JSON: ${e.message}`);
    }

    if (manifest && Array.isArray(manifest.skills)) {
      for (const skill of manifest.skills) {
        const missing = [];
        if (!skill.id) missing.push('id');
        if (!skill.name) missing.push('name');
        if (!skill.version) missing.push('version');
        if (!skill.file) missing.push('file');

        if (missing.length > 0) {
          fail(`manifest entry "${skill.id || skill.name || 'unknown'}" missing: ${missing.join(', ')}`);
        } else {
          const filePath = path.join(SKILLS_DIR, skill.file);
          if (!fs.existsSync(filePath)) {
            fail(`file not found: ${skill.file} (skill: ${skill.id})`);
          } else {
            ok(`${skill.id} — manifest entry valid`);
          }
        }
      }
    }
  } else {
    warn('manifest.json not found');
  }

  // ── Each skill directory ──
  for (const entry of fs.readdirSync(SKILLS_DIR, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    if (entry.name === '_template') continue;

    const skillMd = path.join(SKILLS_DIR, entry.name, 'SKILL.md');
    if (!fs.existsSync(skillMd)) {
      fail(`${entry.name} — SKILL.md not found`);
      continue;
    }

    const content = fs.readFileSync(skillMd, 'utf8');
    const firstLine = content.split('\n')[0].trim();

    if (firstLine !== '---') {
      fail(`${entry.name} — SKILL.md missing YAML frontmatter (must start with ---)`);
      continue;
    }

    // Extract frontmatter (trim each line for CRLF compatibility)
    const lines = content.split('\n').map(l => l.trim());
    const fmEnd = lines.indexOf('---', 1);
    if (fmEnd === -1) {
      fail(`${entry.name} — SKILL.md frontmatter not closed (missing closing ---)`);
      continue;
    }

    const frontmatter = lines.slice(1, fmEnd).join('\n');
    const hasId = /^id:/m.test(frontmatter);
    const hasName = /^name:/m.test(frontmatter);
    const hasVersion = /^version:/m.test(frontmatter);

    const missing = [];
    if (!hasId) missing.push('id');
    if (!hasName) missing.push('name');
    if (!hasVersion) missing.push('version');

    if (missing.length > 0) {
      fail(`${entry.name} — frontmatter missing: ${missing.join(', ')}`);
    } else {
      const size = fs.statSync(skillMd).size;
      ok(`${entry.name} — SKILL.md valid (${size} bytes)`);
    }
  }
}

// ── Main ──
function main() {
  const args = process.argv.slice(2);
  const quick = args.includes('--quick');
  const skillsOnly = args.includes('--skills');
  const typecheckOnly = args.includes('--typecheck');

  console.log(`${COLORS.bold}${COLORS.cyan}🔍 CI Pre-Check${COLORS.reset}`);
  console.log(`${COLORS.gray}Running local validation before push...${COLORS.reset}`);

  if (skillsOnly) {
    checkSkills();
  } else if (typecheckOnly) {
    checkTypecheck();
  } else {
    checkTypecheck();
    checkI18n();
    if (!quick) {
      checkSkills();
    }
  }

  // Summary
  console.log(`\n${COLORS.bold}── Summary ──${COLORS.reset}\n`);
  if (errors === 0 && warnings === 0) {
    log('✅ All checks passed — safe to push!', COLORS.green);
    process.exit(0);
  } else if (errors === 0) {
    log(`⚠️ ${warnings} warning(s), 0 errors — safe to push`, COLORS.yellow);
    process.exit(0);
  } else {
    log(`❌ ${errors} error(s), ${warnings} warning(s) — fix errors before pushing!`, COLORS.red);
    process.exit(1);
  }
}

main();

import { describe, expect, it } from 'vitest';
import {
  repairedCmdLauncher,
  repairedShLauncher,
} from '../../../../../packages/desktop/src/process/agent/kyrn/launcherRepair';

const cmdLauncher = [
  '@echo off',
  "rem Keep this file ASCII: cmd reads it in the console's code page.",
  'if defined MU_NODE (',
  '  "%MU_NODE%" "%~dp0..\\app.asar.unpacked\\out\\main\\mu-acp.js" %*',
  ') else (',
  '  set ELECTRON_RUN_AS_NODE=1',
  '  "%~dp0..\\..\\AiBuddy.exe" "%~dp0..\\app.asar.unpacked\\out\\main\\mu-acp.js" %*',
  ')',
  'exit /b %errorlevel%',
].join('\n');

const shLauncher = [
  '#!/bin/sh',
  'here=$(cd "$(dirname "$0")" && pwd)',
  'adapter="$here/../app.asar.unpacked/out/main/mu-acp.js"',
  'case "$(uname -s)" in',
  '  Darwin) app="$here/../../MacOS/AiBuddy" ;;',
  '  *) app="$here/../../AiBuddy" ;;',
  'esac',
  'exec "$app" "$adapter" "$@"',
].join('\n');

describe('repairedCmdLauncher', () => {
  it('keeps a launcher that already names the running binary', () => {
    expect(repairedCmdLauncher(cmdLauncher, 'AiBuddy.exe')).toBeUndefined();
  });

  it('rewrites a launcher left by a build with a different executable name', () => {
    const stale = cmdLauncher.replace('AiBuddy.exe', 'mu.exe');
    const repaired = repairedCmdLauncher(stale, 'AiBuddy.exe');
    expect(repaired).toContain('%~dp0..\\..\\AiBuddy.exe"');
    expect(repaired).not.toContain('mu.exe"');
    // The adapter path and the MU_NODE branch are untouched.
    expect(repaired).toContain('%~dp0..\\app.asar.unpacked\\out\\main\\mu-acp.js');
    expect(repaired).toContain('"%MU_NODE%"');
  });
});

describe('repairedShLauncher', () => {
  it('keeps a launcher that already names the running binary', () => {
    expect(repairedShLauncher(shLauncher, 'AiBuddy')).toBeUndefined();
  });

  it('rewrites both case branches after a rename', () => {
    const stale = shLauncher.replace('MacOS/AiBuddy', 'MacOS/mu').replace('/../../AiBuddy"', '/../../mu-desktop"');
    const repaired = repairedShLauncher(stale, 'AiBuddy');
    expect(repaired).toContain('app="$here/../../MacOS/AiBuddy"');
    expect(repaired).toContain('app="$here/../../AiBuddy"');
    expect(repaired).not.toContain('mu-desktop');
  });
});

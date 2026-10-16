import { readFileSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';

/**
 * The packaged launchers (resources/mu/acp.cmd, resources/mu/acp) run the app's own binary as Node by a
 * relative name. That name is a build constant (productName/executableName), and renaming it left every
 * already-written launcher pointing at an exe that no longer exists — the backend's health check on the
 * agent then reports the kernel offline and the app refuses to start. Before the registration is written,
 * check that the exe the launcher names is the one running right now, and rewrite the launcher when it is
 * not. Best effort by design: the launcher shipped in the package is correct for its own build, and a
 * read-only install directory must not keep the kernel from starting.
 */

/** The app binary in the Windows launcher, e.g. `"%~dp0..\..\AiBuddy.exe"` (the adapter is a .js, not matched). */
const CMD_EXE = /("%~dp0(?:\.\.\\)+)([\w.-]+\.exe)"/;
/** The app binary in the POSIX launcher, both case branches: `app="$here/../../MacOS/AiBuddy"`. */
const SH_EXE = /(app="\$here\/[^"]*\/)([\w.-]+)"/g;

/** The launcher content rewritten for `exeName`, or undefined when it already names that binary. */
export function repairedCmdLauncher(content: string, exeName: string): string | undefined {
  const match = CMD_EXE.exec(content);
  if (!match || match[2] === exeName) return undefined;
  return content.replace(CMD_EXE, `$1${exeName}"`);
}

export function repairedShLauncher(content: string, exeName: string): string | undefined {
  const match = SH_EXE.exec(content);
  if (!match || match[2] === exeName) return undefined;
  return content.replace(SH_EXE, `$1${exeName}"`);
}

/** Point the packaged launcher of this platform at the binary we are actually running. */
export function repairLauncherExe(launchersDir: string, platform: NodeJS.Platform, execPath: string): void {
  const file = join(launchersDir, platform === 'win32' ? 'acp.cmd' : 'acp');
  try {
    const content = readFileSync(file, 'utf8');
    const exeName = basename(execPath);
    const repaired =
      platform === 'win32' ? repairedCmdLauncher(content, exeName) : repairedShLauncher(content, exeName);
    if (repaired) writeFileSync(file, repaired);
  } catch {
    // The common case is a launcher that already matches; a file we cannot read or write must not block startup.
  }
}

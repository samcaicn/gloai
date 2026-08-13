import { spawn, spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { delimiter } from 'node:path'
import type { ChildHandle, DshCommandResult, DshRunner } from '../types.js'

export function which(command: string, pathEnv: string | undefined = process.env.PATH): string | null {
  if (!pathEnv) return null
  const ext = process.platform === 'win32' ? ['.cmd', '.exe', ''] : ['']
  for (const dir of pathEnv.split(delimiter)) {
    for (const suffix of ext) {
      const candidate = `${dir}/${command}${suffix}`
      if (existsSync(candidate)) return candidate
    }
  }
  return null
}

export class ProcessDshRunner implements DshRunner {
  constructor(
    private readonly pathEnv: string | undefined = process.env.PATH,
    private readonly extraEnv: NodeJS.ProcessEnv = process.env,
  ) {}

  whichDsh(): string | null {
    return which('dsh', this.pathEnv)
  }

  async runPlugin(profile: string, args: readonly string[]): Promise<DshCommandResult> {
    const dsh = this.whichDsh()
    if (!dsh) {
      return {
        exitCode: 127,
        stdout: '',
        stderr: 'dsh not found on PATH — install DeepSeek Harness and retry',
      }
    }
    const result = spawnSync(dsh, ['plugin', '--profile', profile, ...args], {
      encoding: 'utf8',
      env: this.extraEnv,
      timeout: 10 * 60 * 1000,
    })
    if (result.error) {
      const code = (result.error as NodeJS.ErrnoException).code
      if (code === 'ENOENT') {
        return { exitCode: 127, stdout: '', stderr: 'dsh not found on PATH — install DeepSeek Harness and retry' }
      }
      throw result.error
    }
    return {
      exitCode: result.status ?? 1,
      stdout: result.stdout ?? '',
      stderr: result.stderr ?? '',
    }
  }

  spawnProfile(options: { profile: string; env: Record<string, string>; cwd?: string }): ChildHandle {
    const dsh = this.whichDsh()
    if (!dsh) {
      throw new Error('dsh not found on PATH — install DeepSeek Harness before starting the runtime')
    }
    const child = spawn(dsh, ['--profile', options.profile], {
      env: { ...this.extraEnv, ...options.env },
      cwd: options.cwd,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    return {
      pid: child.pid,
      kill(signal?: NodeJS.Signals) {
        child.kill(signal)
      },
      onExit(handler) {
        if (child.exitCode !== null || child.signalCode !== null) {
          handler(child.exitCode, child.signalCode)
          return
        }
        child.on('exit', handler)
      },
      stdout: child.stdout,
      stderr: child.stderr,
    }
  }
}

export function pluginAddArgs(spec: string): string[] {
  return ['add', spec]
}

export function pluginRemoveArgs(packageName: string): string[] {
  return ['remove', packageName]
}

export function formatDshFailure(result: DshCommandResult): string {
  const parts = [
    `dsh plugin exited ${result.exitCode}`,
    result.stderr.trim() || result.stdout.trim() || '(no output)',
  ]
  return parts.join('\n')
}

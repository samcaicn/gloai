/**
 * Easel companion-process integration.
 *
 * Easel (github.com/ZJU-REAL/Easel) is a Python FastAPI + React social-media
 * agent. It is not a Cordis plugin and cannot be compiled into the Harness
 * runtime, so AiMarketing embeds it as a managed companion process: the desktop
 * host launches `easel web` (loopback :7860) and opens a dedicated, sandboxed
 * BrowserWindow to its UI. This keeps the webview boundary intact — Easel runs
 * in its own window, not inside the Harness renderer.
 *
 * Prerequisites (documented for the operator, not enforced here):
 *  - Python 3.10+ with a virtual environment at `packages/easel/.venv`
 *    (created by `bash packages/easel/setup.sh` / `packages/easel/setup.ps1`).
 *  - `pip install -e .` inside that venv (setup does this) so `easel` is importable.
 *  - FFmpeg + Playwright Chromium for media/publishing features.
 *  - `.env` API keys; the chat path also needs `easel gateway start` (port 18789).
 */

import { app, BrowserWindow } from 'electron'
import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { secureWindow } from './security'
import { prewarmShellEnvironment } from './runtime/harness-runtime'

const EASEL_DEFAULT_PORT = 7860
const EASEL_WEB_READY_TIMEOUT_MS = 20_000

export interface EaselStatus {
  /** `packages/easel` exists and its `.venv` Python is present. */
  configured: boolean
  /** The `easel web` process is alive. */
  running: boolean
  url?: string
}

function findEaselRoot(): string | undefined {
  const candidates = [
    join(app.getAppPath(), 'packages', 'easel'),
    process.resourcesPath ? join(process.resourcesPath, 'packages', 'easel') : undefined,
    join(__dirname, '..', '..', 'packages', 'easel')
  ].filter((value): value is string => value !== undefined)
  return candidates.find((candidate) => existsSync(candidate))
}

function findVenvPython(root: string): string | undefined {
  const exe =
    process.platform === 'win32'
      ? join(root, '.venv', 'Scripts', 'python.exe')
      : join(root, '.venv', 'bin', 'python')
  return existsSync(exe) ? exe : undefined
}

/**
 * Terminate a spawned `easel web` tree. `easel web` itself launches
 * `web/app.py` (uvicorn) as a child, so killing only the top-level process
 * leaves uvicorn holding port 7860 and breaks the next launch. On POSIX the
 * child is a detached session leader, so a negative-pid signal reaches the
 * whole group; on Windows `taskkill /T` walks the tree.
 */
function killProcessTree(child: ChildProcess): void {
  if (child.pid === undefined) return
  if (process.platform === 'win32') {
    spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], {
      stdio: 'ignore',
      windowsHide: true
    })
    return
  }
  try {
    process.kill(-child.pid, 'SIGTERM')
  } catch {
    child.kill('SIGTERM')
  }
}

/** Keep the tail of Easel stderr so a failed launch can explain itself. */
const STDERR_TAIL_LIMIT = 2_000

export class EaselManager {
  private child?: ChildProcess
  private window?: BrowserWindow
  private stderrTail = ''
  private readonly root = findEaselRoot()
  private readonly port = EASEL_DEFAULT_PORT

  private get url(): string {
    return `http://127.0.0.1:${this.port}`
  }

  status(): EaselStatus {
    const running = this.child !== undefined && this.child.exitCode === null
    return {
      configured: this.root !== undefined && findVenvPython(this.root) !== undefined,
      running,
      url: running ? this.url : undefined
    }
  }

  private async ensureStarted(): Promise<void> {
    if (this.child !== undefined && this.child.exitCode === null) return
    if (this.root === undefined) {
      throw new Error('Easel package not found under packages/easel.')
    }
    const python = findVenvPython(this.root)
    if (python === undefined) {
      throw new Error(
        'Easel virtual environment not found. Run packages/easel/setup.sh (or setup.ps1) to create .venv and install dependencies.'
      )
    }
    const env = await prewarmShellEnvironment()
    this.stderrTail = ''
    this.child = spawn(
      python,
      ['-m', 'easel', 'web', '--port', String(this.port)],
      {
        cwd: this.root,
        env: { ...env, NO_COLOR: '1' },
        stdio: ['ignore', 'pipe', 'pipe'],
        windowsHide: true,
        detached: true
      }
    )
    const child = this.child
    child.stderr?.setEncoding('utf8')
    child.stderr?.on('data', (chunk: string) => {
      this.stderrTail = (this.stderrTail + chunk).slice(-STDERR_TAIL_LIMIT)
    })
    child.stdout?.setEncoding('utf8')
    child.stdout?.on('data', (chunk: string) => {
      this.stderrTail = (this.stderrTail + chunk).slice(-STDERR_TAIL_LIMIT)
    })
    child.once('exit', () => {
      if (this.child?.exitCode !== null) this.child = undefined
    })
    child.once('error', () => {
      this.child = undefined
    })
    await this.waitUntilReady(EASEL_WEB_READY_TIMEOUT_MS)
  }

  private async waitUntilReady(timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const child = this.child
      if (child === undefined || child.exitCode !== null) {
        throw new Error(`Easel exited before serving :${this.port}. ${this.stderrTail}`.trim())
      }
      try {
        const response = await fetch(this.url, {
          redirect: 'manual',
          signal: AbortSignal.timeout(1_000)
        })
        if (response.status < 500) return
      } catch {
        // Server not answering yet; keep probing.
      }
      await new Promise((resolve) => setTimeout(resolve, 500))
    }
    throw new Error(
      `Easel did not answer on :${this.port} within ${timeoutMs} ms. ${this.stderrTail}`.trim()
    )
  }

  async openWindow(): Promise<{ ok: boolean; url?: string; detail?: string }> {
    try {
      await this.ensureStarted()
      if (this.window === undefined || this.window.isDestroyed()) {
        const window = new BrowserWindow({
          width: 1280,
          height: 860,
          minWidth: 900,
          minHeight: 640,
          show: false,
          title: 'Easel',
          backgroundColor: '#0e0e10',
          webPreferences: {
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: true,
            webSecurity: true
          }
        })
        secureWindow(window)
        window.on('closed', () => {
          if (this.window === window) this.window = undefined
        })
        this.window = window
      }
      const target = this.url
      if (this.window.webContents.getURL() !== target) {
        await this.window.loadURL(target).catch(() => undefined)
      }
      this.window.show()
      this.window.focus()
      return { ok: true, url: target }
    } catch (error) {
      return {
        ok: false,
        detail: error instanceof Error ? error.message : String(error)
      }
    }
  }

  async stop(): Promise<void> {
    if (this.window !== undefined && !this.window.isDestroyed()) {
      this.window.destroy()
      this.window = undefined
    }
    if (this.child !== undefined && this.child.exitCode === null) {
      killProcessTree(this.child)
    }
    this.child = undefined
  }
}

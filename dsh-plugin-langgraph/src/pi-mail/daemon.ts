import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { PiMailClient } from './client.js'

export interface DaemonOptions {
  /** Path to the pi-mail extensions directory (contains daemon.mjs). */
  extensionDir: string
  /** HTTP host for the daemon's web UI. */
  host?: string
  /** HTTP port for the daemon's web UI. */
  port?: number
  /** Override the node executable path. */
  nodePath?: string
  /** Additional environment variables. */
  env?: Record<string, string>
  /** Logger for daemon lifecycle events. */
  logger?: { info: (msg: string) => void; error: (msg: string) => void }
}

export interface DaemonInfo {
  pid: number
  host: string
  port: number
  url: string
  /** Unix socket path (undefined on Windows). */
  socketPath?: string
  /** TCP port for daemon communication (Windows only). */
  tcpPort?: number
}

/**
 * Manages the lifecycle of a pi-mail daemon process.
 * The daemon is a singleton: only one per socket path (Unix) or TCP port (Windows).
 *
 * Cross-platform:
 * - Unix/Linux/macOS: uses Unix domain socket for agent communication
 * - Windows: uses TCP port (UI port + 1) for agent communication
 */
export class PiMailDaemon {
  private proc: ChildProcess | null = null
  private _info: DaemonInfo | null = null

  constructor(private readonly opts: DaemonOptions) {}

  /** The daemon script path (extensions/daemon.mjs inside pi-mail). */
  get daemonScript(): string {
    return resolve(this.opts.extensionDir, 'daemon.mjs')
  }

  /** The PID file path. */
  get pidPath(): string {
    return join(this.opts.extensionDir, '..', '.pi', 'agent', 'mail-daemon.pid')
  }

  /** The socket path the daemon listens on (Unix only). */
  get socketPath(): string {
    return join(this.opts.extensionDir, '..', '.pi', 'agent', 'mail-daemon.sock')
  }

  /** Whether running on Windows (uses TCP instead of Unix socket). */
  protected get isWindows(): boolean {
    return process.platform === 'win32'
  }

  /** TCP port for Windows daemon communication (UI port + 1). */
  protected get tcpPort(): number {
    return (this.opts.port ?? 1994) + 1
  }

  get info(): DaemonInfo | null {
    return this._info
  }

  /** Check if the daemon script exists at the configured path. */
  scriptExists(): boolean {
    return existsSync(this.daemonScript)
  }

  /** Check if the daemon process is currently running (via HTTP health check). */
  async isRunning(): Promise<boolean> {
    const client = this.createClient()
    return client.isAlive()
  }

  /**
   * Start the daemon if it is not already running.
   * Resolves once the daemon is accepting connections.
   */
  async start(): Promise<DaemonInfo> {
    if (this.proc && !this.proc.killed) {
      const running = await this.isRunning()
      if (running && this._info) return this._info
    }

    if (!this.scriptExists()) {
      throw new Error(`pi-mail daemon script not found: ${this.daemonScript}`)
    }

    const host = this.opts.host ?? '127.0.0.1'
    const port = this.opts.port ?? 1994
    // Normalize node path: strip Windows \\?\ prefix and use consistent separators
    const rawNode = this.opts.nodePath ?? process.execPath
    const node = rawNode.replace(/^\\\\\?\\/, '').replace(/\//g, '\\')
    const script = this.daemonScript.replace(/\//g, '\\')

    const env: Record<string, string> = {
      ...process.env,
      PI_MAIL_UI_HOST: host,
      PI_MAIL_UI_PORT: String(port),
      ...this.opts.env,
    }

    // On Windows, configure TCP for daemon communication
    if (this.isWindows) {
      env.PI_MAIL_TCP_HOST = host
      env.PI_MAIL_TCP_PORT = String(this.tcpPort)
    }

    this.opts.logger?.info?.(`Starting pi-mail daemon: ${script}`)

    const proc = spawn(node, [script], {
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: false,
      windowsHide: this.isWindows,
    })

    this.proc = proc

    proc.stdout?.on('data', (data) => {
      const text = data.toString().trim()
      if (text) this.opts.logger?.info?.(`[pi-mail] ${text}`)
    })
    proc.stderr?.on('data', (data) => {
      const text = data.toString().trim()
      if (text) this.opts.logger?.error?.(`[pi-mail] ${text}`)
    })
    proc.on('exit', (code, signal) => {
      this.opts.logger?.info?.(`pi-mail daemon exited (code=${code}, signal=${signal})`)
      this.proc = null
      this._info = null
    })

    // Wait for the daemon to become healthy
    const url = `http://${host}:${port}`
    await this.waitForHealth(url, 15_000)

    this._info = {
      pid: proc.pid ?? 0,
      host,
      port,
      url,
      ...(this.isWindows ? { tcpPort: this.tcpPort } : { socketPath: this.socketPath }),
    }

    this.opts.logger?.info?.(`pi-mail daemon ready at ${url}`)
    return this._info
  }

  /** Stop the daemon gracefully. */
  async stop(): Promise<void> {
    if (!this.proc || this.proc.killed) {
      this.proc = null
      return
    }
    const proc = this.proc
    this.proc = null
    this._info = null

    // SIGTERM works on both Unix and Windows for Node.js processes
    proc.kill('SIGTERM')

    // Force kill after timeout
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        try { proc.kill('SIGKILL') } catch { /* already dead */ }
        resolve()
      }, 3000)
      proc.once('exit', () => {
        clearTimeout(timer)
        resolve()
      })
    })
  }

  /** Create a client bound to this daemon's address. */
  createClient(): PiMailClient {
    const host = this.opts.host ?? '127.0.0.1'
    const port = this.opts.port ?? 1994
    return new PiMailClient(`http://${host}:${port}`)
  }

  // ── Private ───────────────────────────────────────────────────────────────

  private async waitForHealth(url: string, timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs
    const client = new PiMailClient(url)
    while (Date.now() < deadline) {
      if (await client.isAlive()) return
      await sleep(200)
    }
    throw new Error(`pi-mail daemon did not become healthy within ${timeoutMs}ms`)
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

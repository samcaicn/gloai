import { EventEmitter } from 'node:events'
import type { ChildProcess } from 'node:child_process'
import { PassThrough } from 'node:stream'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

// Easel is a Python companion process, so the manager has to survive three
// failure shapes: no venv at all, a process that dies on startup, and a server
// that never answers. These tests drive those paths without spawning Python.
const mocks = vi.hoisted(() => ({
  fsExists: true,
  spawn: vi.fn(),
  secureWindow: vi.fn(),
  windows: [] as FakeBrowserWindow[]
}))

class FakeBrowserWindow {
  options: unknown
  loadedUrl?: string
  shown = false
  destroyed = false
  webContents = { getURL: () => this.loadedUrl ?? '' }

  constructor(options: unknown) {
    this.options = options
    mocks.windows.push(this)
  }

  isDestroyed(): boolean {
    return this.destroyed
  }

  async loadURL(url: string): Promise<void> {
    this.loadedUrl = url
  }

  show(): void {
    this.shown = true
  }

  focus(): void {
    // No-op: focus is unobservable in tests.
  }

  on(): void {
    // The manager only subscribes to 'closed'.
  }

  destroy(): void {
    this.destroyed = true
  }
}

vi.mock('node:fs', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  existsSync: () => mocks.fsExists
}))

vi.mock('node:child_process', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  spawn: mocks.spawn
}))

vi.mock('electron', () => ({
  app: { getAppPath: () => 'C:/repo' },
  BrowserWindow: FakeBrowserWindow
}))

vi.mock('../src/main/security', () => ({ secureWindow: mocks.secureWindow }))

vi.mock('../src/main/runtime/harness-runtime', () => ({
  prewarmShellEnvironment: async () => ({})
}))

class FakeChild extends EventEmitter {
  pid: number
  exitCode: number | null = null
  stdout = new PassThrough()
  stderr = new PassThrough()
  kill = vi.fn()

  constructor(pid: number, options?: { exitCode?: number; stderr?: string }) {
    super()
    this.pid = pid
    if (options?.stderr !== undefined) this.stderr.end(options.stderr)
    const exitCode = options?.exitCode
    if (exitCode !== undefined) {
      process.nextTick(() => {
        this.exitCode = exitCode
        this.emit('exit', exitCode)
      })
    }
  }
}

function asChild(child: FakeChild): ChildProcess {
  // The fake only implements the surface EaselManager touches; the cast keeps
  // the test free of Electron's native types.
  return child as unknown as ChildProcess
}

let manager: typeof import('../src/main/easel-manager')

beforeEach(async () => {
  vi.resetModules()
  vi.clearAllMocks()
  mocks.fsExists = true
  mocks.windows.length = 0
  vi.stubGlobal('fetch', vi.fn(async () => ({ status: 200 })))
  manager = await import('../src/main/easel-manager')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

it('reports an unconfigured Easel instead of launching a process', async () => {
  mocks.fsExists = false
  const instance = new manager.EaselManager()

  expect(instance.status().configured).toBe(false)

  const result = await instance.openWindow()
  expect(result.ok).toBe(false)
  expect(result.detail).toContain('packages/easel')
  expect(mocks.spawn).not.toHaveBeenCalled()
})

it('surfaces Easel stderr when the process exits before serving the port', async () => {
  const child = new FakeChild(4321, {
    exitCode: 1,
    stderr: 'ModuleNotFoundError: No module named uvicorn\n'
  })
  mocks.spawn.mockReturnValue(asChild(child))
  // A dead process never answers: the probe must fail so the loop notices the
  // exit on the following tick instead of trusting a stubbed 200.
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      throw new Error('ECONNREFUSED')
    })
  )

  const instance = new manager.EaselManager()
  const result = await instance.openWindow()

  expect(result.ok).toBe(false)
  expect(result.detail).toContain('exited before serving')
  expect(result.detail).toContain('ModuleNotFoundError')
  expect(instance.status().running).toBe(false)
})

it('opens a dedicated sandboxed window once the loopback server answers', async () => {
  const child = new FakeChild(1234)
  mocks.spawn.mockReturnValue(asChild(child))

  const instance = new manager.EaselManager()
  const result = await instance.openWindow()

  expect(result.ok).toBe(true)
  expect(result.url).toBe('http://127.0.0.1:7860')
  // `easel web` takes only --port; uvicorn binds inside web/app.py.
  expect(mocks.spawn).toHaveBeenCalledWith(
    expect.any(String),
    ['-m', 'easel', 'web', '--port', '7860'],
    expect.objectContaining({ detached: true })
  )

  const window = mocks.windows[0]
  expect(window).toBeDefined()
  expect(window?.loadedUrl).toBe('http://127.0.0.1:7860')
  expect(window?.shown).toBe(true)
  expect(mocks.secureWindow).toHaveBeenCalledTimes(1)
  expect(instance.status().running).toBe(true)
  expect(instance.status().url).toBe('http://127.0.0.1:7860')
})

it('reuses the running process on a second open instead of relaunching', async () => {
  const child = new FakeChild(1234)
  mocks.spawn.mockReturnValue(asChild(child))

  const instance = new manager.EaselManager()
  await instance.openWindow()
  const second = await instance.openWindow()

  expect(second.ok).toBe(true)
  expect(mocks.spawn).toHaveBeenCalledTimes(1)
  expect(mocks.windows).toHaveLength(1)
})

it('terminates the whole process tree so uvicorn cannot hold port 7860', async () => {
  const child = new FakeChild(5555)
  mocks.spawn.mockReturnValue(asChild(child))

  const instance = new manager.EaselManager()
  await instance.openWindow()
  await instance.stop()

  if (process.platform === 'win32') {
    expect(mocks.spawn).toHaveBeenCalledWith(
      'taskkill',
      ['/pid', '5555', '/T', '/F'],
      expect.objectContaining({ windowsHide: true })
    )
  } else {
    // POSIX: the child is a detached session leader, so a negative pid signal
    // reaches the whole group instead of only the `easel` wrapper.
    const kill = vi.spyOn(process, 'kill')
    const instance2 = new manager.EaselManager()
    const child2 = new FakeChild(6666)
    mocks.spawn.mockReturnValue(asChild(child2))
    await instance2.openWindow()
    await instance2.stop()
    expect(kill).toHaveBeenCalledWith(-6666, 'SIGTERM')
    kill.mockRestore()
  }

  expect(child.exitCode).toBeNull()
  expect(instance.status().running).toBe(false)
  expect(mocks.windows[0]?.destroyed).toBe(true)
})

it('fails with the server log when Easel never answers on the port', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      throw new Error('ECONNREFUSED')
    })
  )
  const child = new FakeChild(7777, { stderr: 'waiting for migration\n' })
  mocks.spawn.mockReturnValue(asChild(child))

  const instance = new manager.EaselManager()
  const result = await instance.openWindow()

  expect(result.ok).toBe(false)
  expect(result.detail).toContain('did not answer')
})

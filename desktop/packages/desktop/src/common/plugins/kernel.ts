/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * A small plugin kernel: the piece of DSH Desktop's "everything is a plugin" model that an app like this one can
 * actually take on without being rebuilt around it.
 *
 * DSH runs every feature — window, tray, terminal, updater, the market itself — as a Cordis plugin composed into a
 * `Context`. Adopting Cordis here would mean rewriting the whole app around its container, which is a rewrite, not a
 * feature. What carries over, and what this file keeps, is the *shape* of that model:
 *
 * - a **service**: a named capability one plugin provides and another asks for (`provide` / `get` / `inject`), so a
 *   feature never reaches for a global and never imports the thing that implements it;
 * - a **plugin**: `{ name, inject, apply }`, whose whole lifetime is the disposers it registered — unload it and
 *   everything it added goes away, in reverse order;
 * - an **effect**: a disposer the kernel owns, so unloading is a property of the plugin, not of a shutdown list
 *   somewhere else;
 * - a **slot**: a named place in the UI that any plugin may fill, so a screen does not have to know its extensions.
 *
 * Built-ins register through the same `plugin()` call as anything else would: that is the point. A feature added here
 * is not privileged over one added later by a plugin.
 *
 * No dependencies: this runs in the main process and in the renderer, and is pure TypeScript.
 */

/** What a plugin leaves behind: undo it and the plugin was never there. */
export type Dispose = () => void;

export type Logger = {
  info(message: string): void;
  warn(message: string): void;
  error(message: string): void;
};

export const silentLogger: Logger = { info: () => undefined, warn: () => undefined, error: () => undefined };

/**
 * A plugin. `inject` names the services it needs: the kernel holds `apply` back until every one of them is provided,
 * and unloads the plugin again if one goes away. That is how a feature degrades instead of crashing when the machine
 * cannot support it — the local judge, for one, only runs where its runtime does.
 */
export type PluginDefinition<Config = unknown> = {
  name: string;
  inject?: readonly string[];
  apply: (ctx: PluginContext, config?: Config) => void | Promise<void>;
};

/** One filled place in a screen: what to draw, and where in the row of them. */
export type SlotEntry<Payload = unknown> = {
  id: string;
  name: string;
  order: number;
  payload: Payload;
};

export type SlotRegistry = {
  register: (slot: string, entry: Omit<SlotEntry, 'id'> & { id?: string }) => Dispose;
  entries: (slot: string) => readonly SlotEntry[];
  subscribe: (listener: (slot: string) => void) => Dispose;
};

export function createSlotRegistry(): SlotRegistry {
  const slots = new Map<string, SlotEntry[]>();
  const listeners = new Set<(slot: string) => void>();
  const notify = (slot: string): void => {
    for (const listener of Array.from(listeners)) listener(slot);
  };

  return {
    register(slot, entry) {
      const row = slots.get(slot) ?? [];
      // Registering the same id twice replaces it, so a plugin reload does not stack duplicates.
      const next = row.filter((item) => item.id !== entry.id);
      next.push({ id: entry.id ?? `${entry.name}#${next.length}`, ...entry });
      next.sort((left, right) => left.order - right.order || left.id.localeCompare(right.id));
      slots.set(slot, next);
      notify(slot);
      return () => {
        const current = slots.get(slot);
        if (!current) return;
        const kept = current.filter((item) => item.id !== entry.id);
        if (kept.length) slots.set(slot, kept);
        else slots.delete(slot);
        notify(slot);
      };
    },
    entries(slot) {
      return slots.get(slot) ?? [];
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

/** What a plugin is handed: the services it asked for, and the kernel's bookkeeping of what it added. */
export interface PluginContext {
  readonly name: string;
  readonly slots: SlotRegistry;
  readonly logger: Logger;
  /** Answers the service under `key`, or undefined when nobody provided it yet. */
  get: <T>(key: string) => T | undefined;
  /** Offers `value` as `key` until the plugin is unloaded. */
  provide: (key: string, value: unknown) => Dispose;
  /** Runs `setup` once every one of `deps` exists, and takes it back when one stops existing. */
  inject: (deps: readonly string[], setup: (ctx: PluginContext) => void) => Dispose;
  /** Hands `disposer` to the kernel: it is called when this context is unloaded. */
  effect: (disposer: Dispose) => void;
  /** A child context unloaded with this one, for a group of registrations that belong together. */
  scope: (name: string) => PluginContext;
  dispose: () => void;
}

class Context implements PluginContext {
  readonly name: string;
  readonly slots: SlotRegistry;
  readonly logger: Logger;

  private readonly disposers: Dispose[] = [];
  private readonly children = new Set<Context>();
  private disposed = false;

  constructor(
    private readonly kernel: PluginKernel,
    name: string,
    private readonly parent?: Context
  ) {
    this.name = name;
    this.slots = kernel.slots;
    this.logger = kernel.logger;
  }

  get<T>(key: string): T | undefined {
    return this.kernel.get<T>(key);
  }

  provide(key: string, value: unknown): Dispose {
    const dispose = this.kernel.provide(key, value);
    this.effect(dispose);
    return dispose;
  }

  inject(deps: readonly string[], setup: (ctx: PluginContext) => void): Dispose {
    let child: PluginContext | undefined;
    const stopWatching = this.kernel.onChange((key) => {
      if (deps.includes(key)) sync();
    });

    const sync = (): void => {
      const ready = deps.every((key) => this.kernel.has(key));
      if (ready && !child) {
        const next = this.scope(`inject(${deps.join(',')})`);
        child = next;
        try {
          setup(next);
        } catch (error) {
          // A plugin that throws on load must not take the app with it, and must not linger half-loaded.
          this.logger.error(`[plugins] "${this.name}" failed with ${deps.join(',')}: ${String(error)}`);
          next.dispose();
          child = undefined;
        }
      } else if (!ready && child) {
        child.dispose();
        child = undefined;
      }
    };
    sync();

    const dispose = (): void => {
      stopWatching();
      child?.dispose();
      child = undefined;
    };
    this.effect(dispose);
    return dispose;
  }

  effect(disposer: Dispose): void {
    // Already unloaded: run it now so a late registration never leaks past the plugin that made it.
    if (this.disposed) {
      runDisposer(disposer, this.logger, this.name);
      return;
    }
    this.disposers.push(disposer);
  }

  scope(name: string): PluginContext {
    const child = new Context(this.kernel, `${this.name}/${name}`, this);
    this.children.add(child);
    // The child unloads with the parent, and drops itself from the parent's bookkeeping when unloaded alone.
    this.effect(() => {
      if (this.children.delete(child)) child.dispose();
    });
    child.effect(() => this.children.delete(child));
    return child;
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    for (const child of Array.from(this.children)) child.dispose();
    this.children.clear();
    // Reverse order: the last thing registered goes first, so nothing is torn down under something using it.
    for (const disposer of this.disposers.reverse()) runDisposer(disposer, this.logger, this.name);
    this.disposers.length = 0;
    this.parent?.forget(this);
  }

  private forget(child: Context): void {
    this.children.delete(child);
  }
}

function runDisposer(disposer: Dispose, logger: Logger, name: string): void {
  try {
    disposer();
  } catch (error) {
    logger.error(`[plugins] "${name}" could not be undone cleanly: ${String(error)}`);
  }
}

export type PluginKernelOptions = {
  logger?: Logger;
  slots?: SlotRegistry;
};

/**
 * The container. Services live here; plugins are loaded through it; `slots` is the one registry both processes can
 * share a shape for (the renderer keeps its own instance and fills the same slot names).
 */
export class PluginKernel {
  readonly slots: SlotRegistry;
  readonly logger: Logger;

  private readonly services = new Map<string, unknown>();
  private readonly listeners = new Set<(key: string) => void>();

  constructor(options: PluginKernelOptions = {}) {
    this.slots = options.slots ?? createSlotRegistry();
    this.logger = options.logger ?? silentLogger;
  }

  get<T>(key: string): T | undefined {
    return this.services.get(key) as T | undefined;
  }

  has(key: string): boolean {
    return this.services.has(key);
  }

  provide(key: string, value: unknown): Dispose {
    this.services.set(key, value);
    this.emit(key);
    return () => {
      // Only the provider that set it may withdraw it: a later provider owns the key now.
      if (this.services.get(key) !== value) return;
      this.services.delete(key);
      this.emit(key);
    };
  }

  onChange(listener: (key: string) => void): Dispose {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private emit(key: string): void {
    for (const listener of Array.from(this.listeners)) listener(key);
  }

  context(name: string): PluginContext {
    return new Context(this, name);
  }

  /**
   * Loads a plugin. With `inject`, `apply` waits for those services — which is what makes a capability optional: the
   * market, for instance, browses as soon as it loads and only gains installing once the host provides the pieces it
   * needs for it.
   */
  async plugin<Config>(definition: PluginDefinition<Config>, config?: Config): Promise<Dispose> {
    const run = (ctx: PluginContext): Promise<void> | void => definition.apply(ctx, config);
    const deps = definition.inject ?? [];

    if (deps.length === 0) {
      const ctx = this.context(definition.name);
      try {
        await run(ctx);
      } catch (error) {
        // A plugin that throws on load must not take the app with it, here the same as on the inject path.
        this.logger.error(`[plugins] "${definition.name}" failed to load: ${String(error)}`);
        ctx.dispose();
        return () => undefined;
      }
      return () => ctx.dispose();
    }

    // Held back until every dependency exists, and unloaded if one is withdrawn: see `Context.inject`.
    return await new Promise<Dispose>((resolve) => {
      const root = this.context(definition.name);
      let settled = false;
      root.inject(deps, (ctx) => {
        void Promise.resolve(run(ctx)).then(
          () => {
            if (settled) return;
            settled = true;
            resolve(() => root.dispose());
          },
          (error: unknown) => {
            this.logger.error(`[plugins] "${definition.name}" failed to load: ${String(error)}`);
            if (settled) return;
            settled = true;
            ctx.dispose();
            resolve(() => root.dispose());
          }
        );
      });
      // Never resolves if a dependency never appears — by design: the plugin stays unloaded, not half-loaded.
    });
  }
}

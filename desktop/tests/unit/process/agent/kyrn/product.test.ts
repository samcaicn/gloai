import { describe, expect, it } from 'vitest';

import { AGENT_NAME, findRegistration, initializeKyrn } from '@/process/agent/kyrn/product';

const NEW_COMMAND = 'C:\\Users\\me\\AppData\\Local\\Programs\\AiBuddy\\resources\\mu\\acp.cmd';
const OLD_COMMAND = 'C:\\Users\\me\\AppData\\Local\\Programs\\mu\\resources\\mu\\acp.cmd';
const DESCRIPTION = 'AiBuddy harness · local Codex login · Jev judgment';

const row = (over: Record<string, unknown> = {}) => ({
  id: 'agent-1',
  name: AGENT_NAME,
  command: OLD_COMMAND,
  enabled: true,
  description: DESCRIPTION,
  ...over,
});

describe('findRegistration', () => {
  it("matches the row that runs this install's command", () => {
    const agents = [row({ command: NEW_COMMAND })];
    expect(findRegistration(agents, NEW_COMMAND)?.id).toBe('agent-1');
  });

  it('adopts the row a previous install left behind (its command points at the old folder)', () => {
    const agents = [row()];
    const found = findRegistration(agents, NEW_COMMAND);
    expect(found?.id).toBe('agent-1');
  });

  it('adopts a row still carrying a former name or one of our older descriptions', () => {
    for (const over of [
      { name: 'mu' },
      { name: 'KYRN' },
      { description: 'mu harness · local Codex login · Jev judgment' },
      { description: 'KYRN harness · local Codex login · Jev judgment' },
    ]) {
      expect(findRegistration([row(over)], NEW_COMMAND)?.id).toBe('agent-1');
    }
  });

  it("refuses a namesake without a description (it may be a person's own agent)", () => {
    expect(() => findRegistration([row({ description: undefined })], NEW_COMMAND)).toThrow();
  });

  it("refuses a namesake that is somebody else's agent (a description that is not ours)", () => {
    const agents = [row({ description: 'my own helper' })];
    expect(() => findRegistration(agents, NEW_COMMAND)).toThrow();
  });

  it('finds nothing in an empty catalog', () => {
    expect(findRegistration([], NEW_COMMAND)).toBeUndefined();
  });
});

describe('initializeKyrn adopts a stale registration', () => {
  // A tiny in-memory backend: the routes initializeKyrn touches, enough to follow the adoption through.
  function backend(state: { agents: Array<Record<string, unknown>>; assistants: Array<Record<string, unknown>> }) {
    const calls: Array<[string, string, unknown]> = [];
    const whole = (row: Record<string, unknown>) => ({ ...row, env: [], available: true });
    return {
      calls,
      request: async (method: string, path: string, body?: unknown) => {
        calls.push([method, path, body]);
        if (method === 'GET' && path === '/api/agents/management') return state.agents;
        if (method === 'PATCH' && path.endsWith('/enabled')) {
          const row = state.agents.find((a) => path.includes(String(a.id)));
          if (row) row.enabled = (body as { enabled: boolean }).enabled;
          return row ? whole(row) : undefined;
        }
        if (method === 'PUT' && path.startsWith('/api/agents/custom/')) {
          const row = state.agents.find((a) => path.includes(String(a.id)));
          if (!row) throw new Error('no row');
          Object.assign(row, body as Record<string, unknown>, {
            advanced: (body as { advanced: Record<string, unknown> }).advanced,
          });
          return { ...row };
        }
        if (method === 'POST' && path === '/api/agents/custom') {
          const created = { ...(body as Record<string, unknown>), id: 'agent-new', enabled: true };
          state.agents.push(created);
          return created;
        }
        if (method === 'POST' && path.endsWith('/health-check')) {
          const row = state.agents.find((a) => path.includes(String(a.id)));
          return { ...row, status: 'online' };
        }
        if (method === 'GET' && path === '/api/assistants') return state.assistants;
        if (method === 'PATCH' && path.endsWith('/state')) {
          const assistant = state.assistants.find((a) => path.includes(String(a.id)));
          if (assistant) assistant.enabled = (body as { enabled: boolean }).enabled;
          return assistant;
        }
        throw new Error(`unexpected ${method} ${path}`);
      },
    };
  }

  it("rewrites the old install's command onto the same agent id instead of failing", async () => {
    const state = {
      agents: [row({ yolo_id: 'full' })],
      assistants: [{ id: 'as-1', agent_id: 'agent-1', enabled: true }],
    };
    const { request, calls } = backend(state);
    const catalog = await initializeKyrn(request as never, NEW_COMMAND);
    expect(catalog.agentId).toBe('agent-1');
    const put = calls.find(([method, path]) => method === 'PUT' && path.startsWith('/api/agents/custom/'));
    expect(put).toBeTruthy();
    expect((put![2] as { command: string }).command).toBe(NEW_COMMAND);
  });

  it('registers a new agent when the catalog has none of ours', async () => {
    const state = { agents: [], assistants: [] };
    const { request, calls } = backend(state);
    const catalog = await initializeKyrn(request as never, NEW_COMMAND);
    expect(catalog.agentId).toBe('agent-new');
    expect(calls.some(([method, path]) => method === 'POST' && path === '/api/agents/custom')).toBe(true);
  });
});

// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reportSkillFailure, reportSkillSuccess } from './executionReport';

// Mock mcpCallWithRefresh to capture calls without hitting Tauri/network.
const mcpCallMock = vi.hoisted(() => vi.fn());

vi.mock('./device', () => ({
  mcpCallWithRefresh: (...args: unknown[]) => mcpCallMock(...args),
}));

// Mock logger to no-ops (avoid Tauri invoke side effects in tests).
vi.mock('@/shared/utils/logger', () => ({
  createLogger: () => ({
    trace: vi.fn(),
    debug: vi.fn(),
    info: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
  }),
}));

beforeEach(() => {
  mcpCallMock.mockReset();
  mcpCallMock.mockResolvedValue({ ok: true, data: {} });
});

// ── reportSkillFailure ──────────────────────────────────

describe('reportSkillFailure', () => {
  it('calls execution.report with failure payload', async () => {
    reportSkillFailure('skill-123', 'TypeError: price is undefined');
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    expect(mcpCallMock).toHaveBeenCalledWith('execution.report', {
      skill_id: 'skill-123',
      status: 'failure',
      error_message: 'TypeError: price is undefined',
    });
  });

  it('returns void (fire-and-forget)', () => {
    expect(reportSkillFailure('skill-123', 'err')).toBeUndefined();
  });

  it('guards against empty skillId (no MCP call)', async () => {
    reportSkillFailure('', 'err');
    await new Promise((r) => setTimeout(r, 20));
    expect(mcpCallMock).not.toHaveBeenCalled();
  });

  it('defaults empty error_message to empty string', async () => {
    reportSkillFailure('skill-1', '');
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    expect(mcpCallMock.mock.calls[0][1].error_message).toBe('');
  });

  it('extracts .message from Error objects (avoids "{}" on the wire)', async () => {
    // 关键修复：直接把 Error 塞进 MCP params，JSON.stringify(Error) === '{}'，
    // 服务端拿不到错误描述，自动修复链路彻底失效。这里必须取 .message。
    const err = new Error('boom: network down');
    reportSkillFailure('skill-1', err as unknown as string);
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    expect(mcpCallMock.mock.calls[0][1].error_message).toBe('boom: network down');
  });

  it('coerces null/undefined error_message to empty string', async () => {
    reportSkillFailure('skill-1', null as unknown as string);
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    expect(mcpCallMock.mock.calls[0][1].error_message).toBe('');
  });

  it('guards against whitespace-only skillId', async () => {
    reportSkillFailure('  ', 'err');
    await new Promise((r) => setTimeout(r, 20));
    expect(mcpCallMock).not.toHaveBeenCalled();
  });

  it('does not throw when mcpCallWithRefresh rejects', async () => {
    mcpCallMock.mockRejectedValueOnce(new Error('network down'));
    expect(() => reportSkillFailure('skill-123', 'err')).not.toThrow();
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
  });

  it('does not throw when server returns ok=false', async () => {
    mcpCallMock.mockResolvedValueOnce({ ok: false, error: { code: 'device_not_bound' } });
    expect(() => reportSkillFailure('skill-123', 'err')).not.toThrow();
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
  });
});

// ── reportSkillSuccess ──────────────────────────────────

describe('reportSkillSuccess', () => {
  it('calls execution.report with success payload', async () => {
    reportSkillSuccess('skill-456', 'done output', 4500);
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    expect(mcpCallMock).toHaveBeenCalledWith('execution.report', {
      skill_id: 'skill-456',
      status: 'success',
      result: {
        success: true,
        output: 'done output',
        duration_ms: 4500,
      },
    });
  });

  it('serializes non-string output to compact JSON', async () => {
    const obj = { items: [1, 2, 3], ok: true };
    reportSkillSuccess('skill-1', obj, 100);
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    // 紧凑 JSON（无缩进空格），与原生 JSON.stringify 一致 —— 比 pretty-print
    // 在 4KB 预算内塞入更多真实数据，序列化也更快。
    expect(mcpCallMock.mock.calls[0][1].result.output).toBe(JSON.stringify(obj));
  });

  it('handles circular references without throwing', async () => {
    const obj: Record<string, unknown> = { a: 1 };
    obj.self = obj; // 循环引用
    expect(() => reportSkillSuccess('skill-1', obj, 100)).not.toThrow();
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    const output = mcpCallMock.mock.calls[0][1].result.output as string;
    expect(output).toContain('"[Circular]"');
    expect(output).toContain('"a":1');
  });

  it('does not allocate full string for huge objects (budget-aware)', async () => {
    // 构造一个远超 MAX_OUTPUT_LENGTH 的对象，验证序列化被预算截断、不抛错。
    const huge: unknown[] = [];
    for (let i = 0; i < 100000; i++) huge.push('x'.repeat(100));
    expect(() => reportSkillSuccess('skill-1', { data: huge }, 100)).not.toThrow();
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    const output = mcpCallMock.mock.calls[0][1].result.output as string;
    // 输出被预算封顶，远小于原始对象大小。
    expect(output.length).toBeLessThanOrEqual(4000 + '…[truncated]'.length);
    expect(output).toContain('…[truncated]');
  });

  it('truncates output exceeding MAX_OUTPUT_LENGTH with visible marker', async () => {
    const long = 'x'.repeat(5000);
    reportSkillSuccess('skill-1', long, 100);
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    const output = mcpCallMock.mock.calls[0][1].result.output as string;
    expect(output.length).toBeLessThan(5000);
    expect(output).toContain('…[truncated]');
  });

  it('truncates duration_ms to integer', async () => {
    reportSkillSuccess('skill-1', 'out', 4500.7);
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    expect(mcpCallMock.mock.calls[0][1].result.duration_ms).toBe(4500);
  });

  it('clamps negative duration_ms to 0', async () => {
    reportSkillSuccess('skill-1', 'out', -50);
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    expect(mcpCallMock.mock.calls[0][1].result.duration_ms).toBe(0);
  });

  it('treats NaN duration_ms as 0 (does not send null)', async () => {
    reportSkillSuccess('skill-1', 'out', NaN);
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    // 关键修复：Math.trunc(NaN)=NaN，Math.max(0,NaN)=NaN，JSON 序列化变 null。
    // 旧代码会让服务端收到 duration_ms: null，污染统计数据。
    expect(mcpCallMock.mock.calls[0][1].result.duration_ms).toBe(0);
  });

  it('treats Infinity duration_ms as 0', async () => {
    reportSkillSuccess('skill-1', 'out', Infinity);
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
    expect(mcpCallMock.mock.calls[0][1].result.duration_ms).toBe(0);
  });

  it('guards against whitespace-only skillId', async () => {
    reportSkillSuccess('   ', 'out', 100);
    await new Promise((r) => setTimeout(r, 20));
    expect(mcpCallMock).not.toHaveBeenCalled();
  });

  it('returns void (fire-and-forget)', () => {
    expect(reportSkillSuccess('skill-1', 'out', 100)).toBeUndefined();
  });

  it('guards against empty skillId', async () => {
    reportSkillSuccess('', 'out', 100);
    await new Promise((r) => setTimeout(r, 20));
    expect(mcpCallMock).not.toHaveBeenCalled();
  });

  it('does not throw when mcpCallWithRefresh rejects', async () => {
    mcpCallMock.mockRejectedValueOnce(new Error('boom'));
    expect(() => reportSkillSuccess('skill-1', 'out', 100)).not.toThrow();
    await vi.waitFor(() => expect(mcpCallMock).toHaveBeenCalledTimes(1));
  });
});

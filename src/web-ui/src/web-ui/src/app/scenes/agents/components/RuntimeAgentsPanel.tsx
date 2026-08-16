import React, { useCallback, useEffect, useState } from 'react';
import { RefreshCw, Plus, Send, Trash2, Bot, Cpu } from 'lucide-react';
import { Button, Input, Textarea, IconButton } from '@/component-library';
import {
  runtimeRegistryAPI,
  type RuntimeInstance,
  type SubAgent,
} from '@/infrastructure/api/runtimeRegistry';
import { useNotification } from '@/shared/notification-system';

interface InvokeResult {
  output: string;
  error?: string;
}

const panelStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
};

const cardStyle: React.CSSProperties = {
  border: '1px solid color-mix(in srgb, currentColor 18%, transparent)',
  borderRadius: 10,
  padding: 12,
};

const statusColor: Record<string, string> = {
  available: '#15c39a',
  busy: '#f5a623',
  offline: '#888',
};

/**
 * Self-contained Runtime Agents surface. Detects locally installed coding
 * agent CLIs (opencode / claude / codex / kimi / trae), lists the generated
 * sub-agents (`<app><n>`), lets the user invoke them, add a custom HTTP
 * agent API, spawn parallel instances, and remove agents. Does NOT touch the
 * chat dispatch core — it is an additive gallery zone.
 */
const RuntimeAgentsPanel: React.FC = () => {
  const notification = useNotification();
  const [runtimes, setRuntimes] = useState<RuntimeInstance[]>([]);
  const [subagents, setSubagents] = useState<SubAgent[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      await runtimeRegistryAPI.scan();
      const snap = await runtimeRegistryAPI.listRuntimes();
      setRuntimes(snap.instances);
      setSubagents(await runtimeRegistryAPI.listSubagents());
    } catch (e) {
      notification.error(
        `Runtime scan failed: ${e instanceof Error ? e.message : String(e)}`,
      );
    } finally {
      setLoading(false);
    }
  }, [notification]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const [prompts, setPrompts] = useState<Record<string, string>>({});
  const [running, setRunning] = useState<Record<string, boolean>>({});
  const [results, setResults] = useState<Record<string, InvokeResult>>({});

  const handleInvoke = useCallback(
    async (sa: SubAgent) => {
      const prompt = (prompts[sa.id] ?? '').trim();
      if (!prompt) {
        notification.error('Enter a prompt first');
        return;
      }
      setRunning((r) => ({ ...r, [sa.id]: true }));
      try {
        const resp = await runtimeRegistryAPI.invokeSubagent({
          subagentId: sa.id,
          prompt,
        });
        setResults((r) => ({
          ...r,
          [sa.id]: { output: resp.output, error: resp.error },
        }));
      } catch (e) {
        setResults((r) => ({
          ...r,
          [sa.id]: {
            output: '',
            error: e instanceof Error ? e.message : String(e),
          },
        }));
      } finally {
        setRunning((r) => ({ ...r, [sa.id]: false }));
      }
    },
    [prompts, notification],
  );

  const [showAdd, setShowAdd] = useState(false);
  const [addForm, setAddForm] = useState({
    name: '',
    endpoint: '',
    model: '',
    apiKey: '',
  });

  const handleAdd = useCallback(async () => {
    if (!addForm.name.trim() || !addForm.endpoint.trim()) {
      notification.error('name + endpoint required');
      return;
    }
    try {
      await runtimeRegistryAPI.addCustomAgent({
        name: addForm.name.trim(),
        endpoint: addForm.endpoint.trim(),
        model: addForm.model.trim() || undefined,
        apiKey: addForm.apiKey.trim() || undefined,
      });
      notification.success(`Added ${addForm.name}`);
      setShowAdd(false);
      setAddForm({ name: '', endpoint: '', model: '', apiKey: '' });
      await refresh();
    } catch (e) {
      notification.error(
        `Add failed: ${e instanceof Error ? e.message : String(e)}`,
      );
    }
  }, [addForm, notification, refresh]);

  const handleRemove = useCallback(
    async (sa: SubAgent) => {
      try {
        await runtimeRegistryAPI.removeAgent(sa.id);
        await refresh();
      } catch (e) {
        notification.error(e instanceof Error ? e.message : String(e));
      }
    },
    [notification, refresh],
  );

  const handleSpawn = useCallback(
    async (providerId: string) => {
      try {
        await runtimeRegistryAPI.spawnInstance(providerId);
        await refresh();
      } catch (e) {
        notification.error(e instanceof Error ? e.message : String(e));
      }
    },
    [notification, refresh],
  );

  return (
    <div style={panelStyle}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          flexWrap: 'wrap',
        }}
      >
        <Button
          size="small"
          variant="secondary"
          onClick={() => void refresh()}
          disabled={loading}
        >
          <RefreshCw size={14} style={{ marginRight: 6 }} />
          {loading ? 'Scanning…' : 'Scan / Refresh'}
        </Button>
        <Button
          size="small"
          variant="primary"
          onClick={() => setShowAdd((v) => !v)}
        >
          <Plus size={14} style={{ marginRight: 6 }} />
          Add custom agent
        </Button>
        <span style={{ opacity: 0.6, fontSize: 12 }}>
          {subagents.length} sub-agent
          {subagents.length === 1 ? '' : 's'} available
        </span>
      </div>

      {showAdd && (
        <div
          style={{
            ...cardStyle,
            display: 'flex',
            flexDirection: 'column',
            gap: 8,
          }}
        >
          <Input
            placeholder="name (e.g. my-openai)"
            value={addForm.name}
            onChange={(e) =>
              setAddForm((f) => ({ ...f, name: e.target.value }))
            }
            inputSize="small"
          />
          <Input
            placeholder="endpoint https://…/v1"
            value={addForm.endpoint}
            onChange={(e) =>
              setAddForm((f) => ({ ...f, endpoint: e.target.value }))
            }
            inputSize="small"
          />
          <div style={{ display: 'flex', gap: 8 }}>
            <Input
              placeholder="model (optional)"
              value={addForm.model}
              onChange={(e) =>
                setAddForm((f) => ({ ...f, model: e.target.value }))
              }
              inputSize="small"
              style={{ flex: 1 }}
            />
            <Input
              placeholder="api key (optional)"
              value={addForm.apiKey}
              onChange={(e) =>
                setAddForm((f) => ({ ...f, apiKey: e.target.value }))
              }
              inputSize="small"
              style={{ flex: 1 }}
            />
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <Button
              size="small"
              variant="primary"
              onClick={() => void handleAdd()}
            >
              Save
            </Button>
            <Button
              size="small"
              variant="ghost"
              onClick={() => setShowAdd(false)}
            >
              Cancel
            </Button>
          </div>
        </div>
      )}

      <div>
        <div style={{ opacity: 0.7, fontSize: 12, margin: '4px 0' }}>
          Detected runtimes
        </div>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {runtimes.length === 0 && (
            <span style={{ opacity: 0.5, fontSize: 12 }}>none detected</span>
          )}
          {runtimes.map((rt) => (
            <div
              key={rt.id}
              style={{
                ...cardStyle,
                opacity: rt.installed ? 1 : 0.55,
                minWidth: 180,
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                }}
              >
                <Cpu size={14} />
                <strong>{rt.displayName}</strong>
                <span
                  style={{
                    marginLeft: 'auto',
                    fontSize: 11,
                    opacity: 0.6,
                  }}
                >
                  {rt.installed ? rt.version ?? 'installed' : 'not installed'}
                </span>
              </div>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  marginTop: 8,
                }}
              >
                <span style={{ fontSize: 11, opacity: 0.7 }}>{rt.kind}</span>
                {rt.installed && rt.kind !== 'acp' && (
                  <Button
                    size="small"
                    variant="ghost"
                    onClick={() => void handleSpawn(rt.providerId)}
                  >
                    Spawn instance
                  </Button>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div>
        <div style={{ opacity: 0.7, fontSize: 12, margin: '8px 0 4px' }}>
          Sub-agents
        </div>
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 10,
          }}
        >
          {subagents.length === 0 && (
            <span style={{ opacity: 0.5, fontSize: 12 }}>
              no sub-agents — scan or add a custom agent
            </span>
          )}
          {subagents.map((sa) => (
            <div key={sa.id} style={cardStyle}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                }}
              >
                <Bot size={14} />
                <strong>{sa.displayName}</strong>
                <span style={{ fontSize: 11, opacity: 0.6 }}>({sa.id})</span>
                <span
                  style={{
                    marginLeft: 'auto',
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 4,
                    fontSize: 11,
                  }}
                >
                  <span
                    style={{
                      width: 8,
                      height: 8,
                      borderRadius: '50%',
                      background: statusColor[sa.status] ?? '#888',
                      display: 'inline-block',
                    }}
                  />
                  {sa.status}
                </span>
                <IconButton
                  size="small"
                  variant="ghost"
                  tooltip="Remove"
                  onClick={() => void handleRemove(sa)}
                >
                  <Trash2 size={13} />
                </IconButton>
              </div>
              <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                <Textarea
                  placeholder={`Send a task to ${sa.id}…`}
                  value={prompts[sa.id] ?? ''}
                  onChange={(e) =>
                    setPrompts((p) => ({ ...p, [sa.id]: e.target.value }))
                  }
                  rows={2}
                  style={{ flex: 1 }}
                />
                <Button
                  size="small"
                  variant="primary"
                  onClick={() => void handleInvoke(sa)}
                  disabled={running[sa.id]}
                >
                  <Send size={13} style={{ marginRight: 4 }} />
                  {running[sa.id] ? 'Running…' : 'Invoke'}
                </Button>
              </div>
              {results[sa.id] && (
                <pre
                  style={{
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                    marginTop: 8,
                    padding: 8,
                    borderRadius: 8,
                    fontSize: 12,
                    maxHeight: 240,
                    overflow: 'auto',
                    border:
                      '1px solid color-mix(in srgb, currentColor 12%, transparent)',
                    background:
                      'color-mix(in srgb, currentColor 6%, transparent)',
                  }}
                >
                  {results[sa.id].error
                    ? `ERROR: ${results[sa.id].error}`
                    : results[sa.id].output}
                </pre>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default RuntimeAgentsPanel;

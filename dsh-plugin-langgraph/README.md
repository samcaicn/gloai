# dsh-plugin-langgraph

LangGraph-based multi-agent scheduler plugin for DeepSeek Harness (DSH).

Replaces the built-in Rust `ReactLoopAgent` scheduler with a TypeScript LangGraph orchestration layer. Supports **Supervisor** and **Handoff** coordination patterns for multi-agent workflows.

## Features

- **StateGraph-based orchestration** — Agent workflows modeled as directed graphs with shared state
- **Supervisor pattern** — A coordinator delegates tasks to specialist workers sequentially
- **Handoff pattern** — Agents pass control between each other based on task needs
- **Checkpoint persistence** — File-system based checkpointer for long-running workflows
- **MCP integration** — Exposes scheduling tools via MCP for any compatible client
- **DSH runtime bridge** — Wraps DSH `ctx.tools` as LangChain StructuredTools

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                  dsh-plugin-langgraph                 │
├─────────────────────────────────────────────────────┤
│  MCP Server (server.ts)                              │
│    ├─ lg_dispatch    — run a multi-agent task        │
│    ├─ lg_stream      — stream task execution         │
│    ├─ lg_get_result  — get completed task result     │
│    ├─ lg_list_results— list all task results         │
│    └─ lg_status      — scheduler status              │
├─────────────────────────────────────────────────────┤
│  LangGraphScheduler (core.ts)                        │
│    ├─ dispatch() — invoke compiled graph             │
│    ├─ stream()   — stream graph execution           │
│    └─ results    — task result cache                │
├─────────────────────────────────────────────────────┤
│  Coordination Patterns                               │
│    ├─ supervisor/ — coordinator + workers graph     │
│    └─ handoff/    — peer-to-peer handoff graph      │
├─────────────────────────────────────────────────────┤
│  Infrastructure                                      │
│    ├─ llm/adapter.ts — DSH tools → LangChain tools  │
│    └─ persistence/   — FileSystemCheckpointer        │
└─────────────────────────────────────────────────────┘
```

## Installation

```bash
cd dsh-plugin-langgraph
npm install
npm run build
```

## Usage

### As a DSH Plugin (via cordis.patch.yml)

```yaml
- insert:
    - id: dsh-plugin-langgraph
      name: dsh-plugin-langgraph
      config:
        host: 127.0.0.1
        port: 8766
        allowSupervisor: true
        allowHandoff: true
        maxAgents: 10
```

### Standalone (via CLI)

```bash
# HTTP mode
npx dsh-plugin-langgraph --http --port 8766

# Stdio mode (for MCP clients)
npx dsh-plugin-langgraph
```

### MCP Tools

Once running, the following MCP tools are available:

| Tool | Description |
|------|-------------|
| `lg_dispatch` | Dispatch a multi-agent task (supervisor or handoff mode) |
| `lg_stream` | Stream task execution with intermediate step traces |
| `lg_get_result` | Get result of a completed task |
| `lg_list_results` | List all tracked task results |
| `lg_delete_task` | Delete a task and its checkpoint data |
| `lg_status` | Report scheduler status |

### Example: Supervisor Mode

```json
{
  "taskId": "research-task-001",
  "mode": "supervisor",
  "objective": "Research the latest developments in quantum computing",
  "subAgents": [
    {
      "name": "searcher",
      "description": "Searches for information on the web",
      "systemPrompt": "You are a research searcher. Find relevant information.",
      "tools": ["web_search"]
    },
    {
      "name": "analyst",
      "description": "Analyzes and synthesizes search results",
      "systemPrompt": "You are an analyst. Synthesize findings into insights.",
      "tools": []
    },
    {
      "name": "writer",
      "description": "Writes a comprehensive report",
      "systemPrompt": "You are a technical writer. Produce clear reports.",
      "tools": []
    }
  ],
  "maxSteps": 30
}
```

### Example: Handoff Mode

```json
{
  "taskId": "support-task-002",
  "mode": "handoff",
  "objective": "Resolve the customer billing issue",
  "subAgents": [
    {
      "name": "triage",
      "description": "Routes issues to the right team",
      "systemPrompt": "You triage support tickets. Identify the issue type.",
      "tools": ["lookup_order"],
      "canHandoffTo": ["billing", "technical"]
    },
    {
      "name": "billing",
      "description": "Handles billing-related issues",
      "systemPrompt": "You resolve billing issues. Process refunds and adjustments.",
      "tools": ["refund", "adjust_balance"],
      "canHandoffTo": ["triage"]
    },
    {
      "name": "technical",
      "description": "Handles technical issues",
      "systemPrompt": "You resolve technical issues. Guide troubleshooting.",
      "tools": ["run_diagnostics"],
      "canHandoffTo": ["triage"]
    }
  ]
}
```

## Configuration

| Env Var | Default | Description |
|---------|---------|-------------|
| `DSH_LANGGRAPH_PORT` | 8766 | HTTP port |
| `DSH_LANGGRAPH_HOST` | 127.0.0.1 | HTTP bind address |
| `DSH_LANGGRAPH_ALLOW_SUPERVISOR` | true | Enable supervisor mode |
| `DSH_LANGGRAPH_ALLOW_HANDOFF` | true | Enable handoff mode |
| `DSH_LANGGRAPH_MAX_AGENTS` | 10 | Max concurrent agents |
| `DSH_LANGGRAPH_CHECKPOINT_DIR` | `~/.dsh-langgraph/checkpoints` | Checkpoint directory |

## Development

```bash
# Type check
npm run typecheck

# Run tests
npm test

# Full check (typecheck + lint + build + test)
npm run check
```

## How It Replaces ReactLoopAgent

The built-in Rust `ReactLoopAgent` uses a queue-based turn/step loop:

```
User Message → Inbox → Turn Loop → [LLM Call → Tool Execution] × N → Done
```

This plugin replaces that with a graph-based model:

```
User Objective → StateGraph → [Supervisor/Worker Nodes] × N → Shared State → Final Output
```

Key differences:
- **Graph topology** — Explicit control flow via edges, not just inbox queue
- **Multi-agent** — Multiple specialized agents with distinct roles/tools
- **State channels** — Typed state with reducers (merge, replace, append)
- **Checkpoints** — Durable state for long-running workflows
- **Streaming** — Real-time visibility into agent execution

## License

MIT

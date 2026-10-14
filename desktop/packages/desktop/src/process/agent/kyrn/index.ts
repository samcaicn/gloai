import { AgentSideConnection, ndJsonStream } from '@agentclientprotocol/sdk';
import { Readable, Writable } from 'node:stream';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { logUncaught } from './errorLog.ts';
import { expectedHarness, findHarness, launcherOf, MIN_NODE, nodeVersionOk } from './harness.ts';
import { KyrnAgent } from './KyrnAgent.ts';
import { muHome } from './naming.ts';

// Before anything else can fail: an error nothing handled is logged (see errorLog.ts).
logUncaught();

// Run from the desktop's sources (scripts/kyrn/acp) this file knows its checkout. Bundled into the packaged app
// (out/main/mu-acp.js, see scripts/build-mcp-servers.js) there is none: `import.meta` is empty there.
const source: string | undefined = import.meta.url;
const desktopRoot =
  process.env.KYRN_DESKTOP_ROOT ||
  (source?.startsWith('file:') ? fileURLToPath(new URL('../../../../../../', source)) : undefined);
if (!nodeVersionOk(process.versions.node)) {
  process.stderr.write(
    `mu needs Node.js ${MIN_NODE.join('.')} or newer, found ${process.versions.node}. Install it from https://nodejs.org\n`
  );
  process.exit(1);
}
// No harness found: the launcher of the place it should be (in the packaged app its own copy), whose failure to
// start the app reports as mu offline.
const harness =
  findHarness(desktopRoot) ?? expectedHarness(desktopRoot, (process as { resourcesPath?: string }).resourcesPath);
let agent: KyrnAgent;
const connection = new AgentSideConnection(
  (conn) => {
    agent = new KyrnAgent(conn, launcherOf(harness, process.platform), join(muHome(), 'acp-sessions'));
    return agent;
  },
  // Node and lib.dom describe BYOB readers differently; both are WHATWG streams at runtime.
  ndJsonStream(Writable.toWeb(process.stdout), Readable.toWeb(process.stdin) as unknown as ReadableStream<Uint8Array>)
);
void connection.closed.then(() => agent.close());
for (const signal of ['SIGTERM', 'SIGINT'] as const)
  process.on(signal, () => {
    agent.close();
    process.exit(0);
  });

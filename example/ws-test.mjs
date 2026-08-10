#!/usr/bin/env node
// WebSocket test client for CEOadmin Hub
// Usage: node ws-test.mjs "ws://host:port/api/ws?key=API_KEY"
// Requires: npm install ws (or Node >= 22 with built-in WebSocket)

const url = process.argv[2];
if (!url) {
  console.log('Usage: node ws-test.mjs "ws://host:port/api/ws?key=API_KEY"');
  process.exit(1);
}

// Use built-in WebSocket (Node 22+) or fall back to ws package
let WS;
if (typeof WebSocket !== 'undefined') {
  WS = WebSocket;
} else {
  try {
    WS = (await import('ws')).WebSocket;
  } catch {
    console.error('Error: install ws package first: npm install ws');
    process.exit(1);
  }
}

console.log(`Connecting to ${url}...`);
const ws = new WS(url);

ws.addEventListener('open', () => {
  console.log('Connected!\n');
  console.log('Commands:');
  console.log('  send <user_id> <text>  — send a message');
  console.log('  ping                   — send ping');
  console.log('  quit                   — disconnect\n');
  rl.resume();
  rl.prompt();
});

ws.addEventListener('message', (event) => {
  try {
    const env = JSON.parse(typeof event.data === 'string' ? event.data : event.data.toString());
    console.log(`\n<- [${env.type}]`, JSON.stringify(env.data, null, 2));
  } catch {
    console.log('\n<- (raw)', event.data);
  }
  rl.prompt();
});

ws.addEventListener('close', () => { console.log('Disconnected'); process.exit(0); });
ws.addEventListener('error', (e) => { console.error('Error:', e.message || e); process.exit(1); });

import { createInterface } from 'readline';
const rl = createInterface({ input: process.stdin, output: process.stdout, prompt: '> ' });
rl.pause();

rl.on('line', (line) => {
  const parts = line.trim().split(/\s+/);
  const cmd = parts[0];

  if (!cmd) { rl.prompt(); return; }
  if (cmd === 'quit' || cmd === 'exit') { ws.close(); return; }

  if (cmd === 'ping') {
    ws.send(JSON.stringify({ type: 'ping' }));
    console.log('-> ping');
  } else if (cmd === 'send' && parts.length >= 3) {
    const toUser = parts[1];
    const text = parts.slice(2).join(' ');
    ws.send(JSON.stringify({
      type: 'send_text',
      req_id: `req-${Date.now()}`,
      data: { to_user_id: toUser, text },
    }));
    console.log(`-> send to=${toUser} text="${text}"`);
  } else {
    console.log('Commands: send <user_id> <text> | ping | quit');
  }
  rl.prompt();
});

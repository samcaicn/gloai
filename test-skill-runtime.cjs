// Standalone test for the skill runtime.
// Loads skillRuntime.js directly and exercises its built-in
// action handling (script, set, log, sleep, throw, noop, runtime.set).
// Mocks `cap` and `mid` so we can assert the values that flow
// through the runtime end-to-end.

const fs = require('fs');
const path = require('path');

// ── Mock `cap` ──────────────────────────────────────────────────────
const capLogCalls = [];
const cap = {
  runtime: {
    log: (tag, msg) => capLogCalls.push({ tag, msg }),
    sleep: async (ms) => { /* no-op for tests */ },
  },
  app: {
    resolve: async (app, doAction, p) => {
      return { app, doAction, p };
    },
  },
};

// ── Mock `mid` ──────────────────────────────────────────────────────
const midCalls = [];
const midHandlers = {};
const mid = {
  exec: async (p) => {
    midCalls.push(p);
    // If a handler is registered for this action, run it.
    if (midHandlers[p.action || p.type]) {
      return midHandlers[p.action || p.type](p);
    }
    return { ok: true, p };
  },
  register: (name, handler) => { midHandlers[name] = handler; },
  _lastCtx: null,
};

// ── Load skillRuntime.js as a string and eval it with the mocks ─────
// skillRuntime.js uses `cap`, `mid`, `skillRun` globals.
global.cap = cap;
global.mid = mid;

const src = fs.readFileSync(
  path.join(__dirname, 'src-tauri/src/skills/skillRuntime.js'),
  'utf8'
);

// We need to expose skillRun globally for the test.
const wrapped = src + '\nglobal.skillRun = skillRun;';
eval(wrapped);

// ── Test cases ──────────────────────────────────────────────────────

async function test1_set_falsy_value() {
  // BUG: `runtime.set` action checks `p.value` for truthiness, so
  // setting a var to `false`, `0`, `""`, or `null` silently fails.
  const skill = {
    steps: [
      { id: 's1', action: 'runtime.set', var: 'flag', value: false },
      { id: 's2', action: 'runtime.set', var: 'count', value: 0 },
      { id: 's3', action: 'runtime.set', var: 'name', value: '' },
      { id: 's4', action: 'runtime.set', var: 'data', value: null },
    ],
  };

  const result = await global.skillRun.run(skill, {});
  console.log('test1 result.vars:', result.vars);
  console.log('test1 result.steps:', result.steps);

  // Document the bug
  const flagOk = result.vars.flag === false;
  const countOk = result.vars.count === 0;
  const nameOk = result.vars.name === '';
  const dataOk = result.vars.data === null;

  if (!flagOk) console.log('  ❌ BUG: setting var to `false` silently failed');
  if (!countOk) console.log('  ❌ BUG: setting var to `0` silently failed');
  if (!nameOk) console.log('  ❌ BUG: setting var to `""` silently failed');
  if (!dataOk) console.log('  ❌ BUG: setting var to `null` silently failed');
}

async function test2_set_truthy_value() {
  // Sanity check: truthy values work
  const skill = {
    steps: [
      { id: 's1', action: 'runtime.set', var: 'name', value: 'Alice' },
      { id: 's2', action: 'runtime.set', var: 'count', value: 42 },
    ],
  };
  const result = await global.skillRun.run(skill, {});
  console.log('test2 result.vars:', result.vars);
  if (result.vars.name !== 'Alice') console.log('  ❌ name not set');
  if (result.vars.count !== 42) console.log('  ❌ count not set');
}

async function test3_topological_sort_depends() {
  // Steps with `depends` should run in order
  const skill = {
    steps: [
      { id: 'third', action: 'log', text: '3', depends: 'second' },
      { id: 'first', action: 'log', text: '1' },
      { id: 'second', action: 'log', text: '2', depends: 'first' },
    ],
  };
  const result = await global.skillRun.run(skill, {});
  // steps[0].id should be 'first', steps[1].id should be 'second', steps[2].id should be 'third'
  const orderOk =
    result.steps[0].id === 'first' &&
    result.steps[1].id === 'second' &&
    result.steps[2].id === 'third';
  console.log('test3 step order:', result.steps.map(s => s.id).join(','));
  if (!orderOk) console.log('  ❌ BUG: topological sort order wrong');
}

async function test4_foreach_non_array() {
  // If foreach resolves to a non-array, the loop silently does nothing
  const skill = {
    steps: [
      { id: 'f1', foreach: 'not-an-array', do: [
        { id: 'f1.0', action: 'log', text: 'iter' }
      ]}
    ],
  };
  const result = await global.skillRun.run(skill, {});
  // midCalls should be empty (no log was called)
  const iterCalled = midCalls.some(c => c.action === 'log' && c.text === 'iter');
  console.log('test4 midCalls for foreach non-array:', iterCalled);
  if (!iterCalled) console.log('  ⚠ foreach on non-array silently does nothing (might be a bug)');
}

async function test5_set_action_with_template() {
  // set action with template rendering
  const skill = {
    steps: [
      { id: 's1', action: 'set', var: 'greeting', value: 'Hello ${params.name}' },
    ],
  };
  const result = await global.skillRun.run(skill, { name: 'Bob' });
  console.log('test5 result.vars:', result.vars);
  if (result.vars.greeting !== 'Hello Bob') {
    console.log('  ❌ template render in set action failed');
  }
}

async function test6_log_resolves_templates() {
  // log action should resolve ${...} templates
  capLogCalls.length = 0;
  const skill = {
    steps: [
      { id: 'l1', action: 'log', text: 'Value is ${vars.x}' },
    ],
  };
  await global.skillRun.run(skill, {});
  await global.skillRun.run({ steps: [{ id: 'l2', action: 'set', var: 'x', value: '42' }, { id: 'l3', action: 'log', text: 'Value is ${vars.x}' }] }, {});
  console.log('test6 log calls:', capLogCalls);
  const lastLog = capLogCalls[capLogCalls.length - 1];
  if (!lastLog || !lastLog.msg.includes('Value is 42')) {
    console.log('  ❌ log template render failed');
  }
}

async function test7_topological_missing_dep() {
  // A step with depends on a non-existent step should... what?
  const skill = {
    steps: [
      { id: 'a', action: 'log', text: 'A', depends: 'nonexistent' },
      { id: 'b', action: 'log', text: 'B' },
    ],
  };
  const result = await global.skillRun.run(skill, {});
  // If missing dep silently treated as no dep, 'a' runs immediately
  console.log('test7 step order:', result.steps.map(s => s.id).join(','));
}

async function test8_topological_input_mutation() {
  // Calling run() twice with the same skillDef — does _idx leak?
  const skill = {
    steps: [
      { id: 'a', action: 'log', text: 'A' },
      { id: 'b', action: 'log', text: 'B', depends: 'a' },
    ],
  };
  const r1 = await global.skillRun.run(skill, {});
  console.log('test8 r1 order:', r1.steps.map(s => s.id).join(','));
  const r2 = await global.skillRun.run(skill, {});
  console.log('test8 r2 order:', r2.steps.map(s => s.id).join(','));
  console.log('test8 first step _idx:', skill.steps[0]._idx);
}

async function test9_foreach_silent_on_non_array() {
  // foreach with a non-array: should it error or run once?
  capLogCalls.length = 0;
  const skill = {
    steps: [
      { id: 'f1', foreach: 'single-string', do: [
        { id: 'inner', action: 'log', text: 'inner' }
      ]},
    ],
  };
  const r = await global.skillRun.run(skill, {});
  console.log('test9 capLogCalls after non-array foreach:', capLogCalls.length);
  // BUG: silent no-op. Should at least warn.
}

async function test10_set_in_if_branch() {
  // Does set work inside an if/else branch (at step level)?
  const skill = {
    steps: [
      { id: 'outer', if: 'true', then: { id: 'inner', action: 'set', var: 'x', value: 42 } },
    ],
  };
  const r = await global.skillRun.run(skill, {});
  console.log('test10 r.vars.x:', r.vars.x);
  if (r.vars.x !== 42) console.log('  ❌ set in if/else branch failed');
}

async function test11_recursive_run() {
  // Nested skill definitions (skill within skill)
  // For now, just test that reentrant calls don't pollute each other.
  const skill1 = {
    steps: [
      { id: 's1a', action: 'set', var: 'name', value: 'first' },
    ],
  };
  const skill2 = {
    steps: [
      { id: 's2a', action: 'set', var: 'name', value: 'second' },
    ],
  };
  const r1 = await global.skillRun.run(skill1, {});
  const r2 = await global.skillRun.run(skill2, {});
  console.log('test11 r1.vars.name:', r1.vars.name);
  console.log('test11 r2.vars.name:', r2.vars.name);
}

async function test12_set_nested_object() {
  // Can we set a var to an object?
  const skill = {
    steps: [
      { id: 's1', action: 'set', var: 'config', value: { a: 1, b: 2 } },
    ],
  };
  const r = await global.skillRun.run(skill, {});
  console.log('test12 r.vars.config:', JSON.stringify(r.vars.config));
}

async function test13_template_within_set_value() {
  // Template rendering in set value
  const skill = {
    steps: [
      { id: 's1', action: 'set', var: 'x', value: 5 },
      { id: 's2', action: 'set', var: 'doubled', value: '${vars.x} * 2 = ${vars.x * 2}' },
    ],
  };
  const r = await global.skillRun.run(skill, {});
  console.log('test13 r.vars.doubled:', r.vars.doubled);
}

async function main() {
  await test1_set_falsy_value();
  await test2_set_truthy_value();
  await test3_topological_sort_depends();
  await test4_foreach_non_array();
  await test5_set_action_with_template();
  await test6_log_resolves_templates();
  await test7_topological_missing_dep();
  await test8_topological_input_mutation();
  await test9_foreach_silent_on_non_array();
  await test10_set_in_if_branch();
  await test11_recursive_run();
  await test12_set_nested_object();
  await test13_template_within_set_value();
  console.log('---');
  console.log('Total capLogCalls:', capLogCalls.length);
  console.log('Total midCalls:', midCalls.length);
}

main().catch(e => { console.error(e); process.exit(1); });

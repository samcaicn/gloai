// Tests for the CEO kickoff's "tool usage" mandate (task 62386ffc).
//
// The CEO must use its tools (board_list_tasks, mail_spawn_agent, mail_send,
// mail_stop_self) for every action and must NEVER hand-parse JSON or fabricate
// tool I/O. These assertions pin that mandate directly against ceoKickoff()
// — no daemon, no tmux, no network — so the contract holds regardless of the
// surrounding CEO-config plumbing (which is tested separately in ceo.test.mjs).
//
// Run: npm test

import { test } from "node:test";
import assert from "node:assert/strict";
import { ceoKickoff } from "../extensions/lib/ceo.mjs";

const KICKOFF = ceoKickoff(["/tmp/managed-proj"]);

test("ceoKickoff names the favorited project + group (no regression)", () => {
  assert.ok(KICKOFF.includes("/tmp/managed-proj"), "kickoff names the project cwd");
  assert.ok(KICKOFF.includes("group: managed-proj"), "kickoff names the project group");
});

test("ceoKickoff requires using tools for every action (62386ffc)", () => {
  assert.match(KICKOFF, /you MUST use your tools/i, "kickoff explicitly requires tool use");
  // Every tool the CEO is allowed to use is named in the kickoff.
  for (const tool of ["board_list_tasks", "mail_spawn_agent", "mail_send", "mail_stop_self"]) {
    assert.ok(KICKOFF.includes(tool), `kickoff names the ${tool} tool`);
  }
  // Spawning an MM is the CEO's escalation mechanism — must stay (a64c902d sliver:
  // "document the tool better so the CEO knows to email the agent after spawning").
  assert.match(KICKOFF, /mail_spawn_agent.*mm: true|spawn.*middle manager/i, "kickoff documents spawning an MM via mail_spawn_agent({ cwd, mm: true })");
});

test("ceoKickoff forbids hand-parsing JSON + fabricating tool I/O (62386ffc)", () => {
  assert.match(KICKOFF, /never hand-parse JSON/i, "kickoff forbids hand-parsing JSON");
  assert.match(KICKOFF, /fabricate tool I\/O/i, "kickoff forbids fabricating tool I/O");
  // Concrete anti-patterns are called out so the CEO can't interpret around the rule.
  assert.match(KICKOFF, /JSON\.parse/i, "kickoff calls out JSON.parse as forbidden");
  assert.match(KICKOFF, /do not.*invent.*tool.*output|never.*fabricate/i, "kickoff forbids inventing tool output");
  assert.match(KICKOFF, /ACTUALLY returned|actually returned/i, "kickoff requires acting only on real tool output");
});

test("ceoKickoff still instructs mailing human + self-exit (no regression)", () => {
  assert.ok(KICKOFF.includes("human"), "kickoff instructs mailing human");
  assert.ok(KICKOFF.includes("mail_stop_self"), "kickoff instructs calling mail_stop_self");
  assert.match(KICKOFF, /no.*task administration|do not.*move|do not.*archive/i, "kickoff still forbids task administration");
});

test("ceoKickoff instructs overseeing ALL board groups, not only favorites", () => {
  // The CEO must review every board group, not just the favorited set.
  assert.match(KICKOFF, /ALL board groups|every board group|every other group/i, "kickoff states all-groups oversight");
  assert.match(KICKOFF, /not only favorites|not just the favorited|not.*favorites list/i, "kickoff says oversight is not limited to favorites");
  // Favorites are framed as the additive always-managed baseline.
  assert.match(KICKOFF, /always-managed baseline/i, "kickoff frames favorites as the always-managed baseline");
  // A non-favorited group with active tasks must still get an MM.
  assert.match(KICKOFF, /non-favorited group with active tasks|unfavorited group with on-board tasks/i, "kickoff covers non-favorited groups with tasks");
  // board_list_tasks must be used with all-groups visibility to enumerate groups.
  assert.match(KICKOFF, /all-groups visibility/i, "kickoff tells the CEO to use board_list_tasks all-groups visibility");
  assert.match(KICKOFF, /group.*field|distinct group/i, "kickoff tells the CEO to group tasks by their group field");
});

test("ceoKickoff with empty favorites still instructs all-groups oversight", () => {
  const k = ceoKickoff([]);
  // No favorited project is listed, but all-groups oversight still applies.
  assert.match(k, /No favorited projects this cycle/i, "kickoff notes the empty favorites baseline");
  assert.match(k, /still review every other group|You still review/i, "kickoff still requires reviewing non-favorited groups");
  assert.match(k, /ALL board groups|every board group/i, "kickoff keeps all-groups scope with empty favorites");
  // The CEO must still be told how to discover a non-favorited project's cwd.
  assert.match(k, /mail_list_agents.*cwd|connected agent.*cwd/i, "kickoff tells the CEO to find cwds via mail_list_agents");
  assert.match(k, /mail_list_projects/i, "kickoff tells the CEO to find cwds via mail_list_projects");
});

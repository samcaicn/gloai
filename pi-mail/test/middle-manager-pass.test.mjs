// Tests for the middle-manager kickoff's "full pass over all columns" mandate
// (task 0c7e3fd0).
//
// Bug: the MM exited after a single action (e.g. moved one Review task to Done)
// instead of completing a full pass over the whole board. Root cause: the MM
// skill/kickoff framed the pass as "one short pass" with no explicit
// loop-over-all-columns instruction, so the MM read "one pass" as "one step".
//
// Fix: mmKickoff() now states a pass = iterate EVERY task in EVERY column
// (Refine, To Do, In Progress, Review, Done) before exiting — not a single
// action. This test pins that mandate directly against mmKickoff() — no daemon,
// no tmux, no network — so the contract holds regardless of the surrounding
// MM-config plumbing (which is tested separately in middle-manager.test.mjs).
//
// Also audits the CEO kickoff for the same framing gap (the CEO must consider
// every managed project before exiting, not stop after the first) — covered by
// the ceoKickoff assertions at the end.
//
// Run: npm test

import { test } from "node:test";
import assert from "node:assert/strict";
import { mmKickoff } from "../extensions/lib/middle-manager.mjs";
import { ceoKickoff } from "../extensions/lib/ceo.mjs";

const MM_KICKOFF = mmKickoff(["/tmp/managed-proj"]);

test("mmKickoff names the favorited project + group (no regression)", () => {
  assert.ok(MM_KICKOFF.includes("/tmp/managed-proj"), "kickoff names the project cwd");
  assert.ok(MM_KICKOFF.includes("group: managed-proj"), "kickoff names the project group");
});

test("mmKickoff states a pass is a FULL pass, not one action (0c7e3fd0)", () => {
  assert.match(MM_KICKOFF, /FULL pass/i, "kickoff explicitly calls out a FULL pass");
  assert.match(MM_KICKOFF, /NOT one action/i, "kickoff states a pass is NOT one action");
  assert.match(MM_KICKOFF, /every task in every column/i, "kickoff instructs iterating every task in every column");
  // The MM must not stop after the first action — it must go back and finish.
  assert.match(MM_KICKOFF, /stop after the first/i, "kickoff warns against stopping after the first action");
});

test("mmKickoff iterates EVERY column in order (0c7e3fd0)", () => {
  // All five board columns must be named so the MM walks each one.
  for (const col of ["Refine", "To Do", "In Progress", "Review", "Done"]) {
    assert.ok(MM_KICKOFF.includes(col), `kickoff names the ${col} column`);
  }
});

test("mmKickoff has a 'before you finish' check that re-blocks early exit (0c7e3fd0)", () => {
  // The kickoff must include a final gate that makes the MM confirm it
  // considered every task in every column before self-exiting.
  assert.match(MM_KICKOFF, /before you finish/i, "kickoff has a 'before you finish' gate");
  assert.match(MM_KICKOFF, /every task in every column/i, "finish-gate references every task in every column");
});

test("mmKickoff still instructs mailing human + self-exit (no regression)", () => {
  assert.ok(MM_KICKOFF.includes("human"), "kickoff instructs mailing human");
  assert.ok(MM_KICKOFF.includes("mail_stop_self"), "kickoff instructs calling mail_stop_self");
  // The no-task-admin / pure-manager framing must survive.
  assert.match(MM_KICKOFF, /do NOT implement/i, "kickoff still frames the MM as a pure manager");
});

test("ceoKickoff also frames a FULL pass over every board group (audit, 0c7e3fd0)", () => {
  // The CEO has the same framing gap: it could stop after the first group.
  // The CEO kickoff must instruct considering every board group (the CEO
  // oversees ALL board groups, not only the favorited baseline).
  const CEO = ceoKickoff(["/tmp/managed-proj"]);
  assert.match(CEO, /FULL pass/i, "CEO kickoff explicitly calls out a FULL pass");
  assert.match(CEO, /every board group/i, "CEO kickoff references every board group");
  assert.match(CEO, /not one action/i, "CEO kickoff states a pass is NOT one action");
});

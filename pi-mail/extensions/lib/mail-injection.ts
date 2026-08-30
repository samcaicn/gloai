/**
 * Mail → agent context injection helpers for the pi-mail extension.
 *
 * Two pure builders extracted from index.ts so the extension entry point stays
 * under the file-size budget. Neither touches connection/closure state — they
 * take inputs and return the prompt/message objects the extension emits.
 *
 * - buildBeforeStartGuidance: the identity + channel + unread-mail guidance
 *   appended to the system prompt (and optional visible message) each turn.
 * - formatIncomingMailContent: the human-readable body injected as a steering
 *   message when a new (non-newSession) mail arrives.
 */
import type { MailMessage } from "./mail-client.js";
import type { ExtensionContext } from "@earendil-works/pi-coding-agent";

export interface MailGuidanceOpts {
  agentName: string;
  nameCustomized: boolean;
  agentStatus: string;
  mailTaskSender: { name: string; id: string } | null;
  unread: MailMessage[];
}

/** Render the pi-mail status-bar segment (offline / unread badge / idle). */
export function renderMailStatus(c: ExtensionContext, opts: { connected: boolean; mailbox: MailMessage[]; agentName: string }): void {
  const { connected, mailbox, agentName } = opts;
  if (!connected) {
    c.ui.setStatus("pi-mail", c.ui.theme.fg("dim", "✉ offline"));
    return;
  }
  const unread = mailbox.filter((m) => !m.read).length;
  if (unread > 0) {
    c.ui.setStatus("pi-mail", c.ui.theme.fg("accent", `📬 ${unread}`) + c.ui.theme.fg("dim", ` ${agentName}`));
  } else {
    c.ui.setStatus("pi-mail", c.ui.theme.fg("dim", "✉") + c.ui.theme.fg("dim", ` ${agentName}`));
  }
}

/** Build the before_agent_start system-prompt additions + optional message. */
export function buildBeforeStartGuidance(
  systemPrompt: string,
  opts: MailGuidanceOpts,
): { systemPrompt: string; message?: { customType: string; content: string; display: boolean } } {
  const { agentName, nameCustomized, agentStatus, mailTaskSender, unread } = opts;

  // Always nudge the agent (per task) to maintain an identity + status so an
  // orchestrator can tell who is doing what. systemPrompt-only — no visible
  // message — to avoid noise on every turn.
  const identityGuidance =
    `\n\n## Mail Federation\n` +
    `You are part of a federated agent network (pi-mail). An orchestrator and other agents can see you via mail_list_agents.\n` +
    `- Your display name: "${agentName}"${nameCustomized ? "" : " (auto-generated slug — set a short descriptive name with mail_set_name)"}.\n` +
    `- Your current status: ${agentStatus ? `"${agentStatus}"` : "(not set)"}.\n` +
    `\n**Status rules — follow these strictly:**\n` +
    `1. When you start a task: set status to a one-line description, e.g. "implementing auth refactor in portal-web".\n` +
    `2. When you finish or go idle: set status to "idle" or clear it.\n` +
    `3. Update status whenever your focus shifts to something meaningfully different.\n` +
    `4. Keep it short (<60 chars) and factual — branch name, issue key, and action are ideal.\n` +
    `Do NOT skip status updates — the orchestrator relies on them to coordinate work.\n` +
    `\nThe federation also has a shared kanban task board (optionally synced two-way with a Jira sprint). ` +
    `Tools: board_list_tasks, board_get_task, board_move_task, board_comment_task, board_progress_task, board_assign_task, board_create_task, board_split_task, board_update_task, board_flag_task. ` +
    `If a task is assigned to you (you'll get it as mail), work it via these tools: move it as you progress, post progress updates (board_progress_task) before moving it onward, comment on findings, and follow any column instructions. ` +
    `If a task is unclear, flag it with board_flag_task (with your questions) instead of guessing; if it's too big, subdivide it with board_split_task. A daemon nudge will mail you if an in-progress task of yours goes quiet for a while — reply with board_progress_task.`;

  // Tell the agent which channel the current task arrived on, so it knows
  // whether to reply via mail (operator/agent not at the TUI) or respond in
  // place. mailTaskSender is set when a mail triggers the turn and cleared
  // when the operator types directly in the TUI (see the `input` handler).
  const channelGuidance = mailTaskSender
    ? (
      `\n\n## Current task channel: mail\n` +
      `This task was dispatched to you via pi-mail from "${mailTaskSender.name}" (${mailTaskSender.id.slice(0, 8)}). ` +
      `The operator is NOT sitting at your TUI — they only see output you send as mail.\n` +
      `- When the task is complete: reply with \`mail_send\` to "${mailTaskSender.name}" with a concise summary, then archive the original with \`mail_mark_read\`.\n` +
      `- If you have a question or hit a blocker: ask via \`mail_send\` to "${mailTaskSender.name}". Do NOT use the \`ask_user_question\` tool — there is no one at the TUI to answer it.\n` +
      (mailTaskSender.name === "human"
        ? `- "human" is the operator via the web UI; replies to "human" appear in their inbox.\n`
        : `- "${mailTaskSender.name}" is another agent in the federation.\n`)
    )
    : (
      `\n\n## Current task channel: direct (TUI)\n` +
      `The operator is communicating with you directly over the TUI. Do NOT send mail (\`mail_send\` / \`mail_broadcast\`) to report on this task — respond here directly. ` +
      `You may use the \`ask_user_question\` tool when you need clarification. ` +
      `Only reach for the mail tools if you are participating in a federated multi-agent workflow (see the mail-orchestrator skill).`
    );

  const baseSystemPrompt = systemPrompt + identityGuidance + channelGuidance;

  if (unread.length === 0) {
    return { systemPrompt: baseSystemPrompt };
  }

  const plural = unread.length === 1 ? "" : "s";
  const broadcasts = unread.filter((m) => m.broadcast);
  const broadcastNote = broadcasts.length > 0
    ? ` (${broadcasts.length} of which ${broadcasts.length === 1 ? "is" : "are"} a broadcast — only act on those if they concern you)`
    : "";
  return {
    message: {
      customType: "pi-mail",
      content:
        `📬 You have **${unread.length}** unread mail message${plural}${broadcastNote}. ` +
        `Use \`mail_list\` to see your inbox, \`mail_read\` to read, ` +
        `\`mail_send\` to reply, \`mail_broadcast\` to reach all agents, ` +
        `and \`mail_mark_read\` to archive.`,
      display: true,
    },
    systemPrompt:
      baseSystemPrompt +
      `\n\nYou currently have ${unread.length} unread mail message${plural}${broadcastNote}. ` +
      `Check your inbox with mail_list when relevant to the current task.`,
  };
}

/** Format a newly-arrived (non-newSession) mail as the steering-message body. */
export function formatIncomingMailContent(msg: MailMessage): string {
  const time = new Date(msg.timestamp).toLocaleString();
  const header = msg.broadcast
    ? `📡 **Broadcast** from **${msg.fromName}** (${msg.fromId.slice(0, 8)}): "${msg.subject}"`
    : `📬 **Mail** from **${msg.fromName}** (${msg.fromId.slice(0, 8)}): "${msg.subject}"`;
  const footer = msg.broadcast
    ? `This is a broadcast message. Only take action if this concerns you.`
    : `Please handle this mail and use \`mail_mark_read\` to archive it when done. ` +
      `This is a mail-driven task: the operator is not at your TUI. ` +
      `When complete (or if you have a question), reply to **${msg.fromName}** via \`mail_send\` — do NOT use \`ask_user_question\`.`;
  return [
    header,
    `Date: ${time} | ID: ${msg.id.slice(0, 8)}`,
    ``,
    msg.body,
    ``,
    footer,
  ].join("\n");
}

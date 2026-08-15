"""File-based collaboration substrate (`comms`).

Provides the runtime mailbox for inter-role messaging. Each project/session gets a
`comms/` subtree under its workspace; inside it, `inbox/`, `handoffs/`
and `meetings/` are siblings, each holding per-role subdirectories.

Layout::

    <workspace>/.opc-comms/<project_id>/<session_id>/
    ├── inbox/
    │   ├── ceo/
    │   │   ├── new/      # unread messages addressed to ceo
    │   │   ├── seen/     # archived after broker / agent marks them read
    │   │   └── outbox/   # mirror of messages ceo has sent (audit + dedupe)
    │   ├── cto/
    │   └── ...
    ├── meetings/
    │   └── <meeting_id>/
    │       ├── manifest.yaml
    │       └── transcript.md
    └── ../_shared/
        └── team_memory/
            └── TEAM_MEMORY.md

Design principles:

* Each message is its own file. There is no shared file the producer
  and consumer have to lock — atomicity comes from `os.rename()` from
  a per-role `.tmp/` directory into `new/`.
* Marking-as-read is `mv new/x.md seen/x.md`, also atomic.
* Blocking messages (`blocking: true` in frontmatter) carry a marker
  the broker watches for; the reactivation scheduler treats them as
  "stop sender, run receiver" instead of "let sender keep going".
* Convergence is *prompt-driven*, not enforced by hard counters: the
  prompt rules tell agents to only reply when they need confirmation
  / changes, so the natural fixed point is "no new messages → no new
  turns". A `followup_round` counter is recorded as anomaly telemetry
  but never used as a hard cutoff.

This module is intentionally pure: it does no DB I/O, no async I/O,
no network. Callers (`company_mode`, `external_broker`, the engine
scheduler) wire it into the lifecycle.
"""

from __future__ import annotations

import contextlib
import json
import os
import re
import shutil
import tempfile
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from opc.layer4_tools.output_budget import clip_text

try:
    import fcntl  # POSIX only — used for transcript append locking
except Exception:  # pragma: no cover - Windows fallback
    fcntl = None  # type: ignore

try:
    import yaml  # type: ignore
except Exception:  # pragma: no cover - yaml is in core deps but stay defensive
    yaml = None  # type: ignore


# ──────────────────────────────────────────────────────────────────────
# Constants
# ──────────────────────────────────────────────────────────────────────

COMMS_ROOT_NAME = ".opc-comms"
INBOX_DIRNAME = "inbox"
MEETINGS_DIRNAME = "meetings"
SHARED_DIRNAME = "_shared"
TEAM_MEMORY_DIRNAME = "team_memory"
TEAM_MEMORY_ENTRYPOINT = "TEAM_MEMORY.md"
_TEAM_MEMORY_SCAFFOLD = (
    "# Team Memory\n\n"
    "Use this notebook for durable shared team state:\n"
    "- Current conclusions\n"
    "- Active risks\n"
    "- Decisions\n"
    "- Open questions\n"
    "- Important constraints\n"
)
SCRATCHPAD_ENTRYPOINT = "scratchpad.md"

NEW_DIRNAME = "new"
SEEN_DIRNAME = "seen"
OUTBOX_DIRNAME = "outbox"
TMP_DIRNAME = ".tmp"

_FRONTMATTER_RE = re.compile(
    r"^---\s*\n(?P<front>.*?)\n---\s*\n?(?P<body>.*)$",
    re.DOTALL,
)

# Filename: <iso_ts>__<from>__<to>__<short_uuid>.md
_FILENAME_TEMPLATE = "{ts}__{from_role}__{to_role}__{uid}.md"
_SAFE_NAME_RE = re.compile(r"[^A-Za-z0-9._-]+")


# ──────────────────────────────────────────────────────────────────────
# Dataclasses
# ──────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class CommsLayout:
    """Resolved comms paths for a (workspace, project, session) triple.

    All directories are absolute. The layout object is cheap to
    construct and immutable; create one per (project, session) inside
    each function that needs it rather than caching across requests.
    """

    workspace_root: Path
    project_id: str
    session_id: str
    root: Path
    inbox_root: Path
    meetings_root: Path
    shared_root: Path

    def role_inbox(self, role_id: str) -> Path:
        return self.inbox_root / _safe(role_id)

    def role_new_dir(self, role_id: str) -> Path:
        return self.role_inbox(role_id) / NEW_DIRNAME

    def role_seen_dir(self, role_id: str) -> Path:
        return self.role_inbox(role_id) / SEEN_DIRNAME

    def role_outbox_dir(self, role_id: str) -> Path:
        return self.role_inbox(role_id) / OUTBOX_DIRNAME

    def role_tmp_dir(self, role_id: str) -> Path:
        return self.role_inbox(role_id) / TMP_DIRNAME

    def meeting_dir(self, meeting_id: str) -> Path:
        return self.meetings_root / _safe(meeting_id)

    @property
    def team_memory_root(self) -> Path:
        return self.shared_root / TEAM_MEMORY_DIRNAME

    @property
    def team_memory_path(self) -> Path:
        return self.team_memory_root / TEAM_MEMORY_ENTRYPOINT

    @property
    def scratchpad_path(self) -> Path:
        return self.shared_root / SCRATCHPAD_ENTRYPOINT


@dataclass
class MessageHeader:
    """Lightweight metadata for a single message file.

    Read from frontmatter only — body is loaded on demand. Used for
    inbox listings and prompt injection (where we want titles + age,
    not bodies).
    """

    path: Path
    message_id: str
    from_role: str
    to_role: str
    subject: str
    sent_at: str
    blocking: bool = False
    reply_to: str | None = None
    priority: str = "normal"
    tags: list[str] = field(default_factory=list)
    raw_frontmatter: dict[str, Any] = field(default_factory=dict)


# ──────────────────────────────────────────────────────────────────────
# Layout resolution / bootstrap
# ──────────────────────────────────────────────────────────────────────


def resolve_layout(
    workspace_root: str | Path,
    project_id: str,
    session_id: str,
) -> CommsLayout:
    """Compute the comms layout paths for a workspace + project + session.

    Does NOT create directories — call `ensure_layout` for that.
    """
    ws = Path(workspace_root).expanduser().resolve()
    pid = _safe(project_id or "default")
    sid = _safe(session_id or "default")
    root = ws / COMMS_ROOT_NAME / pid / sid
    return CommsLayout(
        workspace_root=ws,
        project_id=pid,
        session_id=sid,
        root=root,
        inbox_root=root / INBOX_DIRNAME,
        meetings_root=root / MEETINGS_DIRNAME,
        # Shared memory is session-scoped so each runtime run keeps its own
        # durable team notebook without bleeding conclusions across unrelated
        # sessions in the same project.
        shared_root=root / SHARED_DIRNAME,
    )


def ensure_layout(
    layout: CommsLayout,
    roles: Iterable[str],
) -> None:
    """Create the directory tree for the given roles, idempotently.

    Also (re)writes README.md. Safe to call on every work-item start —
    pure mkdir + small file writes.
    """
    layout.root.mkdir(parents=True, exist_ok=True)
    layout.inbox_root.mkdir(parents=True, exist_ok=True)
    layout.meetings_root.mkdir(parents=True, exist_ok=True)
    layout.shared_root.mkdir(parents=True, exist_ok=True)
    layout.team_memory_root.mkdir(parents=True, exist_ok=True)
    for role in roles:
        if not role:
            continue
        layout.role_new_dir(role).mkdir(parents=True, exist_ok=True)
        layout.role_seen_dir(role).mkdir(parents=True, exist_ok=True)
        layout.role_outbox_dir(role).mkdir(parents=True, exist_ok=True)
        layout.role_tmp_dir(role).mkdir(parents=True, exist_ok=True)
    _write_readme(layout)
    _ensure_team_memory_entrypoint(layout)


# ──────────────────────────────────────────────────────────────────────
# Sending
# ──────────────────────────────────────────────────────────────────────


def send_message(
    layout: CommsLayout,
    *,
    from_role: str,
    to_role: str,
    subject: str,
    body: str,
    blocking: bool = False,
    reply_to: str | None = None,
    priority: str = "normal",
    tags: Iterable[str] | None = None,
    idempotency_key: str | None = None,
    message_id: str | None = None,
    sent_at: str | None = None,
    extra_frontmatter: dict[str, Any] | None = None,
) -> Path:
    """Atomically deliver a message to `to_role`'s inbox.

    Writes to `<from_role outbox>/.tmp/<file>` first, fsync's, then
    `os.rename()` into `<to_role inbox>/new/<file>`. Returns the
    final path. Also leaves a copy in the sender's outbox for audit
    and idempotency-key based deduplication.

    `blocking=True` carries a marker the broker uses to decide
    whether the sender's task should be parked until the receiver
    produces a reply. The receiver-side prompt explains the semantics.
    """
    from_role = _safe(from_role)
    to_role = _safe(to_role)
    if not from_role or not to_role:
        raise ValueError("send_message requires both from_role and to_role")

    # Make sure target dirs exist even if ensure_layout was never called
    # for the target role yet (e.g. msg arrives before that work item starts).
    layout.role_new_dir(to_role).mkdir(parents=True, exist_ok=True)
    layout.role_outbox_dir(from_role).mkdir(parents=True, exist_ok=True)
    layout.role_tmp_dir(from_role).mkdir(parents=True, exist_ok=True)

    # Idempotency: if sender already produced a message with this
    # key, return the receiver-side path (new/ if still unread, seen/
    # if already archived) instead of writing a duplicate. We scan
    # the sender's outbox (small per work item) for the key.
    if idempotency_key:
        existing_outbox = _find_in_outbox(layout, from_role, idempotency_key)
        if existing_outbox is not None:
            new_candidate = layout.role_new_dir(to_role) / existing_outbox.name
            if new_candidate.exists():
                return new_candidate
            seen_candidate = layout.role_seen_dir(to_role) / existing_outbox.name
            if seen_candidate.exists():
                return seen_candidate
            # Receiver-side file vanished — fall through and re-deliver.

    ts = _utc_iso_compact()
    short = uuid.uuid4().hex[:8]
    resolved_message_id = str(message_id or f"{ts}__{from_role}__{short}").strip()
    filename = _FILENAME_TEMPLATE.format(
        ts=ts, from_role=from_role, to_role=to_role, uid=short
    )
    fm: dict[str, Any] = {
        "message_id": resolved_message_id,
        "from": from_role,
        "to": to_role,
        "subject": subject or "(no subject)",
        "sent_at": str(sent_at or _utc_iso_human()).strip(),
        "blocking": bool(blocking),
        "priority": priority or "normal",
    }
    if reply_to:
        fm["reply_to"] = str(reply_to)
    if tags:
        fm["tags"] = list(tags)
    if idempotency_key:
        fm["idempotency_key"] = str(idempotency_key)
    if extra_frontmatter:
        for k, v in extra_frontmatter.items():
            if k not in fm:
                fm[k] = v

    payload = _render_message(fm, body)

    # Write to sender-side .tmp first (atomic on same filesystem)
    tmp_path = layout.role_tmp_dir(from_role) / filename
    final_path = layout.role_new_dir(to_role) / filename
    outbox_path = layout.role_outbox_dir(from_role) / filename

    _atomic_write_text(tmp_path, payload)
    # Mirror into sender outbox first so audit survives even if the
    # rename to receiver fails.
    shutil.copy2(tmp_path, outbox_path)
    os.replace(tmp_path, final_path)
    return final_path


# ──────────────────────────────────────────────────────────────────────
# Reading
# ──────────────────────────────────────────────────────────────────────


def list_unread(
    layout: CommsLayout,
    role_id: str,
    *,
    limit: int | None = None,
) -> list[MessageHeader]:
    """Return frontmatter headers of all messages in `role_id`'s `new/`.

    Sorted oldest-first by filename (which begins with an ISO timestamp).
    Cheap: only reads the frontmatter section of each file.
    """
    role_id = _safe(role_id)
    new_dir = layout.role_new_dir(role_id)
    if not new_dir.is_dir():
        return []
    files = sorted(p for p in new_dir.iterdir() if p.is_file() and p.suffix == ".md")
    if limit is not None:
        files = files[:limit]
    headers: list[MessageHeader] = []
    for path in files:
        try:
            header = read_header(path)
        except Exception:
            continue
        if header is not None:
            headers.append(header)
    return headers


def list_roles(layout: CommsLayout) -> list[str]:
    """Return every role directory currently present under inbox/."""
    if not layout.inbox_root.is_dir():
        return []
    roles: list[str] = []
    for child in sorted(layout.inbox_root.iterdir(), key=lambda item: item.name):
        if child.is_dir():
            roles.append(child.name)
    return roles


def list_role_messages(
    layout: CommsLayout,
    role_id: str,
    *,
    include_new: bool = True,
    include_seen: bool = True,
    include_outbox: bool = False,
    limit: int | None = None,
) -> list[MessageHeader]:
    """List all known message headers for one role across selected buckets."""
    role = _safe(role_id)
    buckets: list[Path] = []
    if include_new:
        buckets.append(layout.role_new_dir(role))
    if include_seen:
        buckets.append(layout.role_seen_dir(role))
    if include_outbox:
        buckets.append(layout.role_outbox_dir(role))
    paths: list[Path] = []
    for bucket in buckets:
        if not bucket.is_dir():
            continue
        try:
            paths.extend(
                path for path in bucket.iterdir()
                if path.is_file() and path.suffix == ".md"
            )
        except OSError:
            continue
    paths = sorted(paths, key=lambda item: item.name)
    if limit is not None:
        paths = paths[:limit]
    headers: list[MessageHeader] = []
    for path in paths:
        header = read_header(path)
        if header is not None:
            headers.append(header)
    return headers


def list_unread_blocking(
    layout: CommsLayout,
    role_id: str,
) -> list[MessageHeader]:
    """Subset of unread that have `blocking: true` set."""
    return [h for h in list_unread(layout, role_id) if h.blocking]


def find_unresolved_blocking_outbox(
    layout: CommsLayout,
    sender_role: str,
) -> list[MessageHeader]:
    """List blocking messages `sender_role` has sent which still
    lack a reply. We consider a blocking message resolved when the
    sender's own inbox (new/ or seen/) contains some message whose
    `reply_to` field matches the blocking message id.

    Returned headers always come from the SENDER's outbox copy.
    """
    sender_role = _safe(sender_role)
    outbox = layout.role_outbox_dir(sender_role)
    if not outbox.is_dir():
        return []
    blocking: list[MessageHeader] = []
    for path in sorted(outbox.iterdir()):
        if not path.is_file() or path.suffix != ".md":
            continue
        h = read_header(path)
        if h is None or not h.blocking:
            continue
        if find_reply_to(layout, sender_role, h.message_id) is not None:
            continue
        blocking.append(h)
    return blocking


def find_reply_to(
    layout: CommsLayout,
    sender_role: str,
    message_id: str,
) -> MessageHeader | None:
    """Search the sender's inbox (new + seen) for any message whose
    `reply_to` matches `message_id`. Returns the first match or None.
    """
    sender_role = _safe(sender_role)
    for sub in (NEW_DIRNAME, SEEN_DIRNAME):
        d = layout.role_inbox(sender_role) / sub
        if not d.is_dir():
            continue
        for path in sorted(d.iterdir()):
            if not path.is_file() or path.suffix != ".md":
                continue
            h = read_header(path)
            if h is None:
                continue
            if h.reply_to and str(h.reply_to) == str(message_id):
                return h
    return None


def has_unread(layout: CommsLayout, role_id: str) -> bool:
    """Cheap existence check — `os.scandir` only, no parsing."""
    new_dir = layout.role_new_dir(_safe(role_id))
    if not new_dir.is_dir():
        return False
    try:
        with os.scandir(new_dir) as it:
            return any(entry.is_file() and entry.name.endswith(".md") for entry in it)
    except OSError:
        return False


def read_header(path: Path) -> MessageHeader | None:
    """Parse frontmatter only (cheap). Body is not loaded."""
    try:
        with path.open("r", encoding="utf-8", errors="replace") as f:
            chunk = f.read(8192)
    except OSError:
        return None
    fm, _ = _split_frontmatter(chunk)
    if fm is None:
        return None
    return _header_from_frontmatter(path, fm)


def read_message(path: Path) -> tuple[MessageHeader | None, str]:
    """Read full message: (header, body). Returns (None, "") on error."""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None, ""
    fm, body = _split_frontmatter(text)
    if fm is None:
        return None, text
    return _header_from_frontmatter(path, fm), body


def mark_seen(
    layout: CommsLayout,
    role_id: str,
    paths: Iterable[Path],
) -> list[Path]:
    """Atomically move the given files from `new/` to `seen/`.

    Returns the list of resulting paths in `seen/`. Files that no
    longer exist (e.g. raced with another reader) are silently skipped.
    """
    role_id = _safe(role_id)
    seen_dir = layout.role_seen_dir(role_id)
    seen_dir.mkdir(parents=True, exist_ok=True)
    moved: list[Path] = []
    for src in paths:
        try:
            if not src.exists():
                continue
            dst = seen_dir / src.name
            os.replace(src, dst)
            moved.append(dst)
        except OSError:
            continue
    return moved


def mark_all_seen(layout: CommsLayout, role_id: str) -> list[Path]:
    new_dir = layout.role_new_dir(_safe(role_id))
    if not new_dir.is_dir():
        return []
    return mark_seen(layout, role_id, list(new_dir.iterdir()))


def read_team_memory(layout: CommsLayout) -> str:
    path = layout.team_memory_path
    if not path.exists():
        _ensure_team_memory_entrypoint(layout)
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""


def read_team_memory_digest(
    layout: CommsLayout,
    *,
    max_chars: int = 1600,
) -> str:
    payload = read_team_memory_digest_payload(layout, max_chars=max_chars)
    digest = str(payload.get("digest", "") or "").strip()
    if not digest:
        return ""
    meta_lines = [
        f"team_memory_path: {payload.get('team_memory_path', '')}",
        f"team_memory_truncated: {str(bool(payload.get('team_memory_truncated', False))).lower()}",
        f"team_memory_omitted_chars: {int(payload.get('team_memory_omitted_chars', 0) or 0)}",
    ]
    return "\n".join([*meta_lines, "", digest]).rstrip()


def _is_team_memory_scaffold_only(text: str) -> bool:
    return "\n".join(str(text or "").strip().split()) == "\n".join(_TEAM_MEMORY_SCAFFOLD.strip().split())


def read_team_memory_digest_payload(
    layout: CommsLayout,
    *,
    max_chars: int = 1600,
) -> dict[str, Any]:
    text = read_team_memory(layout).strip()
    path = layout.team_memory_path
    if not text or _is_team_memory_scaffold_only(text):
        return {
            "digest": "",
            "team_memory_path": str(path),
            "team_memory_truncated": False,
            "team_memory_omitted_chars": 0,
        }
    clip = clip_text(
        text,
        limit=max(1, int(max_chars or 1600)),
        marker="team memory preview truncated",
    )
    return {
        "digest": clip.text.rstrip(),
        "team_memory_path": str(path),
        "team_memory_truncated": clip.truncated,
        "team_memory_omitted_chars": clip.omitted_chars,
        "team_memory_original_chars": clip.original_chars,
    }


# ──────────────────────────────────────────────────────────────────────
# Internals
# ──────────────────────────────────────────────────────────────────────


def _safe(name: str) -> str:
    """Filesystem-safe role/project/session/projection id."""
    s = (name or "").strip()
    if not s:
        return ""
    s = _SAFE_NAME_RE.sub("-", s)
    return s.strip("-_") or "default"


def _utc_iso_compact() -> str:
    # `2026-04-08T20-55-30Z` — sortable filename component, no `:` (Win-safe).
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")


def _utc_iso_human() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _atomic_write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(
        prefix=path.name + ".",
        suffix=".tmp",
        dir=str(path.parent),
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_name, path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def _dump_yaml(data: dict[str, Any]) -> str:
    if yaml is not None:
        return yaml.safe_dump(
            data, sort_keys=False, allow_unicode=True, default_flow_style=False
        )
    lines: list[str] = []
    for k, v in data.items():
        if isinstance(v, (list, tuple)):
            lines.append(f"{k}:")
            for item in v:
                lines.append(f"  - {item}")
        elif isinstance(v, bool):
            lines.append(f"{k}: {'true' if v else 'false'}")
        elif v is None:
            lines.append(f"{k}: null")
        else:
            lines.append(f"{k}: {v}")
    return "\n".join(lines) + "\n"


def _render_message(frontmatter: dict[str, Any], body: str) -> str:
    if yaml is not None:
        front_text = yaml.safe_dump(
            frontmatter,
            sort_keys=False,
            allow_unicode=True,
            default_flow_style=False,
        ).strip()
    else:
        # Minimal YAML-ish renderer for primitive scalars
        lines = []
        for k, v in frontmatter.items():
            if isinstance(v, (list, tuple)):
                lines.append(f"{k}:")
                for item in v:
                    lines.append(f"  - {item}")
            elif isinstance(v, bool):
                lines.append(f"{k}: {'true' if v else 'false'}")
            else:
                lines.append(f"{k}: {v}")
        front_text = "\n".join(lines)
    body_text = (body or "").rstrip("\n")
    return f"---\n{front_text}\n---\n\n{body_text}\n"


def _split_frontmatter(text: str) -> tuple[dict[str, Any] | None, str]:
    match = _FRONTMATTER_RE.match(text or "")
    if not match:
        return None, text or ""
    front_raw = match.group("front")
    body = match.group("body") or ""
    fm: dict[str, Any] = {}
    if yaml is not None:
        try:
            loaded = yaml.safe_load(front_raw)
            if isinstance(loaded, dict):
                fm = loaded
        except Exception:
            fm = {}
    if not fm:
        for line in front_raw.splitlines():
            if ":" in line and not line.startswith(" "):
                k, _, v = line.partition(":")
                fm[k.strip()] = v.strip()
    return fm, body


def _header_from_frontmatter(path: Path, fm: dict[str, Any]) -> MessageHeader | None:
    try:
        return MessageHeader(
            path=path,
            message_id=str(fm.get("message_id") or path.stem),
            from_role=str(fm.get("from") or ""),
            to_role=str(fm.get("to") or ""),
            subject=str(fm.get("subject") or "(no subject)"),
            sent_at=str(fm.get("sent_at") or ""),
            blocking=bool(fm.get("blocking") or False),
            reply_to=(str(fm.get("reply_to")) if fm.get("reply_to") else None),
            priority=str(fm.get("priority") or "normal"),
            tags=list(fm.get("tags") or []),
            raw_frontmatter=dict(fm),
        )
    except Exception:
        return None


def _find_in_outbox(
    layout: CommsLayout,
    from_role: str,
    idempotency_key: str,
) -> Path | None:
    outbox = layout.role_outbox_dir(from_role)
    if not outbox.is_dir():
        return None
    for path in outbox.iterdir():
        if not path.is_file() or path.suffix != ".md":
            continue
        header = read_header(path)
        if header is None:
            continue
        if str(header.raw_frontmatter.get("idempotency_key") or "") == str(idempotency_key):
            return path
    return None


def _ensure_team_memory_entrypoint(layout: CommsLayout) -> None:
    path = layout.team_memory_path
    if path.exists():
        return
    _atomic_write_text(path, _TEAM_MEMORY_SCAFFOLD)


# ──────────────────────────────────────────────────────────────────────
# Meetings: multi-party append-only transcripts
# ──────────────────────────────────────────────────────────────────────


@dataclass
class MeetingEntry:
    """A single line in a meeting transcript."""

    entry_id: str
    author: str
    posted_at: str
    content: str
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass
class MeetingState:
    """Cached view of `meetings/<id>/manifest.yaml` plus a transcript count.

    `status` ∈ {"open", "closed"}.
    """

    meeting_id: str
    topic: str
    organizer: str
    participants: list[str]
    status: str
    opened_at: str
    closed_at: str | None
    decision: str | None
    entry_count: int
    manifest_path: Path
    transcript_path: Path


def start_meeting(
    layout: CommsLayout,
    *,
    meeting_id: str | None,
    topic: str,
    organizer: str,
    participants: Iterable[str],
    extra: dict[str, Any] | None = None,
) -> MeetingState:
    """Create a new meeting room.

    Returns the freshly-created `MeetingState`. Idempotent only when
    `meeting_id` is supplied AND already exists with matching topic;
    otherwise a new id is generated.
    """
    organizer = _safe(organizer)
    participants_list = sorted({_safe(p) for p in participants if p})
    if organizer and organizer not in participants_list:
        participants_list.insert(0, organizer)

    if meeting_id:
        mid = _safe(meeting_id)
    else:
        mid = f"{_utc_iso_compact()}__{organizer or 'org'}__{uuid.uuid4().hex[:6]}"

    mdir = layout.meeting_dir(mid)
    mdir.mkdir(parents=True, exist_ok=True)
    manifest_path = mdir / "manifest.yaml"
    transcript_path = mdir / "transcript.md"

    if manifest_path.exists():
        # Re-load existing one rather than overwriting.
        existing = read_meeting_state(layout, mid)
        if existing is not None:
            return existing

    manifest: dict[str, Any] = {
        "meeting_id": mid,
        "topic": topic or "(no topic)",
        "organizer": organizer,
        "participants": participants_list,
        "status": "open",
        "opened_at": _utc_iso_human(),
        "closed_at": None,
        "decision": None,
    }
    if extra:
        manifest.update({k: v for k, v in extra.items() if k not in manifest})
    _atomic_write_text(manifest_path, _dump_yaml(manifest))

    # Initialize transcript with a header.
    header = (
        f"# Meeting `{mid}` — {topic or '(no topic)'}\n"
        f"\n"
        f"Organizer: `{organizer}`. Participants: "
        f"{', '.join(f'`{p}`' for p in participants_list)}.\n"
        f"Opened at {manifest['opened_at']}.\n"
        f"\n"
        f"---\n"
    )
    if not transcript_path.exists():
        _atomic_write_text(transcript_path, header)

    state = read_meeting_state(layout, mid)
    assert state is not None
    return state


def append_to_transcript(
    layout: CommsLayout,
    *,
    meeting_id: str,
    author: str,
    content: str,
) -> MeetingEntry:
    """Atomically append one line to a meeting transcript.

    Uses `fcntl.flock(LOCK_EX)` on POSIX so concurrent participants
    can append safely without losing entries. On Windows the call
    falls back to a best-effort write — `os.O_APPEND` itself is
    atomic for short writes on most filesystems anyway, but the
    explicit lock makes the guarantee unconditional on POSIX.

    Each entry is rendered as a markdown block with a frontmatter-ish
    metadata header so the transcript is both human-readable and
    machine-parseable by `read_transcript`.
    """
    mid = _safe(meeting_id)
    transcript_path = layout.meeting_dir(mid) / "transcript.md"
    if not transcript_path.exists():
        # Soft-bootstrap: if the meeting room exists but transcript was
        # deleted, recreate. If neither exists, raise.
        if not layout.meeting_dir(mid).is_dir():
            raise FileNotFoundError(f"meeting {mid!r} not found at {transcript_path}")
        _atomic_write_text(transcript_path, f"# Meeting `{mid}`\n\n---\n")

    author = _safe(author) or "anonymous"
    posted_at = _utc_iso_human()
    entry_id = f"{_utc_iso_compact()}__{author}__{uuid.uuid4().hex[:6]}"
    body = (content or "").rstrip()

    # Render as one self-contained block. The leading machine-readable
    # marker `<!-- entry: ... -->` makes parsing trivial.
    block = (
        f"\n<!-- entry: {entry_id} author: {author} posted_at: {posted_at} -->\n"
        f"### `{author}` · {posted_at}\n"
        f"\n"
        f"{body}\n"
    )

    # Append with exclusive lock when fcntl is available.
    fd = os.open(str(transcript_path), os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o644)
    try:
        if fcntl is not None:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX)
            except OSError:
                pass
        try:
            os.write(fd, block.encode("utf-8"))
            os.fsync(fd)
        finally:
            if fcntl is not None:
                with contextlib.suppress(OSError):
                    fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)

    return MeetingEntry(
        entry_id=entry_id,
        author=author,
        posted_at=posted_at,
        content=body,
    )


def read_transcript(
    layout: CommsLayout,
    meeting_id: str,
) -> list[MeetingEntry]:
    """Parse a transcript back into a list of MeetingEntry objects.

    Tolerant of hand edits — entries without the machine marker are
    skipped (the file header itself is one such block).
    """
    mid = _safe(meeting_id)
    path = layout.meeting_dir(mid) / "transcript.md"
    if not path.is_file():
        return []
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    entries: list[MeetingEntry] = []
    marker_re = re.compile(
        r"<!--\s*entry:\s*(?P<eid>\S+)\s+author:\s*(?P<author>\S+)\s+posted_at:\s*(?P<posted>[^\s>]+)\s*-->"
    )
    matches = list(marker_re.finditer(text))
    for i, m in enumerate(matches):
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        chunk = text[start:end]
        # Strip the leading "### `author` · ts" line
        lines = chunk.lstrip("\n").splitlines()
        if lines and lines[0].startswith("### "):
            lines = lines[1:]
        body = "\n".join(lines).strip()
        entries.append(
            MeetingEntry(
                entry_id=m.group("eid"),
                author=m.group("author"),
                posted_at=m.group("posted"),
                content=body,
            )
        )
    return entries


def read_meeting_state(
    layout: CommsLayout,
    meeting_id: str,
) -> MeetingState | None:
    mid = _safe(meeting_id)
    mdir = layout.meeting_dir(mid)
    manifest_path = mdir / "manifest.yaml"
    transcript_path = mdir / "transcript.md"
    if not manifest_path.is_file():
        return None
    try:
        text = manifest_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    if yaml is not None:
        try:
            data = yaml.safe_load(text) or {}
        except Exception:
            data = {}
    else:
        data = {}
    if not isinstance(data, dict):
        return None
    return MeetingState(
        meeting_id=str(data.get("meeting_id") or mid),
        topic=str(data.get("topic") or ""),
        organizer=str(data.get("organizer") or ""),
        participants=list(data.get("participants") or []),
        status=str(data.get("status") or "open"),
        opened_at=str(data.get("opened_at") or ""),
        closed_at=(str(data.get("closed_at")) if data.get("closed_at") else None),
        decision=(str(data.get("decision")) if data.get("decision") else None),
        entry_count=len(read_transcript(layout, mid)),
        manifest_path=manifest_path,
        transcript_path=transcript_path,
    )


def list_active_meetings(
    layout: CommsLayout,
    *,
    role_id: str | None = None,
) -> list[MeetingState]:
    """List meetings that are still `open`. If `role_id` is given,
    only return meetings where that role is in `participants`.
    """
    if not layout.meetings_root.is_dir():
        return []
    out: list[MeetingState] = []
    role = _safe(role_id) if role_id else None
    for child in sorted(layout.meetings_root.iterdir()):
        if not child.is_dir():
            continue
        state = read_meeting_state(layout, child.name)
        if state is None or state.status != "open":
            continue
        if role and role not in state.participants:
            continue
        out.append(state)
    return out


def close_meeting(
    layout: CommsLayout,
    *,
    meeting_id: str,
    decision: str,
    closed_by: str = "",
) -> MeetingState | None:
    """Mark a meeting as closed and write the final decision into the manifest.

    Also appends a final transcript entry summarizing the decision so
    the transcript is self-contained for downstream readers.
    """
    mid = _safe(meeting_id)
    state = read_meeting_state(layout, mid)
    if state is None:
        return None
    if state.status == "closed":
        return state
    manifest = {
        "meeting_id": state.meeting_id,
        "topic": state.topic,
        "organizer": state.organizer,
        "participants": state.participants,
        "status": "closed",
        "opened_at": state.opened_at,
        "closed_at": _utc_iso_human(),
        "decision": decision or "",
    }
    _atomic_write_text(state.manifest_path, _dump_yaml(manifest))
    append_to_transcript(
        layout,
        meeting_id=mid,
        author=closed_by or state.organizer or "system",
        content=f"**Meeting closed.** Decision: {decision or '(none recorded)'}",
    )
    return read_meeting_state(layout, mid)


def render_meetings_section(
    layout: CommsLayout,
    role_id: str,
    *,
    max_meetings: int = 6,
    max_recent_entries: int = 3,
) -> str:
    """Markdown section for the prompt: meetings this role is currently in.

    Renders one subsection per open meeting, with participants,
    transcript path, and recent transcript tail. Returns "" when
    there are no open meetings for this role — the "how to start a
    meeting" command template lives in the ``collaboration-playbook``
    skill, not in this per-turn prompt section.
    """
    if not role_id:
        return ""
    role = _safe(role_id)
    meetings = list_active_meetings(layout, role_id=role)[:max_meetings]
    if not meetings:
        return ""
    lines: list[str] = ["### Meetings"]
    for state in meetings:
        lines.append("")
        lines.append(
            f"#### `{state.meeting_id}` — {state.topic} (status: {state.status})"
        )
        lines.append(
            f"Participants: {', '.join('`'+p+'`' for p in state.participants)} "
            f"· opened {state.opened_at} · {state.entry_count} entries"
        )
        lines.append(f"Transcript: `cat {state.transcript_path}`")
        recent = read_transcript(layout, state.meeting_id)[-max_recent_entries:]
        if recent:
            lines.append("Recent:")
            for e in recent:
                lines.append(f"- `{e.author}` · {e.posted_at}: {e.content}")
    return "\n".join(lines)


# ──────────────────────────────────────────────────────────────────────
# README (descriptive only - collaboration tools live behind opc-collab CLI)
# ──────────────────────────────────────────────────────────────────────


_README_TEMPLATE = """\
# SafeOPC Comms Layout

This directory is the file-based collaboration substrate for project
`{project_id}`, session `{session_id}`. It is OPC-managed runtime
state — you do not need to write into it directly.

## Layout

- `inbox/<role>/new/`     — unread messages addressed to <role>
- `inbox/<role>/seen/`    — messages SafeOPC has archived for <role>
- `inbox/<role>/outbox/`  — copies of messages <role> has sent (audit)
- `handoffs/<projection_id>/`  — formal work-item-level handoffs
- `meetings/<meeting_id>/` — multi-party meeting transcripts

## How agents collaborate

Mailbox delivery is **runtime-owned**. SafeOPC reads `.opc-comms`,
classifies inbox state, archives `new/ -> seen/`, and injects the
actionable mailbox snapshot into each agent's turn context.

Agents use collaboration tools only to express intent, such as:

- `send_dm(...)` / `reply_message(...)`
- `delegate_work(...)`
- `manager_board_read`
- `start_meeting(...)` / `respond_meeting(...)`

External agents receive a capability-sliced subset of these tools via
the `opc-collab` CLI. Debug/admin-only tools such as `read_inbox`,
`read_meeting`, and `list_colleagues` are not part of the normal
production surface.

## Reading the files manually (debug only)

    ls inbox/<your_role>/new/
    cat inbox/<your_role>/new/<message-file>.md

Direct file access is for debugging. Production runs do not require the
agent to call a mailbox-read tool; the runtime has already prepared the
actionable mailbox state for the turn.
"""


def _write_readme(layout: CommsLayout) -> None:
    text = _README_TEMPLATE.format(
        project_id=layout.project_id,
        session_id=layout.session_id,
        root=str(layout.root),
    )
    _atomic_write_text(layout.root / "README.md", text)

# ──────────────────────────────────────────────────────────────────────
# Prompt rendering helpers (used by context_assembler)
# ──────────────────────────────────────────────────────────────────────


def render_inbox_section(
    layout: CommsLayout,
    role_id: str,
    *,
    max_unread_listed: int = 12,
) -> str:
    """Build the per-turn comms section for the role's inbox.

    This renders ONLY:
      1. The role's mailbox scope.
      2. Runtime events for this turn (currently-unread messages
         addressed to this role) — surfaced as a runtime-owned
         mailbox snapshot, not as an instruction to poll the inbox.

    Collaboration actions are exposed through the runtime tool surface
    or the opc-collab CLI as needed, but mailbox delivery itself remains
    runtime-owned. The standing rules for when to send / when to reply /
    how to use blocking semantics live in the collaboration skill.

    Returns "" if ``role_id`` is empty or the role has neither
    addresses worth surfacing nor unread messages.
    """
    if not role_id:
        return ""
    role = _safe(role_id)
    new_dir = layout.role_new_dir(role)
    unread = list_unread(layout, role, limit=max_unread_listed) if new_dir.is_dir() else []

    lines: list[str] = []
    lines.append("### Mailbox")
    lines.append(
        f"Mailbox delivery for role `{role}` is runtime-owned; SafeOPC has already "
        f"prepared the actionable snapshot for this turn. Use `reply_message` to "
        f"answer, `inbox(action=\"ack\")` to acknowledge handled messages that need "
        f"no reply, and other collaboration tools only when you need to delegate, "
        f"release, roll up, or coordinate."
    )
    if unread:
        lines.append("")
        lines.append(
            f"### Runtime-owned unread mailbox snapshot ({len(unread)})"
        )
        for h in unread:
            tag = " [BLOCKING]" if h.blocking else ""
            lines.append(
                f"- {h.sent_at} — from `{h.from_role}` — {h.subject}{tag}"
            )
    return "\n".join(lines)

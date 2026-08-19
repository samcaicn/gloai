/**
 * Dashboard launcher auth API.
 * Uses plain fetch (not launcherFetch) to avoid redirect loops on auth pages.
 */
export type LauncherAuthStatus = {
  authenticated: boolean
}

export async function getLauncherAuthStatus(): Promise<LauncherAuthStatus> {
  const res = await fetch("/api/auth/status", {
    method: "GET",
    credentials: "same-origin",
  })
  if (!res.ok) {
    throw new Error(`status ${res.status}`)
  }
  return (await res.json()) as LauncherAuthStatus
}

export async function postLauncherDashboardLogout(): Promise<boolean> {
  const res = await fetch("/api/auth/logout", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: "{}",
  })
  return res.ok
}

export type BindResult = { ok: true } | { ok: false; error: string }

export async function postLauncherDashboardBind(
  joinCode: string,
): Promise<BindResult> {
  const res = await fetch("/api/auth/bind", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ join_code: joinCode.trim() }),
  })
  if (res.ok) {
    const data = (await res.json()) as { status?: string; message?: string }
    if (data && data.status === "pending") {
      return {
        ok: false,
        error:
          data.message ||
          "绑定申请已提交，请等待租户管理员确认后刷新页面重试。",
      }
    }
    return { ok: true }
  }
  return { ok: false, error: await readLauncherAuthError(res) }
}

async function readLauncherAuthError(res: Response): Promise<string> {
  let msg = `Request failed with status ${res.status}`
  try {
    const j = (await res.json()) as { error?: string }
    if (j.error) msg = j.error
  } catch {
    /* ignore */
  }
  return msg
}

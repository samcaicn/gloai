// 通用浮层（modal overlay）：各页面的次要/长表单配置块统一收进浮层，
// 页面本体只留启动按钮，避免单页过长。
// 用法：模板里嵌入 overlayHtml(...)，渲染后调用 wireOverlayClose(container)，
// 打开用 openOverlay(id)。
//
// 2026-11-05 桌面交互加固：打开时把焦点送进浮层、关闭后归还给触发元素，
// Tab 焦点锁在浮层内（不跑到背景页面），并锁住背景滚动。桌面客户端键盘
// 用户高频用 Esc / Tab 操作，原实现只有「加/减 hidden 类」，焦点会丢到 body。

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** 最近一次打开浮层前的焦点元素（关闭时归还）。 */
let lastFocused: HTMLElement | null = null;

function visibleOverlays(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>(".overlay:not(.hidden)"));
}

function restoreFocus() {
  if (visibleOverlays().length) return;            // 还有别的浮层开着，先不归还
  document.body.classList.remove("ov-open");
  const el = lastFocused;
  lastFocused = null;
  if (el && el.isConnected && el !== document.body) {
    el.focus({ preventScroll: true });
  } else {
    // 鼠标点击按钮在部分 WebView 里不会给按钮焦点（activeElement 仍是 body）：
    // 此时把焦点交回当前选中的页签，键盘上下文不至于丢到 body 从头 Tab。
    document.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')?.focus({ preventScroll: true });
  }
}

export function openOverlay(id: string) {
  const ov = document.getElementById(id);
  if (!ov) return;
  if (ov.classList.contains("hidden")) {
    lastFocused = document.activeElement as HTMLElement | null;
  }
  ov.classList.remove("hidden");
  document.body.classList.add("ov-open");
  // 优先聚焦第一个输入控件（登录浮层 = 接口地址），没有则退到关闭按钮
  const panel = ov.querySelector<HTMLElement>(".overlay-panel");
  const first =
    panel?.querySelector<HTMLElement>("input:not([type=hidden]):not([disabled]), select:not([disabled]), textarea:not([disabled])") ||
    panel?.querySelector<HTMLElement>(FOCUSABLE);
  first?.focus({ preventScroll: true });
}

export function closeOverlay(id: string) {
  const ov = document.getElementById(id);
  if (!ov) return;
  ov.classList.add("hidden");
  restoreFocus();
}

export function closeAllOverlays() {
  visibleOverlays().forEach((el) => el.classList.add("hidden"));
  restoreFocus();
}

// 绑定浮层关闭交互：遮罩 / ✕ 按钮 / ESC。容器整体重渲染后需重新调用。
export function wireOverlayClose(root: ParentNode) {
  root.querySelectorAll<HTMLElement>(".overlay").forEach((ov) => {
    ov.querySelectorAll("[data-close-ov]").forEach((el) =>
      el.addEventListener("click", () => closeOverlay(ov.id))
    );
  });
}

// 全局 ESC 关闭 + Tab 焦点陷阱（幂等，只会注册一次）
let escBound = false;
export function bindOverlayEsc() {
  if (escBound) return;
  escBound = true;
  document.addEventListener("keydown", (e) => {
    const open = visibleOverlays();
    if (e.key === "Escape") {
      if (open.length) closeAllOverlays();
      return;
    }
    if (e.key !== "Tab" || !open.length) return;
    // 只处理最上层浮层；用最后一个可见浮层（后开的后插入 DOM）
    const panel = open[open.length - 1].querySelector<HTMLElement>(".overlay-panel");
    if (!panel) return;
    const items = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
      (el) => el.getClientRects().length > 0
    );
    if (!items.length) return;
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement as HTMLElement | null;
    if (!panel.contains(active)) { e.preventDefault(); first.focus(); return; }
    if (e.shiftKey && active === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && active === last) { e.preventDefault(); first.focus(); }
  });
}

// 生成一个浮层容器 HTML（默认隐藏）
export function overlayHtml(id: string, title: string, bodyHtml: string, desc?: string): string {
  return `
  <div class="overlay hidden" id="${id}" role="dialog" aria-modal="true" aria-label="${title}">
    <div class="overlay-mask" data-close-ov></div>
    <div class="overlay-panel">
      <div class="row-between ov-head">
        <h2>${title}</h2>
        <button class="btn small" data-close-ov aria-label="关闭">✕ 关闭</button>
      </div>
      ${desc ? `<p class="card-desc">${desc}</p>` : ""}
      ${bodyHtml}
    </div>
  </div>`;
}

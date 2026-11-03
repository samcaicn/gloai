// 通用浮层（modal overlay）：各页面的次要/长表单配置块统一收进浮层，
// 页面本体只留启动按钮，避免单页过长。
// 用法：模板里嵌入 overlayHtml(...)，渲染后调用 wireOverlayClose(container)，
// 打开用 openOverlay(id)。

export function openOverlay(id: string) {
  document.getElementById(id)?.classList.remove("hidden");
}

export function closeOverlay(id: string) {
  document.getElementById(id)?.classList.add("hidden");
}

export function closeAllOverlays() {
  document.querySelectorAll(".overlay:not(.hidden)").forEach((el) => el.classList.add("hidden"));
}

// 绑定浮层关闭交互：遮罩 / ✕ 按钮 / ESC。容器整体重渲染后需重新调用。
export function wireOverlayClose(root: ParentNode) {
  root.querySelectorAll(".overlay").forEach((ov) => {
    ov.querySelectorAll("[data-close-ov]").forEach((el) =>
      el.addEventListener("click", () => ov.classList.add("hidden"))
    );
  });
}

// 全局 ESC 关闭（幂等，只会注册一次）
let escBound = false;
export function bindOverlayEsc() {
  if (escBound) return;
  escBound = true;
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") closeAllOverlays();
  });
}

// 生成一个浮层容器 HTML（默认隐藏）
export function overlayHtml(id: string, title: string, bodyHtml: string, desc?: string): string {
  return `
  <div class="overlay hidden" id="${id}">
    <div class="overlay-mask" data-close-ov></div>
    <div class="overlay-panel">
      <div class="row-between ov-head">
        <h2>${title}</h2>
        <button class="btn small" data-close-ov>✕ 关闭</button>
      </div>
      ${desc ? `<p class="card-desc">${desc}</p>` : ""}
      ${bodyHtml}
    </div>
  </div>`;
}

// PC 工作台 UI 外壳回归测试（2026-11-05）
// 纯静态断言：不启浏览器、不依赖 Tauri，只锁住这次界面交互优化中「一改就回归」的契约。
// 运行：npm test / pnpm test
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const read = (p) => readFileSync(resolve(ROOT, p), "utf8");
const css = read("src/style.css");
const ui = read("src/ui.ts");
const overlay = read("src/overlay.ts");
const cargo = read("src-tauri/Cargo.toml");
const tauriConf = JSON.parse(read("src-tauri/tauri.conf.json"));
const versionJson = JSON.parse(read("updates/version.json"));

/** 取某个 CSS 选择器的规则体（只取第一个匹配块，够本测试用） */
function ruleBody(selector) {
  const i = css.indexOf(selector + " {");
  assert.notEqual(i, -1, `缺少 CSS 规则 ${selector}`);
  return css.slice(i + selector.length + 2, css.indexOf("}", i));
}

test("内容区是单列块流，非首页面板不再被塞进 2 列 grid", () => {
  const body = ruleBody(".content");
  assert.match(body, /display:\s*block/, ".content 必须是 block");
  assert.doesNotMatch(body, /grid-template-columns:\s*repeat\(2/, "禁止恢复 2 列 grid");
  assert.match(body, /max-width:\s*1280px/, "超宽屏需限宽");
  assert.match(css, /\.members-page,\s*\.orders-page,\s*\.interact-page\s*\{\s*display:\s*block/);
  assert.match(ruleBody(".ord-cols"), /minmax\(260px,\s*340px\)/, "订单左列要可收缩，避免预览区竖排断字");
});

test("浮层挂在 #app 根层（不能是 #page-params 子节点，否则切页后弹了看不见）", () => {
  const mainStart = ui.indexOf('<main class="content" id="page-params"');
  const mainEnd = ui.indexOf("</main>", mainStart);
  const pageParams = ui.slice(mainStart, mainEnd);
  assert.doesNotMatch(pageParams, /\$\{moreOverlays\}|\$\{loginOverlay\}/, "浮层不得放在首页 main 内");
  const toastIdx = ui.indexOf('<div id="toast-box"');
  assert.ok(toastIdx > 0, "toast 容器存在");
  assert.ok(ui.indexOf("${moreOverlays}") > toastIdx, "浮层应在 toast 之后、#app 根层");
  assert.match(overlay, /role="dialog"\s+aria-modal="true"/);
});

test("页签是 ARIA tablist，Alt+1~5 / 方向键 / 页签记忆 / 标题同步齐全", () => {
  assert.match(ui, /role="tablist"/);
  assert.match(ui, /role="tab" aria-selected=/);
  assert.match(ui, /aria-controls="page-/);
  assert.match(ui, /tabindex="\$\{i === 0 \? "0" : "-1"\}"/);
  assert.match(ui, /e\.altKey && \/\^\[1-5\]\$\/\.test\(e\.key\)/, "Alt+1~5 全局直达");
  assert.match(ui, /"ArrowRight"|e\.key === "ArrowRight"/, "tablist 方向键导航");
  assert.match(ui, /e\.key === "Home"|"Home"/);
  assert.match(ui, /localStorage\.setItem\(LAST_TAB_KEY/, "切页记忆");
  assert.match(ui, /localStorage\.getItem\(LAST_TAB_KEY/, "启动恢复");
  assert.match(ui, /document\.title = /, "标题跟随页签");
  assert.match(ui, /\.overlay:not\(\.hidden\)/, "浮层打开时不抢快捷键");
  assert.match(ui, /const TAB_ORDER: TabKey\[\] = \["params", "chat", "members", "orders", "interact"\]/);
});

test("顶栏连接徽标与首页状态条同源同步", () => {
  assert.match(ui, /id="conn-badge"/);
  assert.match(ui, /function updateConnBadge\(\)/);
  assert.match(ui, /function refreshStatusBar\(\)[\s\S]{0,200}updateConnBadge\(\)/);
  assert.match(ui, /"conn-badge"\)\?\.addEventListener[\s\S]{0,200}openOverlay\("ov-login"\)/);
  assert.match(css, /\.conn-badge\s*\{[\s\S]*?flex:\s*none/, "徽标不可被压叠");
  assert.match(css, /@media \(max-width: 1100px\)[\s\S]*?\.kbd-hint \{ display: none; \}/, "窄窗口收掉键盘提示");
});

test("浮层焦点闭环：打开聚焦输入框、关闭归还焦点、Tab 锁在浮层内、背景滚动锁", () => {
  assert.match(overlay, /lastFocused = document\.activeElement/);
  assert.match(overlay, /first\?\.focus\(\{ preventScroll: true \}\)/);
  assert.match(overlay, /document\.body\.classList\.add\("ov-open"\)/);
  assert.match(overlay, /function restoreFocus\(\)/);
  assert.match(overlay, /document\.body\.classList\.remove\("ov-open"\)/);
  assert.match(overlay, /e\.key !== "Tab"/, "Tab 焦点陷阱");
  assert.match(overlay, /e\.shiftKey && active === first/);
  assert.match(css, /body\.ov-open \{ overflow: hidden; \}/);
});

test("登录表单：Enter 提交、autocomplete、校验聚焦、状态文字与失败回滚", () => {
  assert.match(ui, /login-fields"\)[\s\S]{0,300}keydown/);
  assert.match(ui, /"Enter"/);
  assert.match(ui, /autocomplete="\$\{AUTOCOMPLETE\[f\.key\]\}"/);
  assert.match(ui, /\$\(`conn-\$\{missing\}`\)\?\.focus\(\)/, "校验失败聚焦第一个缺失字段");
  assert.match(ui, /function showSwitchState\(/);
  assert.match(ui, /已开启|已关闭/);
  assert.match(ui, /切换中…/);
  assert.match(ui, /async function toggleSwitch\(/);
  assert.match(ui, /input\.checked = !on;/, "切换失败必须回滚");
  assert.match(ui, /input\.disabled = true/, "异步期间禁用重复点击");
});

test("Toast 移出顶栏、限制堆叠并带读屏语义", () => {
  const body = ruleBody(".toast-box");
  assert.match(body, /bottom:\s*20px/);
  assert.match(body, /right:\s*20px/);
  assert.doesNotMatch(body, /top:\s*18px|left:\s*50%/);
  assert.match(ui, /setAttribute\("role", "status"\)/);
  assert.match(ui, /box\.children\.length > 3/);
});

test("首页 IA：运行状态三列带状态字、设置入口描述卡、快捷入口四宫格", () => {
  // 旧 .sw-run 是 flex 竖排（文件前半段），三列覆盖写在「PC 工作台交互优化」段里
  const pcSection = css.slice(css.indexOf("PC 工作台交互优化"));
  assert.match(pcSection, /\.sw-run \{[^}]*repeat\(auto-fit, minmax\(230px, 1fr\)\)/);
  assert.match(ui, /sw-state-autoreply|sw-state-\$\{key\}/);
  assert.match(ui, /class="set-item"/);
  assert.match(ui, /class="set-grid"/);
  assert.match(ui, /data-goto="\$\{q\.tab\}"/);
  assert.match(css, /\.quick-grid \{ grid-template-columns: repeat\(4, minmax\(0, 1fr\)\); \}/);
});

test("版本三处一致：Cargo.toml / tauri.conf.json / updates/version.json", () => {
  const v = (cargo.match(/^version = "([^"]+)"/m) || [])[1];
  assert.ok(v, "Cargo.toml 缺 version");
  assert.equal(tauriConf.version, v);
  assert.equal(versionJson.version, v);
  assert.ok(versionJson.exe_url.includes(`/v${v}/`), "升级包 URL 必须指向同版本 tag");
  assert.ok(versionJson.sig_url.includes(`/v${v}/`));
  assert.match(
    read("src-tauri/Cargo.lock"),
    new RegExp(`name = "bbt-zb-tauri"\\r?\\nversion = "${v.replace(/\./g, "\\.")}"`)
  );
});

test("聊天页「标题行 + 右侧说明 + 正文」留出呼吸，不再视觉叠字", () => {
  assert.match(css, /\.card \.row-between \+ \.card-desc \{ margin-top: 8px; \}/);
  assert.match(css, /\.row-between \.hint \{ flex: none; text-align: right; \}/);
});

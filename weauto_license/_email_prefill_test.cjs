// 离线验证 Worker 侧「付款页邮箱预填」链路。
// 直接从 cf_worker.js 里抽出被测函数（正则截取），避免整份 Worker 依赖 CF 运行时。
const fs = require('fs');
const src = fs.readFileSync(__dirname + '/../weauto_license/cf_worker.js', 'utf8');

function grab(name) {
  // 命中的位置是 "function <name>"，前面可能还有 async 修饰符 —— 必须一起切出来，
  // 否则 async 丢失，函数体里的 await 直接语法错。
  const at = src.indexOf('function ' + name);
  if (at < 0) throw new Error('not found: ' + name);
  const isAsync = src.slice(Math.max(0, at - 6), at) === 'async ';
  const i = isAsync ? at - 6 : at;
  let depth = 0, started = false, j = i;
  for (; j < src.length; j++) {
    if (src[j] === '{') { depth++; started = true; }
    else if (src[j] === '}') { depth--; if (started && depth === 0) { j++; break; } }
  }
  return src.slice(i, j);
}

// 被测函数源码里带 async 修饰符，由 grab() 一并切出；外层用普通 Function 装载，
// 返回的就是 async 函数对象本身（外面再包一层 AsyncFunction 反而会把返回值塞进 Promise）。
function loadSync(name, scope) {
  const keys = Object.keys(scope);
  const vals = keys.map((k) => scope[k]);
  return new Function(...keys, grab(name) + '\nreturn ' + name + ';')(...vals);
}

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  OK  ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra !== undefined ? '  -> ' + JSON.stringify(extra) : '')); }
}

// ---- 1. looksLikeEmail ----
const looksLikeEmail = loadSync('looksLikeEmail', {});
console.log('[1] looksLikeEmail');
ok('正常邮箱', looksLikeEmail('a.b+tag@x.co.uk') === true);
ok('普通邮箱', looksLikeEmail('user@example.com') === true);
ok('空串拒绝', looksLikeEmail('') === false);
ok('undefined 拒绝', looksLikeEmail(undefined) === false);
ok('无 @ 拒绝', looksLikeEmail('nope') === false);
ok('带空格拒绝', looksLikeEmail('a b@x.com') === false);
ok('无 TLD 拒绝', looksLikeEmail('a@x') === false);
ok('超长拒绝', looksLikeEmail('a'.repeat(250) + '@x.com') === false);
ok('非字符串拒绝', looksLikeEmail(12345) === false);

// ---- 2. resolveCheckout：customer.email 只在合法时出现 ----
// 造最小 env + 拦截 creemPost / parseProducts / upstreamError
let captured = null;
function creemPost(env, path, body) { captured = { path, body }; return { status: 200, data: { checkout_url: 'https://checkout.creem.io/ch_x' } }; }
function upstreamError() { return false; }
const PRODUCTS = [
  { tier: 'normal', product_id: 'prod_1', label: '初级 套餐费', price_text: '¥19.9', billing: 'once', features: '基础自动回复' },
  { tier: 'premium', product_id: 'prod_2', label: '中级 套餐费', price_text: '¥59.9', billing: 'once', features: '全部功能' },
  { tier: 'lifetime', product_id: 'prod_3', label: '高级 套餐费', price_text: '¥199', billing: 'once', features: '365天全功能' },
];
const resolveCheckout = loadSync('resolveCheckout', {
  creemPost, upstreamError, parseProducts: () => PRODUCTS, looksLikeEmail,
});

const env = { CREEM_MODE: 'prod', CREEM_API_KEY: 'k' };
(async () => {
  console.log('[2] resolveCheckout payload');
  await resolveCheckout(env, 'normal', 'MID123', 'buyer@qq.com');
  ok('合法邮箱写入 customer.email', captured.body.customer && captured.body.customer.email === 'buyer@qq.com', captured.body);
  ok('product_id 正确', captured.body.product_id === 'prod_1');
  ok('metadata.mid 保留', captured.body.metadata && captured.body.metadata.mid === 'MID123');
  ok('metadata.tier 保留', captured.body.metadata.tier === 'normal');
  ok('不传 success_url（用 Creem 原生页）', !('success_url' in captured.body));

  await resolveCheckout(env, 'normal', 'MID123', '');
  ok('空邮箱 -> 不带 customer 字段', !('customer' in captured.body), captured.body);

  await resolveCheckout(env, 'normal', 'MID123', 'garbage');
  ok('非法邮箱 -> 不带 customer 字段', !('customer' in captured.body), captured.body);

  await resolveCheckout(env, 'premium', '', 'buyer@qq.com');
  ok('已知 tier 选中对应产品', captured.body.product_id === 'prod_2', captured.body.product_id);
  ok('无 mid 时 metadata 不含 mid', !('mid' in captured.body.metadata));

  await resolveCheckout(env, 'nope-not-exist', 'M', 'buyer@qq.com');
  ok('未知 tier 回落 products[0]', captured.body.product_id === 'prod_1', captured.body.product_id);

  // 静态收银台直返，不建会话
  const r = await resolveCheckout({ CREEM_CHECKOUT_URL: 'https://static/x' }, 'normal', 'm', 'a@b.com');
  ok('静态 CREEM_CHECKOUT_URL 优先', r === 'https://static/x');

  // 无 API key -> null（路由层兜底 503）
  const r2 = await resolveCheckout({ CREEM_MODE: 'prod' }, 'normal', 'm', 'a@b.com');
  ok('无 API key 返回 null', r2 === null);

  // ---- 3. buyPage：email/mid 透传到每张卡的按钮 ----
  console.log('[3] buyPage carry');
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  // buyPage 依赖 layout()（Worker 自己的 HTML 骨架），一并装载
  const layout = loadSync('layout', { esc });
  const buyPage = loadSync('buyPage', {
    parseProducts: () => PRODUCTS, looksLikeEmail, esc, layout,
  });
  const E = { SITE_TITLE: 'T', SUPPORT_EMAIL: 's@x.com', CREEM_API_KEY: 'k' };
  const page = buyPage(E, 'https://wetech.jukuai.net', 'MID9', 'buyer@qq.com');
  ok('按钮带 mid', page.includes('mid=MID9'));
  ok('按钮带 email', page.includes('email=buyer%40qq.com'));
  ok('不再出现「复制粘贴卡密」步骤', !page.includes('复制粘贴卡密') && !page.includes('3 步'));
  ok('仍有支付宝付款入口', page.includes('支付宝付款'));
  // 每档一个 <a class="btn">…立即购买</a>；页面里另有 1 处正文提到「立即购买」，故按 class 计数
  ok('每档一个购买按钮', (page.match(/class="btn" href="[^"]*tier=/g) || []).length === PRODUCTS.length,
     (page.match(/class="btn" href="[^"]*tier=/g) || []).length);
  ok('按钮文案含支付宝', (page.match(/支付宝付款 · 立即购买/g) || []).length === PRODUCTS.length);

  const page2 = buyPage(E, 'https://wetech.jukuai.net', '', 'bad');
  ok('非法 email 不进 URL', !page2.includes('email='));

  console.log('\n== ' + pass + ' passed, ' + fail + ' failed ==');
  process.exit(fail ? 1 : 0);
})();

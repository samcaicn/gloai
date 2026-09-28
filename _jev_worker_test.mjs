/**
 * 离线验证 weauto_license/cf_worker.js 的 Jev 端点（/ai/jev/decisions）。
 * 做法：把源码复制成 .mjs，预填 licenseCache 命中（免网络），mock env.AI，
 *       然后直接 import 内部函数断言。Node 22 自带 Request/Response。
 */
import fs from "node:fs";
import path from "node:path";

const SRC = "weauto_license/cf_worker.js";
// 临时模块与测试脚本同目录（相对 import 以脚本所在目录为基准）
const TMP = "_jev_worker_test_mod.mjs";

let src = fs.readFileSync(SRC, "utf8");
// 预填卡密缓存，绕开 Creem 网络校验
src = src.replace(
  "const licenseCache = new Map();",
  'const licenseCache = new Map([["TESTKEY|INST01", Date.now()]]);'
);
src +=
  "\nexport { jevJsonSchema, jevSystemPrompt, jevUserPrompt, jevDecisions, JEV_MODEL_DEFAULT };\n";
fs.writeFileSync(TMP, src);

const M = await import("./" + path.basename(TMP));

let pass = 0;
let fail = 0;
const ok = (name, cond, extra = "") => {
  if (cond) {
    pass++;
    console.log("PASS  " + name);
  } else {
    fail++;
    console.log("FAIL  " + name + (extra ? "  -> " + extra : ""));
  }
};

/* ---------- 1. schema 生成 ---------- */
const questions = {
  literal_question: { type: "noul", instructions: "literal?", criteria: { true: "a", false: "b" } },
  true_intent: {
    type: "choice",
    instructions: "intent?",
    criteria: { casual_chat: "x", vent_anger: "y", request_action: "z" },
  },
  danger_level: { type: "score", instructions: "danger?", criteria: ["b0", "b1", "b2", "b3"] },
  unknown_kind: { type: "wat", instructions: "?", criteria: {} },
};
const schema = M.jevJsonSchema(questions);
ok("schema: noul -> boolean", schema.properties.literal_question.type === "boolean");
ok(
  "schema: choice -> enum 来自 criteria keys",
  JSON.stringify(schema.properties.true_intent.enum) ===
    JSON.stringify(["casual_chat", "vent_anger", "request_action"])
);
ok(
  "schema: score -> integer 上界 = bins-1",
  schema.properties.danger_level.type === "integer" && schema.properties.danger_level.maximum === 3
);
ok("schema: 未知题型被跳过", schema.properties.unknown_kind === undefined);
ok(
  "schema: required 只含有效题",
  JSON.stringify(schema.required) ===
    JSON.stringify(["literal_question", "true_intent", "danger_level"])
);

/* ---------- 2. 提示词 ---------- */
const state = {
  chat: {
    relationship: "girlfriend",
    messages: [
      { from: "her", text: "你又忘了吧" },
      { from: "me", text: "抱歉" },
      { from: "her", text: "算了，我习惯了" },
    ],
    latest_from: "her",
  },
};
const sys = M.jevSystemPrompt(questions, state);
ok("prompt: 带 relationship", sys.includes("Relationship: girlfriend"));
ok("prompt: score 题列出 bins", sys.includes("0: b0") && sys.includes("3: b3"));
ok("prompt: choice 题列出 options", sys.includes("casual_chat: x"));
const usr = M.jevUserPrompt(state);
ok("prompt: 对方消息标 THEM", usr.includes("THEM: 你又忘了吧"));
ok("prompt: 我的消息标 ME", usr.includes("ME: 抱歉"));
ok("prompt: 中文原文保留", usr.includes("算了，我习惯了"));

/* ---------- 3. 端点行为 ---------- */
const baseEnv = {
  CREEM_API_KEY: "dummy",
  AI_UPSTREAM_URL: "https://example.com/v1",
  AI_UPSTREAM_KEY: "dummy",
  JEV_MODEL: "@cf/meta/llama-3.1-8b-instruct",
};

const post = (body, env, headers = {}) =>
  new Request("https://weauto.safeopc.cn/ai/jev/decisions", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: "Bearer TESTKEY",
      "x-weauto-instance": "INST01",
      ...headers,
    },
    body: JSON.stringify(body),
  });

const aiEnv = (mockFn) => ({ ...baseEnv, AI: { run: mockFn } });

// 3.1 正常：模型返回对象
{
  const env = aiEnv(async (model, opts) => {
    globalThis.__lastModel = model;
    globalThis.__lastOpts = opts;
    return {
      response: {
        literal_question: false,
        true_intent: "vent_anger",
        danger_level: 5,
      },
    };
  });
  const res = await M.jevDecisions(post({ state, questions }, env), env);
  const data = await res.json();
  ok("http: 200", res.status === 200, String(res.status));
  ok("answers: noul false -> 0", data.answers.literal_question.noul === 0);
  ok("answers: choice 原样", data.answers.true_intent.choice === "vent_anger");
  ok("answers: score 数字", data.answers.danger_level.score === 5);
  ok("provider 标记", data.provider === "cloudflare-workers-ai");
  ok("使用配置的模型", globalThis.__lastModel === "@cf/meta/llama-3.1-8b-instruct");
  ok(
    "下发了 response_format json_schema",
    globalThis.__lastOpts.response_format.type === "json_schema" &&
      !!globalThis.__lastOpts.response_format.json_schema
  );
  ok("未知题型不出现在 answers", data.answers.unknown_kind === undefined);
}

// 3.2 模型返回字符串 JSON（少数模型行为）
{
  const env = aiEnv(async () => ({ response: '{"literal_question": true}' }));
  const res = await M.jevDecisions(post({ state, questions }, env), env);
  const data = await res.json();
  ok("字符串 JSON 也能解析", res.status === 200 && data.answers.literal_question.noul === 1);
}

// 3.3 字符串裹代码块
{
  const env = aiEnv(async () => ({ response: '```json\n{"danger_level": 2}\n```' }));
  const res = await M.jevDecisions(post({ state, questions }, env), env);
  const data = await res.json();
  ok("代码块包裹也能解析", res.status === 200 && data.answers.danger_level.score === 2);
}

// 3.3b chat completions 风格（实测 8B 走这个形态：choices[0].message.content 是 JSON 字符串）
{
  const env = aiEnv(async () => ({
    choices: [{ message: { content: '{"true_intent":"vent_anger","danger_level":7}' } }],
  }));
  const res = await M.jevDecisions(post({ state, questions }, env), env);
  const data = await res.json();
  ok(
    "choices 形态也能解析",
    res.status === 200 &&
      data.answers.true_intent.choice === "vent_anger" &&
      data.answers.danger_level.score === 7
  );
}

// 3.3c 模型没按 schema 输出纯文本 -> 502（不能当成成功返回空 answers）
{
  const env = aiEnv(async () => ({ choices: [{ message: { content: "我不太明白" } }] }));
  const res = await M.jevDecisions(post({ state, questions }, env), env);
  ok("非 JSON 输出 -> 502", res.status === 502, String(res.status));
}

// 3.4 无卡密 -> 公开可用（不带 Authorization 也返回 200；卡密仅服务 Creem 支付授权）
{
  const env = aiEnv(async () => ({ choices: [{ message: { content: JSON.stringify({ true_intent: "casual_chat" }) } }] }));
  const res = await M.jevDecisions(
    new Request("https://weauto.safeopc.cn/ai/jev/decisions", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ state, questions }),
    }),
    env
  );
  ok("无卡密 -> 公开可用 200（不需要授权）", res.status === 200, String(res.status));
}

// 3.5 未绑定 AI -> 503
{
  const env = { ...baseEnv };
  const res = await M.jevDecisions(post({ state, questions }, env), env);
  ok("未绑定 Workers AI -> 503", res.status === 503);
}

// 3.6 空 questions -> 400
{
  const env = aiEnv(async () => ({ response: {} }));
  const res = await M.jevDecisions(post({ state, questions: {} }, env), env);
  ok("空 questions -> 400", res.status === 400);
}

// 3.7 upstream 抛错 -> 502（不是 500，也不该把异常抛穿）
{
  const env = aiEnv(async () => {
    throw new Error("model overloaded");
  });
  const res = await M.jevDecisions(post({ state, questions }, env), env);
  ok("AI 调用失败 -> 502", res.status === 502);
}

// 3.8 非 POST -> 405
{
  const env = aiEnv(async () => ({ response: {} }));
  const res = await M.jevDecisions(
    new Request("https://weauto.safeopc.cn/ai/jev/decisions", { method: "GET" }),
    env
  );
  ok("GET -> 405", res.status === 405);
}

fs.unlinkSync(TMP);
console.log(`\n结果: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);

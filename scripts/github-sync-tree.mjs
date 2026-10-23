// 递归建树同步（并行版）：自底向上为每个目录 POST 一个 tree（只含直接子项：文件=blob，子目录=tree 引用），
// 避开扁平 tree 的大 payload（2541 条目一次 POST 会被 GitHub 502）。
// blob 需先全部在远端（内容寻址、持久化）；本脚本只建树 + commit + ref。
//
// 前提：github.com 的 git 传输（git-receive-pack）在本机被重置，只能走 api.github.com。
// 同一层级的目录互不依赖，可并行；跨层级必须串行（子 tree 先于父 tree 存在）。
// 并发写会触发 GitHub 二级限流，用 --conc 3 并带指数退避。
//
// 用法: node sync-tree.mjs --repo samcaicn/gloai --branch dsh --root C:/code1/mu [--conc 3]
import { execFileSync } from 'node:child_process';

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? String(process.argv[i + 1]) : fallback;
}
const REPO = arg('repo', 'samcaicn/gloai');
const BRANCH = arg('branch', 'dsh');
const ROOT = arg('root', 'C:/code1/mu');
const CONC = Number(arg('conc', '3'));
const TOKEN = execFileSync('gh', ['auth', 'token'], { encoding: 'utf8' }).trim();

const API = 'https://api.github.com';
const HEADERS = {
  Authorization: `Bearer ${TOKEN}`,
  Accept: 'application/vnd.github+json',
  'X-GitHub-Api-Version': '2022-11-28',
  'Content-Type': 'application/json',
  'User-Agent': 'goglobal-sync',
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(path, init = {}, retries = 10) {
  for (let attempt = 1; ; attempt++) {
    let res;
    try {
      res = await fetch(`${API}${path}`, { ...init, headers: { ...HEADERS, ...(init.headers || {}) } });
    } catch (error) {
      if (attempt >= retries) throw new Error(`${init.method || 'GET'} ${path} -> network ${error.message}`);
      await sleep(Math.min(2 ** attempt * 1000, 30_000));
      continue;
    }
    if (res.ok) return res.json();
    const body = await res.text();
    // 二级限流（403/429）需要更长的退避；5xx 是 GitHub 侧抖动
    const limited = res.status === 403 || res.status === 429;
    if (attempt >= retries) {
      throw new Error(`${init.method || 'GET'} ${path} -> ${res.status}: ${body.slice(0, 200)}`);
    }
    const wait = limited ? Math.min(15_000 * attempt, 90_000) : Math.min(2 ** attempt * 1000, 20_000);
    if (attempt >= 3 || limited) {
      console.log(`  retry ${attempt}/${retries} after ${res.status} on ${path} (wait ${wait}ms)`);
    }
    await sleep(wait);
  }
}

const git = (args) => execFileSync('git', args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });

const localHead = git(['rev-parse', 'HEAD']).trim();
// -t 同时列出 tree：一次拿到每个目录的本地 tree sha，省掉 448 次 rev-parse 子进程
const lsTree = git(['ls-tree', '-r', '-t', '-z', 'HEAD']);

/** dir(含 '' 根) -> { files: [{name,mode,sha}], subdirs: Set<name>, localSha } */
const dirs = new Map();
const dirOf = (path) => {
  if (!dirs.has(path)) dirs.set(path, { files: [], subdirs: new Set(), localSha: null });
  return dirs.get(path);
};
let fileCount = 0;
for (const line of lsTree.split('\0').filter(Boolean)) {
  const m = /^(\d{6}) (\w+) ([0-9a-f]{40})\t([\s\S]+)$/.exec(line);
  if (!m) throw new Error('unparseable ls-tree line');
  const [, mode, type, sha, path] = m;
  const slash = path.lastIndexOf('/');
  const parent = slash < 0 ? '' : path.slice(0, slash);
  const name = slash < 0 ? path : path.slice(slash + 1);
  if (type === 'tree') {
    dirOf(path).localSha = sha;
    dirOf(parent).subdirs.add(name);
  } else {
    dirOf(parent).files.push({ name, mode, sha });
    fileCount++;
  }
}
dirOf('').localSha = git(['rev-parse', 'HEAD^{tree}']).trim();
console.log(`[local] HEAD=${localHead}`);
console.log(`[local] files=${fileCount}  dirs=${dirs.size}  root=${dirs.get('').localSha}`);

// 远端分支当前状态（404 = 不存在 -> 孤儿提交，确定性答案不重试）
let curSha = null;
{
  const res = await fetch(`${API}/repos/${REPO}/git/ref/heads/${BRANCH}`, { headers: HEADERS });
  if (res.ok) {
    curSha = (await res.json()).object.sha;
    console.log(`[remote] ${BRANCH}=${curSha}`);
  } else if (res.status === 404) {
    console.log(`[remote] ${BRANCH} 不存在 -> 孤儿提交`);
  } else {
    throw new Error(`GET ref -> ${res.status}`);
  }
}

const treeShaOf = new Map(); // dir -> 远端 tree sha
let created = 0;
let failed = 0;

/** 并发跑完一层；返回失败数 */
async function runLevel(levelDirs) {
  let idx = 0;
  let bad = 0;
  async function worker() {
    for (;;) {
      const i = idx++;
      if (i >= levelDirs.length) return;
      const dir = levelDirs[i];
      const { files, subdirs } = dirs.get(dir);
      const entries = [
        ...files.map((f) => ({ path: f.name, mode: f.mode, type: 'blob', sha: f.sha })),
        ...[...subdirs].map((name) => ({
          path: name,
          mode: '040000',
          type: 'tree',
          sha: treeShaOf.get(dir ? `${dir}/${name}` : name),
        })),
      ];
      if (entries.some((e) => e.type === 'tree' && !e.sha)) {
        console.error(`MISSING_SUBTREE dir="${dir || '(root)'}" — 中止`);
        process.exit(1);
      }
      try {
        const tree = await api(`/repos/${REPO}/git/trees`, {
          method: 'POST',
          body: JSON.stringify({ tree: entries }),
        });
        // 每个目录都与本地 tree sha 逐字节校验
        if (tree.sha !== dirs.get(dir).localSha) {
          console.error(
            `TREE_MISMATCH dir="${dir || '(root)'}" remote=${tree.sha} local=${dirs.get(dir).localSha} — 中止`
          );
          process.exit(1);
        }
        treeShaOf.set(dir, tree.sha);
        created++;
      } catch (error) {
        bad++;
        failed++;
        console.error(`TREE_FAIL dir="${dir || '(root)'}" ${error.message.slice(0, 160)}`);
      }
      if (created % 50 === 0) console.log(`  [trees] ${created}/${dirs.size}`);
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONC, levelDirs.length) }, worker));
  return bad;
}

// 自底向上按层级：深的先建（git 要求子 tree 先于父 tree 存在）
const byDepth = new Map();
for (const dir of dirs.keys()) {
  const d = dir ? dir.split('/').length : 0;
  if (!byDepth.has(d)) byDepth.set(d, []);
  byDepth.get(d).push(dir);
}
const depths = [...byDepth.keys()].sort((a, b) => b - a);
for (const d of depths) {
  const level = byDepth.get(d);
  await runLevel(level);
  console.log(`  [level ${d}] ${level.length} 个目录完成（累计 ${created}）`);
}

if (failed > 0) {
  console.error(`ABORT: ${failed} 个目录树建失败，不建 commit/ref`);
  process.exit(1);
}
console.log(`[trees] ${created} 个目录树全部与本地 sha 一致 ✓（root=${treeShaOf.get('')}）`);

const localMsg = git(['log', '-1', '--pretty=%B', 'HEAD']).trimEnd();
const msg = `${localMsg}\n\n（Git Data API 递归建树同步到构建分支；每层 tree 与本地 sha 校验一致）`;
const commit = await api(`/repos/${REPO}/git/commits`, {
  method: 'POST',
  body: JSON.stringify({ message: msg, tree: treeShaOf.get(''), parents: curSha ? [curSha] : [] }),
});
console.log(`[commit] ${commit.sha}`);

if (curSha) {
  await api(`/repos/${REPO}/git/refs/heads/${BRANCH}`, {
    method: 'PATCH',
    body: JSON.stringify({ sha: commit.sha, force: true }),
  });
} else {
  await api(`/repos/${REPO}/git/refs`, {
    method: 'POST',
    body: JSON.stringify({ ref: `refs/heads/${BRANCH}`, sha: commit.sha }),
  });
}
console.log(`SYNCED ${commit.sha} -> ${REPO}#${BRANCH}`);

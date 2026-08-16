// 流式文本打字机渲染器 —— 把一次性到达的完整文本平滑地逐字渲染。
//
// 背景：上游 LLM 接口虽返回 `text/event-stream`，但实测是"伪流式"——
// 服务器/代理缓冲整个响应，LLM 生成完成后在最后几十 ms 内一次性推送全部
// 内容帧（见 tupai.log [mcp_stream] 诊断：8.5s 空白后 60ms 内 10 个 chunk 全到）。
// 直接逐 chunk setState 会被 React 18 自动批处理合并成单次渲染，表现为
// "等很久后瞬间出全文"。本渲染器用 requestAnimationFrame 按匀速把目标文本
// 逐字追上，无论上游真假流式，前端都有稳定的逐字流式体验。
//
// 自适应速率（总时长约 1.5~2s，短文本慢打可见逐字，长文本快打不拖沓）：
//   charsPerSec = max(40, ceil(targetLen / 2))
//   - 25 字 → 40 字/秒 → ~0.6s
//   - 300 字 → 150 字/秒 → ~2s
//
// 生命周期：
//   push(fullText)   —— 每次 content 增量后调用，传入当前完整文本（非 delta）
//   finishStream()   —— LLM 流结束（done chunk / for-await 退出）时调用；
//                       若已打完则立即 onDone，否则打完后 onDone
//   cancel()         —— error / 卸载 / 中断时调用，停止 rAF 且不再回调 onDone

interface StreamingTypewriter {
  push: (fullText: string) => void;
  finishStream: () => void;
  cancel: () => void;
}

export function createStreamingTypewriter(
  render: (text: string) => void,
  onDone: () => void,
): StreamingTypewriter {
  let target = '';
  let shown = 0;
  let rafId: number | null = null;
  let streamDone = false;
  let canceled = false;
  let doneFired = false;

  const fireDone = () => {
    if (doneFired || canceled) return;
    doneFired = true;
    onDone();
  };

  const tick = () => {
    rafId = null;
    if (canceled) return;
    if (shown >= target.length) {
      if (streamDone) fireDone();
      return;
    }
    const charsPerSec = Math.max(40, Math.ceil(target.length / 2));
    const perFrame = Math.max(1, Math.ceil(charsPerSec / 60));
    shown = Math.min(target.length, shown + perFrame);
    render(target.slice(0, shown));
    if (shown >= target.length) {
      if (streamDone) fireDone();
    } else {
      rafId = requestAnimationFrame(tick);
    }
  };

  const schedule = () => {
    if (rafId !== null || canceled) return;
    if (shown >= target.length) return;
    rafId = requestAnimationFrame(tick);
  };

  return {
    push(fullText: string) {
      if (canceled) return;
      target = fullText;
      schedule();
    },
    finishStream() {
      if (canceled) return;
      streamDone = true;
      // 已打完（或从未有内容）→ 立即收尾；否则让 rAF 打完后收尾。
      if (shown >= target.length) {
        fireDone();
      } else {
        schedule();
      }
    },
    cancel() {
      canceled = true;
      if (rafId !== null) {
        cancelAnimationFrame(rafId);
        rafId = null;
      }
    },
  };
}

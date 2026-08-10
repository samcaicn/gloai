"""
SSE 协议解析器(服务端 + 客户端共用)。

对应 TypeScript: sdk/shared/sse.ts + backend/utils/sseStream.ts。

设计:
- parse_sse_stream(async_iter[bytes]) -> AsyncIterator[SseEvent]
  容忍任意 chunk 边界,内部 buffer 累积,遇 \n\n flush 一条
- encode_sse_event(SseEvent) -> str
- write_sse(async_iter[SseEvent]) -> AsyncIterator[bytes]
  把事件序列编码为 text/event-stream 字节

注释行 (`: hb`) 不产出事件,但心跳行 (: 开头) 也不应被忽略成事件 —
这里定义: 注释行 = 仅含冒号+可选文本的行,直接跳过。
"""

from __future__ import annotations
import json
from typing import AsyncIterator, Iterable, List, Optional

from .protocol import SseEvent, SseEventType


async def _parse_sse_fields(
    lines: AsyncIterator[str],
) -> AsyncIterator[SseEvent]:
    """SSE 字段行 → SseEvent 核心逻辑。

    输入: 已按行切分的 str async iter (每行含尾 \n, 兼容 \r\n)。
    """
    event_type: Optional[str] = None
    data_lines: List[str] = []
    event_id: Optional[str] = None
    retry_ms: Optional[int] = None

    async for raw in lines:
        line = raw
        if line.endswith("\n"):
            line = line[:-1]
        if line.endswith("\r"):
            line = line[:-1]
        if not line:
            if data_lines or event_type is not None:
                yield _build_event(event_type, data_lines, event_id, retry_ms)
                event_type = None
                data_lines = []
                event_id = None
                retry_ms = None
            continue
        if line.startswith(":"):
            continue
        field_name, _, value = line.partition(":")
        if value.startswith(" "):
            value = value[1:]
        if field_name == "event":
            event_type = value
        elif field_name == "data":
            data_lines.append(value)
        elif field_name == "id":
            event_id = value
        elif field_name == "retry":
            try:
                retry_ms = int(value)
            except ValueError:
                retry_ms = None

    if data_lines or event_type is not None:
        yield _build_event(event_type, data_lines, event_id, retry_ms)


async def _decode_lines(
    lines: AsyncIterator[bytes],
) -> AsyncIterator[str]:
    async for raw in lines:
        try:
            yield raw.decode("utf-8")
        except UnicodeDecodeError:
            continue


async def parse_sse_stream(
    lines: AsyncIterator[bytes],
) -> AsyncIterator[SseEvent]:
    """解析 SSE 字节流,产出 SseEvent。

    输入: 任意切分的 bytes async iter (网络 chunk 边界任意)
    """
    buffered = _LineBuffer(lines)
    async for evt in _parse_sse_fields(_decode_lines(buffered)):
        yield evt


async def parse_sse_lines(
    lines: AsyncIterator[str],
) -> AsyncIterator[SseEvent]:
    """解析 SSE 字符串行流,产出 SseEvent。

    输入: 按行切分的 str async iter (httpx.aiter_lines() 输出可直接喂入)。
    相比 parse_sse_stream 省去 bytes 编解码, 适合上游已产出 str 的场景。
    """
    async for evt in _parse_sse_fields(lines):
        yield evt


def _build_event(
    event_type: Optional[str],
    data_lines: List[str],
    event_id: Optional[str],
    retry_ms: Optional[int],
) -> SseEvent:
    """把字段集合打包成 SseEvent。"""
    # 缺失 event 字段时按 'message' 处理
    if event_type is None:
        event_type = SseEventType.MESSAGE.value
    # 验证 event_type 是已知枚举(未知也保留,便于扩展)
    try:
        evt_enum = SseEventType(event_type)
    except ValueError:
        evt_enum = SseEventType.MESSAGE

    # 拼 data: 多行用 \n 连接,然后尝试 JSON parse
    joined = "\n".join(data_lines)
    try:
        parsed = json.loads(joined)
        if not isinstance(parsed, dict):
            parsed = {"value": parsed}
    except (json.JSONDecodeError, ValueError):
        # 纯文本 data,放到 {"text": ...}
        parsed = {"text": joined}

    return SseEvent(
        type=evt_enum,
        data=parsed,
        id=event_id,
        retry_ms=retry_ms,
    )


async def write_sse(events: AsyncIterator[SseEvent]) -> AsyncIterator[bytes]:
    """把事件流编码成 text/event-stream 字节。"""
    async for evt in events:
        yield evt.encode().encode("utf-8")


def encode_sse_event(event: SseEvent) -> bytes:
    """单条事件编码(同步版本,便于测试)。"""
    return event.encode().encode("utf-8")


# ===== 字节流 -> 行迭代器 工具(供 parse_sse_stream 消费) =====
class _LineBuffer:
    """把任意切分的字节累积成行,按行产出 bytes(含 \n)。

    安全: 累积 buffer 超 MAX_LINE_SIZE (64KB) 则抛异常,防止内存耗尽。
    """

    MAX_LINE_SIZE = 65536  # 64KB

    def __init__(self, source: AsyncIterator[bytes]):
        self.source = source
        self.buf = b""

    async def __aiter__(self) -> AsyncIterator[bytes]:
        async for chunk in self.source:
            self.buf += chunk
            # 安全: 单次累积超过 MAX_LINE_SIZE 则拒绝(防止缓冲区膨胀攻击)
            if len(self.buf) > self.MAX_LINE_SIZE:
                raise ValueError(
                    f"SSE line buffer overflow: {len(self.buf)} bytes > {self.MAX_LINE_SIZE} limit"
                )
            while True:
                idx = self.buf.find(b"\n")
                if idx < 0:
                    break
                line = self.buf[: idx + 1]  # 含 \n
                self.buf = self.buf[idx + 1 :]
                yield line
        # 尾部残余
        if self.buf:
            yield self.buf + b"\n"  # 规整成行
            self.buf = b""


def line_buffer(source: AsyncIterator[bytes]) -> AsyncIterator[bytes]:
    """包装任意 bytes async iter 为按行产出(便于喂 parse_sse_stream)。"""
    return _LineBuffer(source).__aiter__()


# ===== 同步行迭代器(便于测试) =====
async def from_iterable(items: Iterable[bytes]) -> AsyncIterator[bytes]:
    """测试用:把可迭代对象转 async iter。"""
    for x in items:
        yield x
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { AIConfigCard } from "@/components/ai-config-card";
import { useLLMUsage, useMediaUsage } from "@/hooks/use-admin";

function fmt(n: number): string {
  return n.toLocaleString("en-US");
}

function relTime(sec: number): string {
  if (!sec) return "—";
  const diff = Math.floor(Date.now() / 1000) - sec;
  if (diff < 60) return "刚刚";
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`;
  if (diff < 86400 * 30) return `${Math.floor(diff / 86400)} 天前`;
  return new Date(sec * 1000).toLocaleDateString("zh-CN");
}

function modelTypeLabel(t: string): string {
  switch (t) {
    case "embedding":
      return "嵌入 (Embedding)";
    case "chat":
      return "对话 (Chat)";
    default:
      return t || "对话 (Chat)";
  }
}

function mediaTypeLabel(t: string): string {
  switch (t) {
    case "image":
      return "生图 (Image)";
    case "video":
      return "生视频 (Video)";
    case "audio":
      return "音频 (Audio)";
    default:
      return t || "生图 (Image)";
  }
}

export function AdminLLMPage() {
  const { data, isLoading } = useLLMUsage();
  const rows = data?.rows ?? [];
  const totals = data?.totals;

  const { data: mediaData, isLoading: mediaLoading } = useMediaUsage();
  const mediaRows = mediaData?.rows ?? [];
  const mediaTotals = mediaData?.totals;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">LLM 配置</h1>
        <p className="text-sm text-muted-foreground mt-0.5">
          OpenAI 兼容接口与平台 LLM 用量。所有 AI 请求（对话 / 嵌入）均经由此处配置的接口，
          并按租户、模型、类型累计 token 消耗，为后续计费预留数据。
        </p>
      </div>

      <AIConfigCard />

      <Card className="border-border/50 bg-card/30">
        <CardHeader>
          <CardTitle>LLM 用量（按租户 / 模型 / 类型）</CardTitle>
          <CardDescription>
            每条记录为某租户在某模型、某类型下的 token 消耗汇总。租户即微信账号（bot）；
            内置应用（如甲乙方对聊）以会话维度计入。
          </CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <p className="text-sm text-muted-foreground py-8 text-center">加载中…</p>
          ) : rows.length === 0 ? (
            <p className="text-sm text-muted-foreground py-8 text-center">暂无 LLM 调用记录。</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>租户</TableHead>
                  <TableHead>模型</TableHead>
                  <TableHead>类型</TableHead>
                  <TableHead className="text-right">提示词</TableHead>
                  <TableHead className="text-right">补全</TableHead>
                  <TableHead className="text-right">总计</TableHead>
                  <TableHead className="text-right">缓存</TableHead>
                  <TableHead className="text-right">推理</TableHead>
                  <TableHead className="text-right">调用</TableHead>
                  <TableHead className="text-right">最近</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r, i) => (
                  <TableRow key={`${r.tenant_id}-${r.model}-${r.model_type}-${i}`}>
                    <TableCell>
                      <div className="font-medium">{r.tenant_name || r.tenant_id}</div>
                      <div className="text-xs text-muted-foreground font-mono truncate max-w-[160px]">
                        {r.tenant_id}
                      </div>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{r.model}</TableCell>
                    <TableCell className="text-xs">{modelTypeLabel(r.model_type)}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {fmt(r.prompt_tokens)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {fmt(r.completion_tokens)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums font-semibold">
                      {fmt(r.total_tokens)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {fmt(r.cached_tokens)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {fmt(r.reasoning_tokens)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{fmt(r.call_count)}</TableCell>
                    <TableCell className="text-right text-xs text-muted-foreground">
                      {relTime(r.last_at)}
                    </TableCell>
                  </TableRow>
                ))}
                {totals && (
                  <TableRow className="border-t-2 border-border/60 bg-muted/20">
                    <TableCell className="font-semibold">合计</TableCell>
                    <TableCell />
                    <TableCell />
                    <TableCell className="text-right tabular-nums font-semibold">
                      {fmt(totals.prompt_tokens)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums font-semibold">
                      {fmt(totals.completion_tokens)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums font-semibold">
                      {fmt(totals.total_tokens)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums font-semibold">
                      {fmt(totals.cached_tokens)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums font-semibold">
                      {fmt(totals.reasoning_tokens)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums font-semibold">
                      {fmt(totals.call_count)}
                    </TableCell>
                    <TableCell />
                  </TableRow>
                )}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card className="border-border/50 bg-card/30">
        <CardHeader>
          <CardTitle>媒体生成用量（生图 / 生视频 / 音频）</CardTitle>
          <CardDescription>
            每次生成请求按 次数 与 时长（秒）累计。生图仅计次数（时长 0），生视频 /
            音频计生成内容时长。预留给后续按媒体类型计费。
          </CardDescription>
        </CardHeader>
        <CardContent>
          {mediaLoading ? (
            <p className="text-sm text-muted-foreground py-8 text-center">加载中…</p>
          ) : mediaRows.length === 0 ? (
            <p className="text-sm text-muted-foreground py-8 text-center">暂无媒体生成记录。</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>租户</TableHead>
                  <TableHead>模型</TableHead>
                  <TableHead>类型</TableHead>
                  <TableHead className="text-right">次数</TableHead>
                  <TableHead className="text-right">时长(秒)</TableHead>
                  <TableHead className="text-right">请求数</TableHead>
                  <TableHead className="text-right">最近</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {mediaRows.map((r, i) => (
                  <TableRow key={`${r.tenant_id}-${r.model}-${r.media_type}-${i}`}>
                    <TableCell>
                      <div className="font-medium">{r.tenant_name || r.tenant_id}</div>
                      <div className="text-xs text-muted-foreground font-mono truncate max-w-[160px]">
                        {r.tenant_id}
                      </div>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{r.model}</TableCell>
                    <TableCell className="text-xs">{mediaTypeLabel(r.media_type)}</TableCell>
                    <TableCell className="text-right tabular-nums font-semibold">
                      {fmt(r.count)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {fmt(r.duration_seconds)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{fmt(r.call_count)}</TableCell>
                    <TableCell className="text-right text-xs text-muted-foreground">
                      {relTime(r.last_at)}
                    </TableCell>
                  </TableRow>
                ))}
                {mediaTotals && (
                  <TableRow className="border-t-2 border-border/60 bg-muted/20">
                    <TableCell className="font-semibold">合计</TableCell>
                    <TableCell />
                    <TableCell />
                    <TableCell className="text-right tabular-nums font-semibold">
                      {fmt(mediaTotals.count)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums font-semibold">
                      {fmt(mediaTotals.duration_seconds)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums font-semibold">
                      {fmt(mediaTotals.call_count)}
                    </TableCell>
                    <TableCell />
                  </TableRow>
                )}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

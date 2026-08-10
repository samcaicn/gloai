import { useState } from "react";
import { Loader2, Plus, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useToast } from "@/hooks/use-toast";
import { useAIConfig, useSaveAIConfig } from "@/hooks/use-admin";
import { api } from "@/lib/api";

// AIConfigCard is the OpenAI-compatible LLM interface configuration form. It is
// shared by the admin overview and the dedicated "LLM 配置" page.
export function AIConfigCard() {
  const { data: aiConfigData } = useAIConfig();
  const [aiConfig, setAIConfig] = useState<any>(null);
  const [fetchedModels, setFetchedModels] = useState<string[]>([]);
  const [fetchingModels, setFetchingModels] = useState(false);
  const saveAIMutation = useSaveAIConfig();
  const { toast } = useToast();

  // Sync query data into local state for form editing
  const effectiveAIConfig = aiConfig ?? aiConfigData;

  async function handleSaveAI() {
    if (!effectiveAIConfig) return;
    try {
      await saveAIMutation.mutateAsync(effectiveAIConfig);
      toast({ title: "全局 AI 配置已保存" });
    } catch (e: any) {
      toast({ variant: "destructive", title: "保存失败", description: e.message });
    }
  }

  async function handleFetchModels() {
    const base = effectiveAIConfig?.base_url || "";
    const key = effectiveAIConfig?.api_key || "";
    if (!base || !key) {
      toast({ variant: "destructive", title: "请先填写接口地址与 API Key" });
      return;
    }
    setFetchingModels(true);
    try {
      const data = await api.fetchAIModels({ base_url: base, api_key: key });
      const ids: string[] = (data?.models || [])
        .map((m: any) => m.id)
        .filter((s: unknown): s is string => typeof s === "string" && s.length > 0);
      setFetchedModels(ids);
      // Fill the available-models list and keep any already-chosen default model.
      updateAIConfig({ available_models: JSON.stringify(ids) });
      toast({ title: `已从 /models 获取 ${ids.length} 个模型` });
    } catch (e: any) {
      toast({ variant: "destructive", title: "获取模型失败", description: e.message });
    } finally {
      setFetchingModels(false);
    }
  }

  function updateAIConfig(patch: any) {
    setAIConfig((prev: any) => ({ ...(prev ?? aiConfigData), ...patch }));
  }

  return (
    <Card className="border-border/50 bg-card/50">
      <CardHeader>
        <CardTitle>OpenAI 接口配置</CardTitle>
        <CardDescription>
          配置 OpenAI 兼容接口（接口地址 / API Key / 模型），作为主系统的默认 AI 接口， 内置应用（如
          AI 转型驾驶舱）也会复用此配置。平台所有 LLM 请求均经由此处配置。
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Tabs defaultValue="basic">
          <TabsList className="mb-4">
            <TabsTrigger value="basic">基础</TabsTrigger>
            <TabsTrigger value="advanced">高级</TabsTrigger>
          </TabsList>

          <TabsContent value="basic" className="space-y-4 mt-0">
            <div className="space-y-1.5">
              <Label className="text-xs font-bold uppercase text-muted-foreground">
                接口地址 (OpenAI 兼容)
              </Label>
              <Input
                value={effectiveAIConfig?.base_url || ""}
                onChange={(e) => updateAIConfig({ base_url: e.target.value })}
                placeholder="https://api.openai.com/v1"
              />
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs font-bold uppercase text-muted-foreground">默认模型</Label>
              <div className="flex gap-2">
                <Input
                  value={effectiveAIConfig?.model || ""}
                  onChange={(e) => updateAIConfig({ model: e.target.value })}
                  placeholder="例如 gpt-4o-mini"
                />
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="shrink-0 whitespace-nowrap"
                  onClick={handleFetchModels}
                  disabled={
                    fetchingModels || !effectiveAIConfig?.base_url || !effectiveAIConfig?.api_key
                  }
                >
                  {fetchingModels ? <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" /> : null}从
                  /models 获取
                </Button>
              </div>
              {fetchedModels.length > 0 ? (
                <Select
                  value={effectiveAIConfig?.model || ""}
                  onValueChange={(v) => updateAIConfig({ model: v })}
                >
                  <SelectTrigger className="h-8 text-xs">
                    <SelectValue placeholder="从已获取模型中选取默认模型" />
                  </SelectTrigger>
                  <SelectContent>
                    {fetchedModels.map((m) => (
                      <SelectItem key={m} value={m} className="text-xs font-mono">
                        {m}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : null}
              <p className="text-xs text-muted-foreground">
                点击「从 /models 获取」可从接口拉取可用模型并选择默认模型（同时填充可用模型列表）。
              </p>
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs font-bold uppercase text-muted-foreground">API Key</Label>
              <Input
                type="password"
                value={effectiveAIConfig?.api_key || ""}
                onChange={(e) => updateAIConfig({ api_key: e.target.value })}
                placeholder="••••••••"
              />
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs font-bold uppercase text-muted-foreground">
                系统提示词
              </Label>
              <Textarea
                rows={4}
                value={effectiveAIConfig?.system_prompt || ""}
                onChange={(e) => updateAIConfig({ system_prompt: e.target.value })}
                placeholder="设置 AI 的系统角色提示词"
                className="resize-y text-sm"
              />
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs font-bold uppercase text-muted-foreground">
                历史消息轮数
              </Label>
              <Input
                type="number"
                min={0}
                value={effectiveAIConfig?.max_history || ""}
                onChange={(e) => updateAIConfig({ max_history: e.target.value })}
                placeholder="默认 20 轮"
              />
              <p className="text-xs text-muted-foreground">
                AI 对话时携带的历史消息轮数，0 表示不携带历史。
              </p>
            </div>
          </TabsContent>

          <TabsContent value="advanced" className="space-y-4 mt-0">
            <div className="space-y-1.5">
              <Label className="text-xs font-bold uppercase text-muted-foreground">
                可用模型列表
              </Label>
              {(() => {
                let models: string[] = [];
                try {
                  if (effectiveAIConfig?.available_models) {
                    const parsed = JSON.parse(effectiveAIConfig.available_models);
                    if (Array.isArray(parsed))
                      models = parsed.filter((s: unknown) => typeof s === "string");
                  }
                } catch {}

                const setModels = (next: string[]) => {
                  setAIConfig((prev: any) => ({
                    ...(prev ?? aiConfigData),
                    available_models: JSON.stringify(next),
                  }));
                };

                return (
                  <div className="space-y-2">
                    {models.length > 0 && (
                      <div className="flex flex-wrap gap-1.5">
                        {models.map((m, i) => (
                          <span
                            key={i}
                            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-muted border text-xs font-mono"
                          >
                            {m}
                            <button
                              type="button"
                              className="ml-0.5 text-muted-foreground hover:text-destructive"
                              onClick={() => setModels(models.filter((_, j) => j !== i))}
                            >
                              ×
                            </button>
                          </span>
                        ))}
                      </div>
                    )}
                    <Input
                      placeholder="输入模型名称，按回车添加"
                      onKeyDown={(e) => {
                        if (e.key === "Enter") {
                          e.preventDefault();
                          const v = (e.target as HTMLInputElement).value.trim();
                          if (v && !models.includes(v)) {
                            setModels([...models, v]);
                            (e.target as HTMLInputElement).value = "";
                          }
                        }
                      }}
                    />
                  </div>
                );
              })()}
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs font-bold uppercase text-muted-foreground">
                自定义 Headers
              </Label>
              <p className="text-xs text-muted-foreground">
                调用 AI 接口时附加的 HTTP 请求头，例如 OpenRouter 归属信息。
              </p>
              <div className="space-y-2">
                {(() => {
                  let entries: [string, string][] = [];
                  try {
                    const raw = effectiveAIConfig?.custom_headers;
                    if (raw) {
                      const parsed = JSON.parse(raw);
                      entries = Array.isArray(parsed) ? parsed : Object.entries(parsed);
                    }
                  } catch {}

                  const sync = (next: [string, string][]) => {
                    updateAIConfig({ custom_headers: next.length ? JSON.stringify(next) : "" });
                  };

                  return (
                    <>
                      {entries.map(([key, val], i) => (
                        <div key={i} className="flex gap-2 items-center">
                          <Input
                            className="flex-1"
                            placeholder="Header Name"
                            value={key}
                            onChange={(e) => {
                              const next = [...entries];
                              next[i] = [e.target.value, val];
                              sync(next);
                            }}
                          />
                          <Input
                            className="flex-1"
                            placeholder="Value"
                            value={val}
                            onChange={(e) => {
                              const next = [...entries];
                              next[i] = [key, e.target.value];
                              sync(next);
                            }}
                          />
                          <Button
                            variant="ghost"
                            size="icon"
                            className="shrink-0 h-8 w-8 text-muted-foreground hover:text-destructive"
                            onClick={() => sync(entries.filter((_, j) => j !== i))}
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </Button>
                        </div>
                      ))}
                      <Button
                        variant="outline"
                        size="sm"
                        className="w-full"
                        onClick={() => sync([...entries, ["", ""]])}
                      >
                        <Plus className="h-3.5 w-3.5 mr-1" />
                        添加 Header
                      </Button>
                    </>
                  );
                })()}
              </div>
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs font-bold uppercase text-muted-foreground">
                媒体生成模型（生图 / 生视频 / 音频）
              </Label>
              <p className="text-xs text-muted-foreground">
                复用上方全局接口地址与 API Key，仅需填写各类型的模型名。生成次数与时长会记入「LLM
                配置」用量表，为计费预留数据。
              </p>
              <Input
                value={effectiveAIConfig?.image_model || ""}
                onChange={(e) => updateAIConfig({ image_model: e.target.value })}
                placeholder="生图模型，如 dall-e-3"
              />
              <Input
                value={effectiveAIConfig?.video_model || ""}
                onChange={(e) => updateAIConfig({ video_model: e.target.value })}
                placeholder="生视频模型，如 sora / 自定义"
              />
              <Input
                value={effectiveAIConfig?.audio_model || ""}
                onChange={(e) => updateAIConfig({ audio_model: e.target.value })}
                placeholder="音频模型（TTS），如 tts-1"
              />
            </div>
            <div className="flex items-center justify-between p-3 rounded-xl bg-muted/20 border border-border/50">
              <div>
                <p className="text-sm font-medium">隐藏思考过程</p>
                <p className="text-xs text-muted-foreground">
                  启用后不会将模型的思考内容发送给用户
                </p>
              </div>
              <Switch
                checked={effectiveAIConfig?.hide_thinking === "true"}
                onCheckedChange={(checked) =>
                  updateAIConfig({ hide_thinking: checked ? "true" : "false" })
                }
              />
            </div>
            <div className="flex items-center justify-between p-3 rounded-xl bg-muted/20 border border-border/50">
              <div>
                <p className="text-sm font-medium">Markdown 转纯文本</p>
                <p className="text-xs text-muted-foreground">
                  启用后将 AI 回复中的 Markdown 格式转为纯文本
                </p>
              </div>
              <Switch
                checked={effectiveAIConfig?.strip_markdown === "true"}
                onCheckedChange={(checked) =>
                  updateAIConfig({ strip_markdown: checked ? "true" : "false" })
                }
              />
            </div>
          </TabsContent>
        </Tabs>
      </CardContent>
      <CardFooter className="flex justify-end">
        <Button onClick={handleSaveAI} disabled={saveAIMutation.isPending}>
          保存
        </Button>
      </CardFooter>
    </Card>
  );
}

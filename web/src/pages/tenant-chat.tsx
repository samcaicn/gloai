import { useCallback, useEffect, useRef, useState } from "react";
import {
  Play,
  Pause,
  StepForward,
  RotateCcw,
  MessagesSquare,
  Loader2,
  Settings2,
  AlertTriangle,
  UserPlus,
  LogIn,
  Copy,
  Check,
  Search,
  DoorOpen,
} from "lucide-react";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Switch } from "../components/ui/switch";
import { Card, CardHeader, CardTitle, CardContent, CardDescription } from "../components/ui/card";
import { Badge } from "../components/ui/badge";
import { api } from "../lib/api";
import { useToast } from "@/hooks/use-toast";

type Side = "A" | "B";
type Status = "waiting" | "idle" | "running" | "paused" | "error";

interface Participant {
  name: string;
  system_prompt: string;
  user_id: string;
  joined: boolean;
}
interface ChatMessage {
  seq: number;
  side: Side;
  content: string;
  thinking?: string;
  created_at: number;
}
interface Conversation {
  id: string;
  participants: Record<Side, Participant>;
  messages: ChatMessage[];
  status: Status;
  topic: string;
  max_rounds: number;
  delay_ms: number;
  turn: Side;
  round_count: number;
  error?: string;
  invite_code?: string;
  created_by: string;
  created_at: number;
  updated_at: number;
}
interface TenantChatView {
  conversation: Conversation;
  my_side: Side | "";
  ai_configured: boolean;
}
interface PassiveProfile {
  user_id: string;
  enabled: boolean;
  handle?: string;
  name: string;
  system_prompt: string;
  topic: string;
  max_rounds: number;
  delay_ms: number;
  updated_at: number;
}
interface MineView {
  conversations: TenantChatView[];
  ai_configured: boolean;
  passive: PassiveProfile | null;
}

const SIDE_META: Record<Side, { label: string; color: string; bubble: string; align: string }> = {
  A: {
    label: "甲",
    color: "text-sky-600",
    bubble: "bg-sky-500 text-white",
    align: "justify-start",
  },
  B: {
    label: "乙",
    color: "text-emerald-600",
    bubble: "bg-emerald-500 text-white",
    align: "justify-end",
  },
};

const STATUS_LABEL: Record<Status, string> = {
  waiting: "等待对方加入",
  idle: "空闲",
  running: "对聊中",
  paused: "已暂停",
  error: "出错",
};

function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    return navigator.clipboard.writeText(text);
  }
  return Promise.reject();
}

export function TenantChatPage() {
  const { toast } = useToast();
  const [mine, setMine] = useState<MineView | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  // join (作为乙) form
  const [showJoin, setShowJoin] = useState(false);
  const [joinId, setJoinId] = useState("");
  const [joinCode, setJoinCode] = useState("");

  const selected = mine?.conversations.find((c) => c.conversation.id === selectedId) ?? null;

  const load = useCallback(async () => {
    try {
      const data = await api.tenantChatMine();
      setMine(data);
    } catch (e: any) {
      if (e.message !== "unauthorized") {
        toast({ variant: "destructive", title: "加载失败", description: e.message });
      }
    } finally {
      setLoading(false);
    }
  }, [toast]);

  // Deep links: ?join=ID&code=CODE / ?find=HANDLE / ?conv=ID
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const j = params.get("join");
    const c = params.get("code");
    const cv = params.get("conv");
    if (j) {
      setJoinId(j);
      setShowJoin(true);
    }
    if (c) setJoinCode(c);
    if (cv) setSelectedId(cv);
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function createConv() {
    setBusy(true);
    try {
      const data = await api.tenantChatCreate();
      setSelectedId(data.conversation.id);
      toast({ title: "已创建对聊（你是甲），把邀请码发给乙方" });
      await load();
    } catch (e: any) {
      toast({ variant: "destructive", title: "创建失败", description: e.message });
    } finally {
      setBusy(false);
    }
  }

  async function joinConv() {
    if (!joinId || !joinCode) {
      toast({ variant: "destructive", title: "请填写会话 ID 与邀请码" });
      return;
    }
    setBusy(true);
    try {
      const data = await api.tenantChatJoin(joinId.trim(), joinCode.trim());
      setSelectedId(data.conversation.id);
      window.history.replaceState({}, "", window.location.pathname);
      toast({ title: "已加入对聊（你是乙）" });
      await load();
    } catch (e: any) {
      toast({ variant: "destructive", title: "加入失败", description: e.message });
    } finally {
      setBusy(false);
    }
  }

  async function savePassive(p: PassiveProfile) {
    setBusy(true);
    try {
      await api.tenantChatPassiveSet({
        enabled: p.enabled,
        handle: p.handle,
        name: p.name,
        system_prompt: p.system_prompt,
        topic: p.topic,
        max_rounds: p.max_rounds,
        delay_ms: p.delay_ms,
      });
      toast({ title: "被动会话设置已保存" });
      await load();
    } catch (e: any) {
      toast({ variant: "destructive", title: "保存失败", description: e.message });
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20 text-muted-foreground">
        <Loader2 className="w-5 h-5 animate-spin mr-2" /> 加载中…
      </div>
    );
  }

  if (selected) {
    return (
      <ChatDetail
        view={selected}
        onBack={() => setSelectedId(null)}
        reload={load}
        busy={busy}
        setBusy={setBusy}
      />
    );
  }

  const passive = mine?.passive ?? null;

  return (
    <div className="space-y-5">
      <div className="flex items-center gap-2">
        <MessagesSquare className="w-6 h-6 text-primary" />
        <h1 className="text-2xl font-bold tracking-tight">甲乙方 AI 对聊</h1>
        {mine?.ai_configured ? (
          <Badge variant="secondary">系统 AI 已配置</Badge>
        ) : (
          <Badge variant="destructive" className="gap-1">
            <AlertTriangle className="w-3 h-3" /> 系统 AI 未配置
          </Badge>
        )}
      </div>

      {!mine?.ai_configured ? (
        <Card>
          <CardContent className="text-sm text-muted-foreground py-3">
            尚未配置全局 AI。请前往「系统管理 → AI 设置」填写 API Key 与模型后，再开始对聊。
          </CardContent>
        </Card>
      ) : null}

      {/* 被动会话设置（别人找你聊） */}
      <PassiveSettingsCard passive={passive} onSave={savePassive} busy={busy} />

      {/* 发起对聊 */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">发起对聊</CardTitle>
          <CardDescription>
            两种方式：你主动创建并邀请乙方；或你凭邀请码加入别人的对聊。被动会话由对方开启后在专门页面发起。
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <Button
              onClick={createConv}
              disabled={busy || !mine?.ai_configured}
              className="h-9 gap-1"
            >
              <UserPlus className="w-4 h-4" /> 创建对聊（我作为甲）
            </Button>
            <Button
              variant="outline"
              className="h-9 gap-1"
              onClick={() => setShowJoin((v) => !v)}
              disabled={!mine?.ai_configured}
            >
              <LogIn className="w-4 h-4" /> 加入对聊（我作为乙）
            </Button>
          </div>

          {showJoin ? (
            <div className="grid gap-2 sm:grid-cols-3 rounded-md border p-3">
              <Input
                placeholder="会话 ID"
                value={joinId}
                onChange={(e) => setJoinId(e.target.value)}
                className="h-8 text-xs"
              />
              <Input
                placeholder="邀请码"
                value={joinCode}
                onChange={(e) => setJoinCode(e.target.value)}
                className="h-8 text-xs"
              />
              <Button onClick={joinConv} disabled={busy} className="h-8">
                加入
              </Button>
            </div>
          ) : null}
        </CardContent>
      </Card>

      {/* 我的对聊列表 */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">我的对聊</CardTitle>
          <CardDescription>你参与的所有对聊（主动创建 / 受邀加入 / 被动被找）。</CardDescription>
        </CardHeader>
        <CardContent>
          {mine && mine.conversations.length > 0 ? (
            <div className="space-y-2">
              {mine.conversations.map((v) => {
                const c = v.conversation;
                const partnerSide: Side = v.my_side === "B" ? "A" : "B";
                const partner = c.participants[partnerSide];
                const paired = !!c.participants.A.user_id && !!c.participants.B.user_id;
                return (
                  <div
                    key={c.id}
                    className="flex items-center justify-between gap-3 rounded-md border p-3"
                  >
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="font-medium truncate">{partner?.name || "对方"}</span>
                        <Badge variant={c.status === "running" ? "default" : "outline"}>
                          {STATUS_LABEL[c.status]}
                        </Badge>
                        {v.my_side ? (
                          <Badge variant="secondary">我是{SIDE_META[v.my_side].label}</Badge>
                        ) : null}
                        {!paired && v.my_side === "A" ? (
                          <Badge variant="outline">待乙方加入</Badge>
                        ) : null}
                      </div>
                      <p className="text-xs text-muted-foreground truncate">
                        {c.messages.length > 0
                          ? `已 ${c.messages.length} 条 · ${c.topic}`
                          : c.topic}
                      </p>
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      className="h-8 gap-1 shrink-0"
                      onClick={() => setSelectedId(c.id)}
                    >
                      <DoorOpen className="w-3.5 h-3.5" /> 打开
                    </Button>
                  </div>
                );
              })}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground py-4 text-center">
              还没有对聊。创建一场，或让对方用你的被动会话口令找你。
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

// ---- 被动会话设置卡片 ----
function PassiveSettingsCard({
  passive,
  onSave,
  busy,
}: {
  passive: PassiveProfile | null;
  onSave: (p: PassiveProfile) => void;
  busy: boolean;
}) {
  const [enabled, setEnabled] = useState(!!passive?.enabled);
  const [name, setName] = useState(passive?.name ?? "");
  const [prompt, setPrompt] = useState(passive?.system_prompt ?? "");
  const [topic, setTopic] = useState(passive?.topic ?? "");
  const [rounds, setRounds] = useState(passive?.max_rounds ?? 12);
  const [delay, setDelay] = useState(passive?.delay_ms ?? 1500);

  // keep draft in sync if the server profile changes (e.g. after save/load)
  useEffect(() => {
    setEnabled(!!passive?.enabled);
    setName(passive?.name ?? "");
    setPrompt(passive?.system_prompt ?? "");
    setTopic(passive?.topic ?? "");
    setRounds(passive?.max_rounds ?? 12);
    setDelay(passive?.delay_ms ?? 1500);
  }, [passive]);

  function save() {
    onSave({
      user_id: passive?.user_id ?? "",
      enabled,
      name: name.trim(),
      system_prompt: prompt,
      topic: topic.trim(),
      max_rounds: rounds,
      delay_ms: delay,
      updated_at: passive?.updated_at ?? 0,
    });
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base flex items-center gap-2">
          <Settings2 className="w-4 h-4" /> 被动会话设置（别人找你聊）
        </CardTitle>
        <CardDescription>
          打开开关即可允许别人向你发起对聊。下面的参数就是别人找你聊时，自动套用在你席位上的人设与默认设置（无需口令）。
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex items-center justify-between rounded-md border p-3">
          <div>
            <div className="text-sm font-medium">允许别人找我聊</div>
            <div className="text-xs text-muted-foreground">
              打开后，其他用户可在「找人聊」页面向你发起对聊。
            </div>
          </div>
          <Switch checked={enabled} onCheckedChange={setEnabled} />
        </div>

        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">展示名称</label>
          <Input
            placeholder="对方看到你的称呼"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="h-8 text-xs"
          />
        </div>

        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">人设 / 系统提示词</label>
          <textarea
            placeholder="别人找你聊时，AI 以这个身份与你对话"
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            rows={4}
            className="w-full rounded-md border border-input bg-transparent px-2 py-1 text-xs font-mono placeholder:text-muted-foreground/40 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 resize-y"
          />
        </div>

        <div className="grid gap-3 md:grid-cols-3">
          <div className="space-y-1">
            <label className="text-xs text-muted-foreground">默认话题</label>
            <Input
              value={topic}
              onChange={(e) => setTopic(e.target.value)}
              className="h-8 text-xs"
            />
          </div>
          <div className="space-y-1">
            <label className="text-xs text-muted-foreground">轮数上限</label>
            <Input
              type="number"
              min={1}
              max={200}
              value={rounds}
              onChange={(e) => setRounds(Number(e.target.value))}
              className="h-8 text-xs"
            />
          </div>
          <div className="space-y-1">
            <label className="text-xs text-muted-foreground">每轮间隔 (ms)</label>
            <Input
              type="number"
              min={0}
              max={30000}
              value={delay}
              onChange={(e) => setDelay(Number(e.target.value))}
              className="h-8 text-xs"
            />
          </div>
        </div>

        <Button onClick={save} disabled={busy} className="h-8">
          保存被动会话设置
        </Button>
      </CardContent>
    </Card>
  );
}

// ---- 会话详情 ----
function ChatDetail({
  view,
  onBack,
  reload,
  busy,
  setBusy,
}: {
  view: TenantChatView;
  onBack: () => void;
  reload: () => void;
  busy: boolean;
  setBusy: (b: boolean) => void;
}) {
  const { toast } = useToast();
  const conv = view.conversation;
  const mySide = (view.my_side ?? "") as Side | "";
  const paired = !!conv.participants.A.user_id && !!conv.participants.B.user_id;
  const [persona, setPersona] = useState<Record<Side, Participant>>({
    A: conv.participants.A,
    B: conv.participants.B,
  });
  const [topicDraft, setTopicDraft] = useState(conv.topic);
  const [roundsDraft, setRoundsDraft] = useState(conv.max_rounds);
  const [delayDraft, setDelayDraft] = useState(conv.delay_ms);
  const [copied, setCopied] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Seed edit buffers when the conversation changes.
  useEffect(() => {
    setPersona({ A: conv.participants.A, B: conv.participants.B });
    setTopicDraft(conv.topic);
    setRoundsDraft(conv.max_rounds);
    setDelayDraft(conv.delay_ms);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conv.id]);

  // Poll while waiting for partner, or while running.
  useEffect(() => {
    if (conv.status !== "running" && !(!paired && conv.status === "waiting")) return;
    const interval = conv.status === "running" ? 1200 : 2000;
    const t = setInterval(() => reload(), interval);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conv.id, conv.status, paired, reload]);

  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [conv.messages.length]);

  async function control(action: "start" | "pause" | "step" | "reset") {
    setBusy(true);
    try {
      await api.tenantChatControl(conv.id, action);
      await reload();
    } catch (e: any) {
      toast({ variant: "destructive", title: "操作失败", description: e.message });
    } finally {
      setBusy(false);
    }
  }

  async function savePersona(side: Side) {
    const p = persona[side];
    try {
      await api.tenantChatSetPersona(conv.id, p.name, p.system_prompt);
      toast({ title: `${SIDE_META[side].label} 人设已保存` });
      await reload();
    } catch (e: any) {
      toast({ variant: "destructive", title: "保存失败", description: e.message });
    }
  }

  async function saveConfig() {
    try {
      await api.tenantChatSetConfig(conv.id, {
        topic: topicDraft,
        max_rounds: roundsDraft,
        delay_ms: delayDraft,
      });
      toast({ title: "设置已保存" });
      await reload();
    } catch (e: any) {
      toast({ variant: "destructive", title: "保存失败", description: e.message });
    }
  }

  function copyInvite() {
    if (!conv.invite_code) return;
    copyText(
      `${window.location.origin}${window.location.pathname}?join=${conv.id}&code=${conv.invite_code}`,
    ).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      },
      () => {},
    );
  }

  // 等待乙方加入（仅甲可见邀请信息）
  if (!paired) {
    return (
      <div className="space-y-5">
        <div className="flex items-center gap-3 flex-wrap">
          <Button variant="ghost" size="sm" onClick={onBack}>
            ← 返回
          </Button>
          <h1 className="text-xl font-bold">等待乙方加入</h1>
          <Badge variant="outline">{STATUS_LABEL[conv.status]}</Badge>
        </div>
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base">等待乙方加入</CardTitle>
            <CardDescription>
              你是{SIDE_META["A"].label}。把邀请码 / 链接发给乙方（另一个扫码登录的用户）。
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1">
                <label className="text-xs text-muted-foreground">会话 ID</label>
                <div className="rounded-md border px-2 py-1.5 text-xs font-mono break-all">
                  {conv.id}
                </div>
              </div>
              <div className="space-y-1">
                <label className="text-xs text-muted-foreground">邀请码</label>
                <div className="rounded-md border px-2 py-1.5 text-xs font-mono break-all">
                  {conv.invite_code || "（已用完）"}
                </div>
              </div>
            </div>
            <Button
              variant="outline"
              className="h-8 gap-1"
              onClick={copyInvite}
              disabled={!conv.invite_code}
            >
              {copied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
              {copied ? "已复制邀请链接" : "复制邀请链接"}
            </Button>
          </CardContent>
        </Card>
        <PersonaEditor
          side="A"
          mine
          mySide="A"
          persona={persona.A}
          onChange={(p) => setPersona((x) => ({ ...x, A: p }))}
          onSave={() => savePersona("A")}
          busy={busy}
        />
      </div>
    );
  }

  const status = conv.status;
  const partner: Side = mySide === "B" ? "A" : "B";

  return (
    <div className="space-y-5">
      <div className="flex items-center gap-3 flex-wrap">
        <Button variant="ghost" size="sm" onClick={onBack}>
          ← 返回
        </Button>
        <h1 className="text-xl font-bold">甲乙方 AI 对聊</h1>
        <Badge variant={status === "running" ? "default" : "outline"}>{STATUS_LABEL[status]}</Badge>
        {mySide ? <Badge variant="outline">我是{SIDE_META[mySide].label}</Badge> : null}
        {conv.error ? <span className="text-xs text-destructive">{conv.error}</span> : null}
      </div>

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">对聊设置</CardTitle>
          <CardDescription>
            甲、乙是两个真实的扫码 iLink 用户（租户），各自只配自己的人设；对话统一走平台系统 OpenAI
            接口。
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid gap-3 md:grid-cols-3">
            <div className="space-y-1">
              <label className="text-xs text-muted-foreground">话题</label>
              <Input
                value={topicDraft}
                onChange={(e) => setTopicDraft(e.target.value)}
                className="h-8 text-xs"
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs text-muted-foreground">轮数上限</label>
              <Input
                type="number"
                min={1}
                max={200}
                value={roundsDraft}
                onChange={(e) => setRoundsDraft(Number(e.target.value))}
                className="h-8 text-xs"
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs text-muted-foreground">每轮间隔 (ms)</label>
              <Input
                type="number"
                min={0}
                max={30000}
                value={delayDraft}
                onChange={(e) => setDelayDraft(Number(e.target.value))}
                className="h-8 text-xs"
              />
            </div>
          </div>
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="outline"
              className="h-8"
              onClick={saveConfig}
              disabled={busy}
            >
              保存设置
            </Button>
            <div className="flex-1" />
            {status === "running" ? (
              <Button
                size="sm"
                className="h-8 gap-1"
                onClick={() => control("pause")}
                disabled={busy}
              >
                <Pause className="w-3.5 h-3.5" /> 暂停
              </Button>
            ) : (
              <Button
                size="sm"
                className="h-8 gap-1"
                onClick={() => control("start")}
                disabled={busy}
              >
                <Play className="w-3.5 h-3.5" /> 开始对聊
              </Button>
            )}
            <Button
              size="sm"
              variant="outline"
              className="h-8 gap-1"
              onClick={() => control("step")}
              disabled={busy || status === "running"}
            >
              <StepForward className="w-3.5 h-3.5" /> 单步
            </Button>
            <Button
              size="sm"
              variant="outline"
              className="h-8 gap-1"
              onClick={() => control("reset")}
              disabled={busy}
            >
              <RotateCcw className="w-3.5 h-3.5" /> 重置
            </Button>
          </div>

          <div className="grid gap-3 md:grid-cols-2 pt-1">
            {(["A", "B"] as Side[]).map((side) => (
              <PersonaEditor
                key={side}
                side={side}
                mine={side === mySide}
                mySide={mySide as Side}
                persona={persona[side]}
                onChange={(p) => setPersona((x) => ({ ...x, [side]: p }))}
                onSave={() => savePersona(side)}
                busy={busy}
              />
            ))}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">
            对话记录
            {conv.round_count ? (
              <span className="text-xs text-muted-foreground font-normal ml-2">
                已进行 {conv.round_count} 轮
              </span>
            ) : null}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div
            ref={scrollRef}
            className="h-[calc(100vh-560px)] min-h-[320px] overflow-y-auto space-y-3 pr-1"
          >
            {conv.messages.length === 0 ? (
              <p className="text-center text-sm text-muted-foreground py-10">
                还没有对话。设置好话题后点击「开始对聊」或「单步」。
              </p>
            ) : (
              conv.messages.map((m) => {
                const meta = SIDE_META[m.side];
                const isMine = m.side === mySide;
                return (
                  <div key={m.seq} className={`flex ${meta.align}`}>
                    <div
                      className={`max-w-[78%] ${m.side === "A" ? "items-start" : "items-end"} flex flex-col`}
                    >
                      <span className={`text-xs mb-1 ${meta.color}`}>
                        {meta.label}
                        {isMine ? "（我）" : m.side === partner ? "（对方）" : ""} · {m.side}
                      </span>
                      <div
                        className={`rounded-2xl px-3 py-2 text-sm whitespace-pre-wrap break-words ${meta.bubble}`}
                      >
                        {m.content}
                      </div>
                      {m.thinking ? (
                        <p className="text-[11px] text-muted-foreground mt-1 max-w-full whitespace-pre-wrap">
                          思考：{m.thinking}
                        </p>
                      ) : null}
                    </div>
                  </div>
                );
              })
            )}
            {status === "running" ? (
              <div className={`flex ${SIDE_META[conv.turn].align}`}>
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <Loader2 className="w-3 h-3 animate-spin" /> {SIDE_META[conv.turn].label}正在思考…
                </div>
              </div>
            ) : null}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

function PersonaEditor({
  side,
  mine,
  mySide,
  persona,
  onChange,
  onSave,
  busy,
}: {
  side: Side;
  mine: boolean;
  mySide: Side;
  persona: Participant;
  onChange: (p: Participant) => void;
  onSave: () => void;
  busy: boolean;
}) {
  const meta = SIDE_META[side];
  return (
    <div className="space-y-2 rounded-md border p-3">
      <div className="flex items-center justify-between gap-2">
        <span className={`font-semibold ${meta.color}`}>
          {meta.label}
          {side === mySide ? "（我）" : "（对方）"}
        </span>
        {!mine ? (
          <Badge variant="outline" className="text-[10px]">
            只读
          </Badge>
        ) : null}
        {persona.user_id ? (
          <Badge variant="secondary" className="text-[10px]">
            已加入
          </Badge>
        ) : null}
      </div>
      <Input
        placeholder="显示名称"
        value={persona.name}
        disabled={!mine}
        onChange={(e) => onChange({ ...persona, name: e.target.value })}
        className="h-8 text-xs"
      />
      <textarea
        placeholder="系统提示词（人设）"
        value={persona.system_prompt}
        disabled={!mine}
        onChange={(e) => onChange({ ...persona, system_prompt: e.target.value })}
        rows={5}
        className="w-full rounded-md border border-input bg-transparent px-2 py-1 text-xs font-mono placeholder:text-muted-foreground/40 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 resize-y disabled:opacity-60"
      />
      {mine ? (
        <Button
          size="sm"
          variant="secondary"
          className="h-7 text-xs"
          onClick={onSave}
          disabled={busy}
        >
          保存{meta.label}人设
        </Button>
      ) : null}
    </div>
  );
}

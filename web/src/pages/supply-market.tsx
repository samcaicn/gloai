import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Store,
  PackagePlus,
  ClipboardList,
  ListOrdered,
  Handshake,
  MessageSquare,
  Send,
  Loader2,
  Trash2,
  XCircle,
  Sparkles,
  RefreshCw,
  ArrowLeft,
} from "lucide-react";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Textarea } from "../components/ui/textarea";
import { Label } from "../components/ui/label";
import {
  Card,
  CardHeader,
  CardTitle,
  CardContent,
  CardDescription,
} from "../components/ui/card";
import { Badge } from "../components/ui/badge";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "../components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../components/ui/select";
import { Separator } from "../components/ui/separator";
import { api } from "../lib/api";
import { useToast } from "@/hooks/use-toast";

type ItemType = "supply" | "procurement";
type State = "DRAFT" | "PENDING_CLARIFICATION" | "VERIFIED" | "REJECTED" | "CLOSED";

interface ClarificationQuestion {
  qid: string;
  text: string;
}
interface Item {
  item_id: string;
  item_type: ItemType;
  tenant_id: string;
  title: string;
  description: string;
  category: string;
  price: number;
  currency: string;
  location: string;
  contact: string;
  state: State;
  score: number;
  round_no: number;
  created_at: number;
  updated_at: number;
  chat_session_ids?: string[];
}
interface PublishResult {
  item_id: string;
  item_type: ItemType;
  state: State;
  score: number;
  round_no: number;
  next_questions?: ClarificationQuestion[];
  is_final?: boolean;
  refined?: string;
}
interface ChatMessage {
  from_role: "owner" | "inquirer";
  role: string;
  text: string;
  ts: number;
  from_user_id: string;
}
interface ChatSession {
  session_id: string;
  item_id: string;
  item_type: ItemType;
  owner_tenant_id: string;
  inquirer_tenant_id: string;
  started_at: number;
  last_message_at: number;
  messages: ChatMessage[];
  my_role?: "owner" | "inquirer";
}
interface MatchCandidate {
  item_id: string;
  item_type: ItemType;
  title: string;
  description: string;
  category: string;
  price: number;
  currency: string;
  location: string;
  owner_tenant_id: string;
  match_score: number;
  item_score: number;
  match_hit: boolean;
}

const TYPE_META: Record<ItemType, { label: string; color: string }> = {
  supply: { label: "供应", color: "bg-sky-500/15 text-sky-600" },
  procurement: { label: "采购", color: "bg-violet-500/15 text-violet-600" },
};
const STATE_META: Record<State, { label: string; color: string }> = {
  DRAFT: { label: "草稿", color: "bg-muted text-muted-foreground" },
  PENDING_CLARIFICATION: { label: "待补充", color: "bg-amber-500/15 text-amber-600" },
  VERIFIED: { label: "已上架", color: "bg-emerald-500/15 text-emerald-600" },
  REJECTED: { label: "已拒绝", color: "bg-destructive/15 text-destructive" },
  CLOSED: { label: "已下架", color: "bg-muted text-muted-foreground" },
};

function fmtPrice(p: number, c: string) {
  return `${c || "CNY"} ${p}`;
}
function fmtTime(ts: number) {
  if (!ts) return "-";
  return new Date(ts * 1000).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function SupplyMarketPage() {
  const { toast } = useToast();
  const [tab, setTab] = useState("publish");

  return (
    <div className="mx-auto max-w-6xl space-y-6 px-4 py-6">
      <div className="flex items-center gap-3">
        <div className="flex size-11 items-center justify-center rounded-xl bg-gradient-to-br from-sky-500 to-violet-500 text-white shadow">
          <Store className="size-6" />
        </div>
        <div>
          <h1 className="text-2xl font-bold">供采市场</h1>
          <p className="text-sm text-muted-foreground">
            发布供应 / 采购，自动评分上架，跨用户撮合对接
          </p>
        </div>
      </div>

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="flex-wrap">
          <TabsTrigger value="publish">
            <PackagePlus className="mr-1.5 size-4" /> 发布
          </TabsTrigger>
          <TabsTrigger value="mine">
            <ClipboardList className="mr-1.5 size-4" /> 我的
          </TabsTrigger>
          <TabsTrigger value="market">
            <ListOrdered className="mr-1.5 size-4" /> 市场
          </TabsTrigger>
          <TabsTrigger value="match">
            <Handshake className="mr-1.5 size-4" /> 撮合
          </TabsTrigger>
          <TabsTrigger value="chats">
            <MessageSquare className="mr-1.5 size-4" /> 会话
          </TabsTrigger>
        </TabsList>

        <TabsContent value="publish">
          <PublishTab onGoMarket={() => setTab("market")} />
        </TabsContent>
        <TabsContent value="mine">
          <MineTab onOpenMatch={() => setTab("match")} onOpenChats={() => setTab("chats")} />
        </TabsContent>
        <TabsContent value="market">
          <MarketTab onOpenMatch={() => setTab("match")} onOpenChats={() => setTab("chats")} />
        </TabsContent>
        <TabsContent value="match">
          <MatchTab />
        </TabsContent>
        <TabsContent value="chats">
          <ChatsTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}

/* ---------------- Publish ---------------- */
function PublishTab({ onGoMarket }: { onGoMarket: () => void }) {
  const { toast } = useToast();
  const [categories, setCategories] = useState<string[]>([]);
  const [itemType, setItemType] = useState<ItemType>("supply");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState("");
  const [price, setPrice] = useState("");
  const [currency, setCurrency] = useState("CNY");
  const [location, setLocation] = useState("");
  const [contact, setContact] = useState("");
  const [busy, setBusy] = useState(false);

  const [pending, setPending] = useState<PublishResult | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});

  useEffect(() => {
    api.supplyCategories().then(setCategories).catch(() => {});
  }, []);

  const reset = () => {
    setTitle("");
    setDescription("");
    setCategory("");
    setPrice("");
    setCurrency("CNY");
    setLocation("");
    setContact("");
  };

  const doPublish = async () => {
    setBusy(true);
    try {
      const res: PublishResult = await api.supplyPublish({
        item_type: itemType,
        title,
        description,
        category,
        price: parseFloat(price) || 0,
        currency,
        location,
        contact,
      });
      setPending(res);
      setAnswers({});
      if (res.state === "VERIFIED") {
        toast({ title: "发布成功", description: "已通过评分自动上架市场。" });
      }
    } catch (e: any) {
      toast({ variant: "destructive", title: "发布失败", description: e.message });
    } finally {
      setBusy(false);
    }
  };

  const doClarify = async () => {
    if (!pending) return;
    setBusy(true);
    try {
      const list = Object.entries(answers)
        .filter(([, v]) => v.trim() !== "")
        .map(([qid, text]) => ({ qid, text }));
      if (list.length === 0) {
        toast({ variant: "destructive", title: "请至少回答一个问题" });
        return;
      }
      const res: PublishResult = await api.supplyClarify(pending.item_id, list);
      setPending(res);
      setAnswers({});
      if (res.state === "VERIFIED") {
        toast({ title: "已上架", description: "补充内容后评分达标，已进入市场。" });
      } else if (res.state === "REJECTED") {
        toast({ variant: "destructive", title: "已拒绝", description: "补充 3 轮仍未达标。" });
      }
    } catch (e: any) {
      toast({ variant: "destructive", title: "提交失败", description: e.message });
    } finally {
      setBusy(false);
    }
  };

  if (pending) {
    const final = pending.state === "VERIFIED" || pending.state === "REJECTED";
    return (
      <div className="space-y-4">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Badge className={TYPE_META[pending.item_type].color}>{TYPE_META[pending.item_type].label}</Badge>
              <Badge className={STATE_META[pending.state].color}>{STATE_META[pending.state].label}</Badge>
              <span className="text-sm font-normal text-muted-foreground">
                评分 {pending.score} 分 · 第 {pending.round_no} 轮
              </span>
            </CardTitle>
            <CardDescription>
              编号：{pending.item_id} {final && pending.refined ? "· 已生成上架简介" : ""}
            </CardDescription>
          </CardHeader>
          {!final && pending.next_questions && pending.next_questions.length > 0 && (
            <CardContent className="space-y-4">
              <p className="text-sm font-medium">系统需要补充以下信息（最多 3 轮）：</p>
              {pending.next_questions.map((q, i) => (
                <div key={q.qid} className="space-y-1.5">
                  <Label className="text-sm">{i + 1}. {q.text}</Label>
                  <Textarea
                    rows={2}
                    value={answers[q.qid] ?? ""}
                    onChange={(e) => setAnswers((p) => ({ ...p, [q.qid]: e.target.value }))}
                  />
                </div>
              ))}
              <div className="flex gap-2">
                <Button onClick={doClarify} disabled={busy}>
                  {busy ? <Loader2 className="mr-1.5 size-4 animate-spin" /> : <Sparkles className="mr-1.5 size-4" />}
                  提交补充
                </Button>
                <Button variant="ghost" onClick={() => setPending(null)}>
                  <ArrowLeft className="mr-1.5 size-4" /> 返回编辑
                </Button>
              </div>
            </CardContent>
          )}
          {final && (
            <CardContent className="flex gap-2">
              <Button onClick={() => { setPending(null); reset(); }}>
                再发布一条
              </Button>
              <Button variant="secondary" onClick={onGoMarket}>去市场看看</Button>
            </CardContent>
          )}
        </Card>
        {final && pending.refined && (
          <Card>
            <CardHeader><CardTitle className="text-base">上架简介</CardTitle></CardHeader>
            <CardContent className="whitespace-pre-wrap text-sm">{pending.refined}</CardContent>
          </Card>
        )}
      </div>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>发布供应 / 采购</CardTitle>
        <CardDescription>系统按 100 分制自动评分，≥40 分直接上架；不足则自动追问。</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex gap-2">
          {(["supply", "procurement"] as ItemType[]).map((t) => (
            <Button
              key={t}
              type="button"
              variant={itemType === t ? "default" : "outline"}
              onClick={() => setItemType(t)}
            >
              {TYPE_META[t].label}
            </Button>
          ))}
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1.5 sm:col-span-2">
            <Label>标题 *</Label>
            <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="一句话概括，如：出租南山写字楼办公室" />
          </div>
          <div className="space-y-1.5 sm:col-span-2">
            <Label>详细描述 *</Label>
            <Textarea
              rows={4}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="详细说明规格、数量、期限、附加条件等，越详细评分越高"
            />
          </div>
          <div className="space-y-1.5">
            <Label>品类</Label>
            <Select value={category || undefined} onValueChange={setCategory}>
              <SelectTrigger className="w-full">
                <SelectValue placeholder="选择或自定义" />
              </SelectTrigger>
              <SelectContent>
                {categories.map((c) => (
                  <SelectItem key={c} value={c}>{c}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label>价格 *</Label>
              <Input type="number" min="0" value={price} onChange={(e) => setPrice(e.target.value)} placeholder="0" />
            </div>
            <div className="space-y-1.5">
              <Label>币种</Label>
              <Select value={currency} onValueChange={setCurrency}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {["CNY", "USD", "EUR", "GBP", "JPY", "HKD", "TWD"].map((c) => (
                    <SelectItem key={c} value={c}>{c}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
          <div className="space-y-1.5">
            <Label>所在地区 *</Label>
            <Input value={location} onChange={(e) => setLocation(e.target.value)} placeholder="如：深圳南山" />
          </div>
          <div className="space-y-1.5">
            <Label>联系方式 *</Label>
            <Input value={contact} onChange={(e) => setContact(e.target.value)} placeholder="微信 / 电话 / 邮箱" />
          </div>
        </div>
        <Button onClick={doPublish} disabled={busy}>
          {busy ? <Loader2 className="mr-1.5 size-4 animate-spin" /> : <PackagePlus className="mr-1.5 size-4" />}
          发布
        </Button>
      </CardContent>
    </Card>
  );
}

/* ---------------- Mine ---------------- */
function MineTab({ onOpenMatch, onOpenChats }: { onOpenMatch: () => void; onOpenChats: () => void }) {
  const { toast } = useToast();
  const [items, setItems] = useState<Item[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      setItems(await api.supplyMyItems());
    } catch (e: any) {
      toast({ variant: "destructive", title: "加载失败", description: e.message });
    } finally {
      setLoading(false);
    }
  }, [toast]);
  useEffect(() => { load(); }, [load]);

  const act = async (fn: () => Promise<any>, okMsg: string) => {
    try {
      await fn();
      toast({ title: okMsg });
      await load();
    } catch (e: any) {
      toast({ variant: "destructive", title: "操作失败", description: e.message });
    }
  };

  if (loading) {
    return <Card><CardContent className="py-10 flex justify-center"><Loader2 className="size-6 animate-spin text-muted-foreground" /></CardContent></Card>;
  }
  if (items.length === 0) {
    return <EmptyCard text="还没有发布任何供需信息，去「发布」页签发布一条吧。" />;
  }
  return (
    <div className="space-y-3">
      {items.map((it) => (
        <Card key={it.item_id}>
          <CardContent className="pt-5">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0 space-y-1">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge className={TYPE_META[it.item_type].color}>{TYPE_META[it.item_type].label}</Badge>
                  <Badge className={STATE_META[it.state].color}>{STATE_META[it.state].label}</Badge>
                  <span className="text-sm font-medium text-muted-foreground">评分 {it.score}</span>
                  {it.category && <Badge variant="outline">{it.category}</Badge>}
                </div>
                <h3 className="text-base font-semibold">{it.title}</h3>
                <p className="line-clamp-2 text-sm text-muted-foreground">{it.description}</p>
                <p className="text-sm text-muted-foreground">
                  {fmtPrice(it.price, it.currency)} · {it.location || "未填地区"} · 更新于 {fmtTime(it.updated_at)}
                </p>
              </div>
              <div className="flex shrink-0 flex-col items-end gap-2">
                {it.state === "VERIFIED" && (
                  <Button size="sm" variant="outline" onClick={onOpenMatch}>
                    <Handshake className="mr-1 size-3.5" /> 撮合
                  </Button>
                )}
                {it.state === "VERIFIED" && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => act(() => api.supplyChatStart(it.item_id), "已创建/复用会话")}
                  >
                    <MessageSquare className="mr-1 size-3.5" /> 咨询会话
                  </Button>
                )}
                {it.state === "VERIFIED" && (
                  <Button size="sm" variant="ghost" onClick={() => act(() => api.supplyClose(it.item_id), "已下架")}>
                    <XCircle className="mr-1 size-3.5" /> 下架
                  </Button>
                )}
                <Button size="sm" variant="ghost" onClick={() => act(() => api.supplyDelete(it.item_id), "已删除")}>
                  <Trash2 className="mr-1 size-3.5" /> 删除
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

/* ---------------- Market ---------------- */
function MarketTab({ onOpenMatch, onOpenChats }: { onOpenMatch: () => void; onOpenChats: () => void }) {
  const { toast } = useToast();
  const [items, setItems] = useState<Item[]>([]);
  const [loading, setLoading] = useState(true);
  const [itemType, setItemType] = useState<string>("");
  const [category, setCategory] = useState<string>("");
  const [location, setLocation] = useState("");
  const [priceMax, setPriceMax] = useState("");
  const [categories, setCategories] = useState<string[]>([]);

  useEffect(() => {
    api.supplyCategories().then(setCategories).catch(() => {});
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.supplyMarketplace({
        item_type: itemType || undefined,
        category: category || undefined,
        location: location || undefined,
        price_max: priceMax ? parseFloat(priceMax) : undefined,
        limit: 100,
      });
      setItems(data);
    } catch (e: any) {
      toast({ variant: "destructive", title: "加载失败", description: e.message });
    } finally {
      setLoading(false);
    }
  }, [itemType, category, location, priceMax, toast]);

  useEffect(() => { load(); }, [load]);

  const startChat = async (item: Item) => {
    try {
      await api.supplyChatStart(item.item_id);
      toast({ title: "已发起会话" });
      onOpenChats();
    } catch (e: any) {
      toast({ variant: "destructive", title: "发起失败", description: e.message });
    }
  };

  return (
    <div className="space-y-4">
      <Card>
        <CardContent className="grid gap-3 pt-4 sm:grid-cols-2 lg:grid-cols-5">
          <div>
            <Label className="text-xs text-muted-foreground">类型</Label>
            <Select value={itemType || undefined} onValueChange={(v) => setItemType(v === "all" ? "" : v)}>
              <SelectTrigger className="w-full"><SelectValue placeholder="全部" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部</SelectItem>
                <SelectItem value="supply">供应</SelectItem>
                <SelectItem value="procurement">采购</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div>
            <Label className="text-xs text-muted-foreground">品类</Label>
            <Select value={category || undefined} onValueChange={(v) => setCategory(v === "all" ? "" : v)}>
              <SelectTrigger className="w-full"><SelectValue placeholder="全部" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部</SelectItem>
                {categories.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <div>
            <Label className="text-xs text-muted-foreground">地区</Label>
            <Input value={location} onChange={(e) => setLocation(e.target.value)} placeholder="如：深圳" />
          </div>
          <div>
            <Label className="text-xs text-muted-foreground">价格上限</Label>
            <Input type="number" min="0" value={priceMax} onChange={(e) => setPriceMax(e.target.value)} placeholder="不限" />
          </div>
          <div className="flex items-end">
            <Button variant="secondary" onClick={load}>
              <RefreshCw className="mr-1.5 size-4" /> 查询
            </Button>
          </div>
        </CardContent>
      </Card>

      {loading ? (
        <Card><CardContent className="flex justify-center py-10"><Loader2 className="size-6 animate-spin text-muted-foreground" /></CardContent></Card>
      ) : items.length === 0 ? (
        <EmptyCard text="当前筛选条件下没有已上架的供需信息。" />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {items.map((it) => (
            <Card key={it.item_id}>
              <CardContent className="pt-5">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge className={TYPE_META[it.item_type].color}>{TYPE_META[it.item_type].label}</Badge>
                  {it.category && <Badge variant="outline">{it.category}</Badge>}
                  <span className="ml-auto text-xs text-muted-foreground">质量分 {it.score}</span>
                </div>
                <h3 className="mt-2 text-base font-semibold">{it.title}</h3>
                <p className="line-clamp-2 mt-1 text-sm text-muted-foreground">{it.description}</p>
                <p className="mt-2 text-sm font-medium">{fmtPrice(it.price, it.currency)}</p>
                <p className="text-xs text-muted-foreground">{it.location} · {fmtTime(it.updated_at)}</p>
                <div className="mt-3 flex gap-2">
                  <Button size="sm" variant="outline" onClick={() => startChat(it)}>
                    <MessageSquare className="mr-1 size-3.5" /> 咨询
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => { toast({ title: "已选择该信息" }); onOpenMatch(); }}>
                    <Handshake className="mr-1 size-3.5" /> 匹配对方
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}

/* ---------------- Match ---------------- */
function MatchTab() {
  const { toast } = useToast();
  const [myItems, setMyItems] = useState<Item[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [matches, setMatches] = useState<MatchCandidate[]>([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.supplyMyItems({ state: "VERIFIED" }).then(setMyItems).catch(() => {});
  }, []);

  const run = async (itemId: string) => {
    if (!itemId) return;
    setBusy(true);
    try {
      setMatches(await api.supplyMatch(itemId));
    } catch (e: any) {
      toast({ variant: "destructive", title: "撮合失败", description: e.message });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <Card>
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="min-w-56 flex-1">
            <Label>选择我的供应 / 采购（需已上架）</Label>
            <Select value={selectedId} onValueChange={setSelectedId}>
              <SelectTrigger className="w-full"><SelectValue placeholder="请选择" /></SelectTrigger>
              <SelectContent>
                {myItems.map((it) => (
                  <SelectItem key={it.item_id} value={it.item_id}>
                    [{TYPE_META[it.item_type].label}] {it.title}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <Button onClick={() => run(selectedId)} disabled={!selectedId || busy}>
            {busy ? <Loader2 className="mr-1.5 size-4 animate-spin" /> : <Handshake className="mr-1.5 size-4" />}
            撮合
          </Button>
        </CardContent>
      </Card>

      {matches.length === 0 ? (
        <EmptyCard text="选择一条供需信息并点击「撮合」，系统会推荐匹配的对方。" />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {matches.map((m) => (
            <Card key={m.item_id}>
              <CardContent className="pt-5">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge className={TYPE_META[m.item_type].color}>{TYPE_META[m.item_type].label}</Badge>
                  {m.match_hit ? (
                    <Badge className="bg-emerald-500/15 text-emerald-600">命中 {m.match_score} 分</Badge>
                  ) : (
                    <Badge variant="outline">匹配度 {m.match_score}</Badge>
                  )}
                  <span className="ml-auto text-xs text-muted-foreground">质量分 {m.item_score}</span>
                </div>
                <h3 className="mt-2 text-base font-semibold">{m.title}</h3>
                <p className="line-clamp-2 mt-1 text-sm text-muted-foreground">{m.description}</p>
                <p className="mt-2 text-sm font-medium">{fmtPrice(m.price, m.currency)}</p>
                <p className="text-xs text-muted-foreground">{m.category} · {m.location}</p>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}

/* ---------------- Chats ---------------- */
function ChatsTab() {
  const { toast } = useToast();
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [selected, setSelected] = useState<ChatSession | null>(null);
  const [text, setText] = useState("");
  const [loading, setLoading] = useState(true);
  const endRef = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    try {
      const list = await api.supplyChatsMine();
      setSessions(list);
      if (list.length > 0 && !selected) setSelected(list[0]);
    } catch (e: any) {
      toast({ variant: "destructive", title: "加载失败", description: e.message });
    } finally {
      setLoading(false);
    }
  }, [toast, selected]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [selected]);

  const open = async (s: ChatSession) => {
    try {
      setSelected(await api.supplyChatGet(s.session_id));
    } catch (e: any) {
      toast({ variant: "destructive", title: "打开失败", description: e.message });
    }
  };

  const send = async () => {
    if (!selected || !text.trim()) return;
    try {
      const updated = await api.supplyChatSend(selected.session_id, text);
      setText("");
      setSelected(updated);
    } catch (e: any) {
      toast({ variant: "destructive", title: "发送失败", description: e.message });
    }
  };

  if (loading) {
    return <Card><CardContent className="flex justify-center py-10"><Loader2 className="size-6 animate-spin text-muted-foreground" /></CardContent></Card>;
  }
  if (sessions.length === 0) {
    return <EmptyCard text="还没有会话。在「市场」或「我的」里点击「咨询」即可发起。" />;
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[320px_1fr]">
      <Card>
        <CardContent className="space-y-1 p-2">
          {sessions.map((s) => (
            <button
              key={s.session_id}
              onClick={() => open(s)}
              className={`w-full rounded-lg px-3 py-2 text-left text-sm transition-colors ${
                selected?.session_id === s.session_id ? "bg-primary/10" : "hover:bg-muted"
              }`}
            >
              <div className="flex items-center justify-between gap-2">
                <Badge className={TYPE_META[s.item_type].color}>{TYPE_META[s.item_type].label}</Badge>
                <span className="text-xs text-muted-foreground">{s.messages.length} 条</span>
              </div>
              <div className="mt-1 truncate font-medium">{s.item_id}</div>
              <div className="truncate text-xs text-muted-foreground">
                {s.my_role === "owner" ? `咨询方：${s.inquirer_tenant_id.slice(0, 8)}` : `发布方：${s.owner_tenant_id.slice(0, 8)}`} · {fmtTime(s.last_message_at)}
              </div>
            </button>
          ))}
        </CardContent>
      </Card>

      {selected && (
        <Card className="flex flex-col">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium">
              会话 {selected.session_id} · {selected.my_role === "owner" ? "我是发布方" : "我是咨询方"}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col">
            <div className="min-h-64 space-y-2 overflow-y-auto rounded-lg bg-muted/40 p-3">
              {selected.messages.length === 0 && (
                <p className="py-8 text-center text-sm text-muted-foreground">还没有消息，打个招呼吧。</p>
              )}
              {selected.messages.map((m, i) => {
                const mine = m.from_role === selected.my_role;
                return (
                  <div key={i} className={`flex ${mine ? "justify-end" : "justify-start"}`}>
                    <div
                      className={`max-w-[75%] rounded-2xl px-3 py-2 text-sm ${
                        mine ? "bg-primary text-primary-foreground" : "bg-muted text-foreground"
                      }`}
                    >
                      <p className="whitespace-pre-wrap break-words">{m.text}</p>
                      <p className="mt-1 text-[10px] opacity-60">{fmtTime(m.ts)} · {m.from_role === "owner" ? "发布方" : "咨询方"}</p>
                    </div>
                  </div>
                );
              })}
              <div ref={endRef} />
            </div>
            <div className="mt-3 flex gap-2">
              <Input
                value={text}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }}
                placeholder="输入消息，Enter 发送"
              />
              <Button onClick={send} disabled={!text.trim()}>
                <Send className="mr-1.5 size-4" /> 发送
              </Button>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function EmptyCard({ text }: { text: string }) {
  return (
    <Card>
      <CardContent className="py-10 text-center text-sm text-muted-foreground">{text}</CardContent>
    </Card>
  );
}

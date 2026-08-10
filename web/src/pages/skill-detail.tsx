import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  ArrowLeft,
  Check,
  Download,
  FileArchive,
  History,
  Loader2,
  ShieldCheck,
  Trash2,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useToast } from "@/hooks/use-toast";

import {
  SkillIcon,
  SkillListingBadge,
  SkillVersionBadge,
  StarRating,
  formatBytes,
  timeAgo,
} from "@/components/skill-bits";
import {
  useCancelSkillVersion,
  useDeleteSkill,
  useDeleteSkillRating,
  useInstallSkill,
  useRateSkill,
  useSkill,
  useUninstallSkill,
} from "@/hooks/use-skills";
import { api, type SkillVersion } from "@/lib/api";

const ACTION_LABELS: Record<string, string> = {
  submit: "提交审核",
  approve: "审核通过",
  reject: "审核拒绝",
  cancel: "撤回提交",
  unlist: "下架",
  relist: "重新上架",
};

export function SkillDetailPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const { toast } = useToast();

  const { data, isLoading } = useSkill(id);
  const installMutation = useInstallSkill();
  const uninstallMutation = useUninstallSkill();
  const rateMutation = useRateSkill();
  const deleteRatingMutation = useDeleteSkillRating();
  const cancelMutation = useCancelSkillVersion();
  const deleteMutation = useDeleteSkill();

  const [ratingDraft, setRatingDraft] = useState(0);
  const [commentDraft, setCommentDraft] = useState("");

  if (isLoading) {
    return <div className="h-64 rounded-xl bg-muted/30 animate-pulse" />;
  }
  if (!data) {
    return (
      <div className="py-20 text-center text-muted-foreground">
        <p className="text-sm">技能不存在或无权访问</p>
        <Button variant="outline" className="mt-4" onClick={() => navigate("/dashboard/skills")}>
          返回技能市场
        </Button>
      </div>
    );
  }

  const {
    skill,
    latest_version: latest,
    my_rating: myRating,
    ratings,
    installed,
    can_manage,
  } = data;
  const versions: SkillVersion[] = data.versions ?? (latest ? [latest] : []);
  const currentRating = ratingDraft || myRating?.rating || 0;

  async function handleInstall() {
    try {
      const res = await installMutation.mutateAsync({ id: skill.id });
      toast({ title: `已安装「${skill.name}」v${res.version}` });
    } catch (e: any) {
      toast({ variant: "destructive", title: "安装失败", description: e.message });
    }
  }

  async function handleUninstall() {
    try {
      await uninstallMutation.mutateAsync({ id: skill.id });
      toast({ title: `已卸载「${skill.name}」` });
    } catch (e: any) {
      toast({ variant: "destructive", title: "卸载失败", description: e.message });
    }
  }

  async function handleRate() {
    if (currentRating < 1) return;
    try {
      await rateMutation.mutateAsync({
        id: skill.id,
        rating: currentRating,
        comment: commentDraft || myRating?.comment,
      });
      setCommentDraft("");
      setRatingDraft(0);
      toast({ title: "评分已提交" });
    } catch (e: any) {
      toast({ variant: "destructive", title: "评分失败", description: e.message });
    }
  }

  async function handleCancelVersion(v: SkillVersion) {
    try {
      await cancelMutation.mutateAsync({ skillId: skill.id, versionId: v.id });
      toast({ title: `已撤回 v${v.version}` });
    } catch (e: any) {
      toast({ variant: "destructive", title: "撤回失败", description: e.message });
    }
  }

  async function handleDeleteSkill() {
    if (!window.confirm(`确认删除技能「${skill.name}」及其全部版本？此操作不可撤销。`)) return;
    try {
      await deleteMutation.mutateAsync(skill.id);
      toast({ title: "技能已删除" });
      navigate("/dashboard/skills");
    } catch (e: any) {
      toast({ variant: "destructive", title: "删除失败", description: e.message });
    }
  }

  return (
    <div className="space-y-5">
      <Button
        variant="ghost"
        size="sm"
        className="gap-1.5 -ml-2"
        onClick={() => navigate("/dashboard/skills")}
      >
        <ArrowLeft className="h-4 w-4" /> 技能市场
      </Button>

      {/* Header */}
      <div className="flex flex-wrap items-start gap-4">
        <SkillIcon icon={skill.icon} size="h-14 w-14" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold tracking-tight">{skill.name}</h1>
            {latest && (
              <Badge variant="outline" className="font-mono text-[11px]">
                v{latest.version}
              </Badge>
            )}
            <SkillListingBadge listing={skill.listing} />
          </div>
          <p className="text-sm text-muted-foreground font-mono mt-0.5">{skill.slug}</p>
          <p className="text-sm mt-2 max-w-3xl">{skill.description}</p>
          <div className="flex flex-wrap items-center gap-4 mt-3">
            <StarRating value={skill.rating_avg} count={skill.rating_count} />
            <span className="flex items-center gap-1 text-xs text-muted-foreground">
              <Download className="h-3.5 w-3.5" /> {skill.install_count} 次安装
            </span>
            {skill.owner_name && (
              <span className="text-xs text-muted-foreground">开发者 {skill.owner_name}</span>
            )}
            {skill.license && (
              <Badge variant="outline" className="text-[10px]">
                {skill.license}
              </Badge>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2">
          {latest && (
            <Button variant="outline" className="gap-1.5" asChild>
              <a href={api.skillDownloadURL(skill.id, latest.id)}>
                <FileArchive className="h-4 w-4" /> 下载技能包
              </a>
            </Button>
          )}
          {installed ? (
            <Button
              variant="outline"
              onClick={handleUninstall}
              disabled={uninstallMutation.isPending}
            >
              已安装 · 卸载
            </Button>
          ) : (
            <Button
              onClick={handleInstall}
              disabled={installMutation.isPending || !latest}
              className="gap-1.5"
            >
              {installMutation.isPending ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Download className="h-4 w-4" />
              )}
              安装
            </Button>
          )}
        </div>
      </div>

      {skill.listing === "rejected" && skill.reject_reason && (
        <div className="rounded-lg bg-destructive/5 border border-destructive/20 p-3">
          <p className="text-xs font-semibold text-destructive mb-1">审核未通过</p>
          <p className="text-sm">{skill.reject_reason}</p>
        </div>
      )}

      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">说明</TabsTrigger>
          <TabsTrigger value="versions">版本（{versions.length}）</TabsTrigger>
          <TabsTrigger value="ratings">评价（{skill.rating_count}）</TabsTrigger>
          {can_manage && <TabsTrigger value="manage">管理</TabsTrigger>}
        </TabsList>

        {/* --- Overview --- */}
        <TabsContent value="overview" className="space-y-4 pt-4">
          {latest?.manifest && Object.keys(latest.manifest).length > 0 && (
            <div className="rounded-xl border border-border/50 bg-card p-4 space-y-3">
              <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
                技能声明
              </p>
              <div className="grid gap-3 sm:grid-cols-2 text-sm">
                <Field label="入口文件">
                  <code className="text-xs">{latest.entry}</code>
                </Field>
                <Field label="包大小">{formatBytes(latest.bundle_size)}</Field>
                {Array.isArray(latest.manifest.allowed_tools) &&
                  latest.manifest.allowed_tools.length > 0 && (
                    <Field label="允许的工具">
                      <div className="flex flex-wrap gap-1">
                        {latest.manifest.allowed_tools.map((t: string) => (
                          <Badge key={t} variant="secondary" className="text-[10px] font-mono">
                            {t}
                          </Badge>
                        ))}
                      </div>
                    </Field>
                  )}
                {latest.bundle_sha256 && (
                  <Field label="校验和">
                    <code className="text-[10px] break-all">
                      {latest.bundle_sha256.slice(0, 32)}…
                    </code>
                  </Field>
                )}
              </div>
            </div>
          )}

          {latest?.files && latest.files.length > 0 && (
            <div className="rounded-xl border border-border/50 bg-card p-4">
              <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide mb-2">
                包内文件（{latest.files.length}）
              </p>
              <ul className="text-xs font-mono space-y-1 max-h-52 overflow-y-auto">
                {latest.files.map((f) => (
                  <li key={f.path} className="flex justify-between gap-4">
                    <span className="truncate">{f.path}</span>
                    <span className="text-muted-foreground shrink-0">{formatBytes(f.size)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {latest?.readme && (
            <div className="rounded-xl border border-border/50 bg-card p-4">
              <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide mb-2">
                SKILL.md
              </p>
              <pre className="text-xs whitespace-pre-wrap font-mono bg-muted/40 rounded-lg p-3 max-h-96 overflow-y-auto">
                {latest.readme}
              </pre>
            </div>
          )}
        </TabsContent>

        {/* --- Versions --- */}
        <TabsContent value="versions" className="pt-4">
          <div className="rounded-xl border border-border/50 divide-y">
            {versions.length === 0 && (
              <p className="p-6 text-sm text-muted-foreground text-center">暂无版本</p>
            )}
            {versions.map((v) => (
              <div key={v.id} className="flex flex-wrap items-center gap-3 p-4">
                <Badge variant="outline" className="font-mono text-[11px]">
                  v{v.version}
                </Badge>
                <SkillVersionBadge status={v.status} />
                {skill.latest_version_id === v.id && (
                  <Badge variant="secondary" className="text-[10px]">
                    当前线上版本
                  </Badge>
                )}
                <span className="text-xs text-muted-foreground">{timeAgo(v.created_at)}</span>
                {v.submitter_name && (
                  <span className="text-xs text-muted-foreground">由 {v.submitter_name} 提交</span>
                )}
                <span className="text-xs text-muted-foreground tabular-nums">
                  {formatBytes(v.bundle_size)} · {v.download_count} 次下载
                </span>
                <div className="ml-auto flex items-center gap-2">
                  {(v.status === "approved" || can_manage) && (
                    <Button variant="ghost" size="sm" asChild>
                      <a href={api.skillDownloadURL(skill.id, v.id)}>下载</a>
                    </Button>
                  )}
                  {can_manage && v.status === "pending" && (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => handleCancelVersion(v)}
                      disabled={cancelMutation.isPending}
                    >
                      撤回
                    </Button>
                  )}
                </div>
                {v.changelog && (
                  <p className="w-full text-xs text-muted-foreground">{v.changelog}</p>
                )}
                {v.status === "rejected" && v.reject_reason && (
                  <p className="w-full text-xs text-destructive">拒绝原因：{v.reject_reason}</p>
                )}
              </div>
            ))}
          </div>
        </TabsContent>

        {/* --- Ratings --- */}
        <TabsContent value="ratings" className="space-y-4 pt-4">
          <div className="rounded-xl border border-border/50 bg-card p-4 space-y-3">
            <Label>我的评分</Label>
            <StarRating value={currentRating} onChange={setRatingDraft} size="h-6 w-6" />
            <Textarea
              rows={3}
              placeholder="说说这个技能好不好用…"
              value={commentDraft || myRating?.comment || ""}
              onChange={(e) => setCommentDraft(e.target.value)}
            />
            <div className="flex items-center gap-2">
              <Button
                size="sm"
                onClick={handleRate}
                disabled={currentRating < 1 || rateMutation.isPending}
                className="gap-1.5"
              >
                {rateMutation.isPending ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Check className="h-3.5 w-3.5" />
                )}
                {myRating ? "更新评分" : "提交评分"}
              </Button>
              {myRating && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => deleteRatingMutation.mutate(skill.id)}
                  disabled={deleteRatingMutation.isPending}
                >
                  删除我的评分
                </Button>
              )}
            </div>
          </div>

          <div className="space-y-3">
            {ratings.length === 0 && (
              <p className="text-sm text-muted-foreground text-center py-8">还没有评价</p>
            )}
            {ratings.map((r) => (
              <div key={r.id} className="rounded-xl border border-border/50 bg-card p-4">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">{r.user_name || "匿名用户"}</span>
                  <StarRating value={r.rating} />
                  {r.version && (
                    <Badge variant="outline" className="text-[10px] font-mono">
                      v{r.version}
                    </Badge>
                  )}
                  <span className="ml-auto text-xs text-muted-foreground">
                    {timeAgo(r.updated_at)}
                  </span>
                </div>
                {r.comment && <p className="text-sm mt-2">{r.comment}</p>}
              </div>
            ))}
          </div>
        </TabsContent>

        {/* --- Manage (owner / admin) --- */}
        {can_manage && (
          <TabsContent value="manage" className="space-y-4 pt-4">
            {data.review_logs && data.review_logs.length > 0 && (
              <div className="rounded-xl border border-border/50 bg-card p-4">
                <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide mb-3 flex items-center gap-1.5">
                  <History className="h-3.5 w-3.5" /> 审核记录
                </p>
                <div className="space-y-2">
                  {data.review_logs.map((log) => (
                    <div key={log.id} className="flex flex-wrap items-start gap-2 text-xs">
                      <span className="text-muted-foreground tabular-nums whitespace-nowrap">
                        {new Date(log.created_at * 1000).toLocaleString("zh-CN", {
                          month: "2-digit",
                          day: "2-digit",
                          hour: "2-digit",
                          minute: "2-digit",
                        })}
                      </span>
                      <Badge variant="outline" className="text-[10px]">
                        {ACTION_LABELS[log.action] || log.action}
                      </Badge>
                      {log.version && (
                        <span className="font-mono text-muted-foreground">v{log.version}</span>
                      )}
                      {log.actor_name && (
                        <span className="text-muted-foreground">{log.actor_name}</span>
                      )}
                      {log.reason && <span className="text-muted-foreground">{log.reason}</span>}
                    </div>
                  ))}
                </div>
              </div>
            )}

            <div className="rounded-xl border border-border/50 bg-card p-4 space-y-3">
              <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide flex items-center gap-1.5">
                <ShieldCheck className="h-3.5 w-3.5" /> 提交新版本
              </p>
              <p className="text-sm text-muted-foreground">
                在 <code>SKILL.md</code> 中提升 <code>version</code>{" "}
                字段后，回到技能市场重新提交即可 创建新的待审核版本；旧的待审核版本会自动被替代。
              </p>
            </div>

            <Separator />

            <div className="rounded-xl border border-destructive/20 bg-destructive/5 p-4">
              <p className="text-sm font-medium text-destructive">危险操作</p>
              <p className="text-xs text-muted-foreground mt-1 mb-3">
                删除技能会一并移除其所有版本、评分与安装记录。
              </p>
              <Button
                variant="destructive"
                size="sm"
                className="gap-1.5"
                onClick={handleDeleteSkill}
                disabled={deleteMutation.isPending}
              >
                <Trash2 className="h-3.5 w-3.5" /> 删除技能
              </Button>
            </div>
          </TabsContent>
        )}
      </Tabs>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground mb-0.5">{label}</p>
      <div className="text-sm">{children}</div>
    </div>
  );
}

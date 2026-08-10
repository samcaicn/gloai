import { useState } from "react";
import { Check, FileArchive, Inbox, ShieldAlert, Terminal, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useToast } from "@/hooks/use-toast";

import {
  SkillIcon,
  SkillListingBadge,
  StarRating,
  formatBytes,
  timeAgo,
} from "@/components/skill-bits";
import {
  useAdminSkills,
  usePendingSkillVersions,
  useReviewSkillVersion,
  useSetSkillListing,
} from "@/hooks/use-skills";
import { api, type SkillVersion } from "@/lib/api";

export function AdminSkillReviewsPage() {
  const { toast } = useToast();
  const { data: pending = [], isLoading } = usePendingSkillVersions();
  const { data: allSkills = [] } = useAdminSkills();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [rejectTarget, setRejectTarget] = useState<SkillVersion | null>(null);
  const [rejectReason, setRejectReason] = useState("");

  const reviewMutation = useReviewSkillVersion();
  const listingMutation = useSetSkillListing();
  const submitting = reviewMutation.isPending || listingMutation.isPending;

  const selected = pending.find((v) => v.id === selectedId) ?? null;

  async function handleApprove(v: SkillVersion) {
    try {
      await reviewMutation.mutateAsync({ versionId: v.id, status: "approved" });
      toast({ title: `「${v.skill_name}」v${v.version} 已通过并上架` });
      setSelectedId(null);
    } catch (e: any) {
      toast({ variant: "destructive", title: "操作失败", description: e.message });
    }
  }

  async function handleRejectConfirm() {
    if (!rejectTarget || !rejectReason.trim()) return;
    try {
      await reviewMutation.mutateAsync({
        versionId: rejectTarget.id,
        status: "rejected",
        reason: rejectReason.trim(),
      });
      toast({ title: `「${rejectTarget.skill_name}」v${rejectTarget.version} 已拒绝` });
      setRejectTarget(null);
      setRejectReason("");
      setSelectedId(null);
    } catch (e: any) {
      toast({ variant: "destructive", title: "操作失败", description: e.message });
    }
  }

  async function handleToggleListing(id: string, listing: string, name: string) {
    const next = listing === "listed" ? "unlisted" : "listed";
    try {
      await listingMutation.mutateAsync({ id, listing: next as "listed" | "unlisted" });
      toast({ title: next === "listed" ? `「${name}」已上架` : `「${name}」已下架` });
    } catch (e: any) {
      toast({ variant: "destructive", title: "操作失败", description: e.message });
    }
  }

  const allowedTools: string[] = Array.isArray(selected?.manifest?.allowed_tools)
    ? selected!.manifest!.allowed_tools
    : [];

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">技能审核</h1>
        <p className="text-sm text-muted-foreground mt-0.5">
          审核用户提交的技能包版本，管理技能上下架
        </p>
      </div>

      <Tabs defaultValue="queue">
        <TabsList>
          <TabsTrigger value="queue">
            待审核
            {pending.length > 0 && (
              <span className="ml-1.5 text-[10px] min-w-[1.25rem] text-center rounded-full px-1 py-px font-semibold bg-orange-500 text-white">
                {pending.length}
              </span>
            )}
          </TabsTrigger>
          <TabsTrigger value="all">全部技能（{allSkills.length}）</TabsTrigger>
        </TabsList>

        {/* --- Review queue --- */}
        <TabsContent value="queue" className="pt-4">
          <div className="flex flex-col md:flex-row gap-4">
            <div className="md:w-72 shrink-0 space-y-0.5 overflow-y-auto max-h-[50vh] md:max-h-[calc(100vh-16rem)]">
              {isLoading ? (
                [1, 2, 3].map((i) => (
                  <div key={i} className="h-16 rounded-lg bg-muted/40 animate-pulse mb-1" />
                ))
              ) : pending.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
                  <Inbox className="h-8 w-8 mb-2 opacity-30" />
                  <p className="text-sm">没有待审核的技能版本</p>
                </div>
              ) : (
                pending.map((v) => (
                  <button
                    key={v.id}
                    onClick={() => setSelectedId(v.id)}
                    aria-current={selectedId === v.id ? "true" : undefined}
                    className={`w-full flex items-center gap-3 p-3 rounded-lg text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                      selectedId === v.id
                        ? "bg-primary/10 border border-primary/20"
                        : "hover:bg-muted/50 border border-transparent"
                    }`}
                  >
                    <SkillIcon icon={v.skill_icon} size="h-9 w-9" />
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium truncate">{v.skill_name}</p>
                      <p className="text-xs text-muted-foreground truncate">
                        v{v.version} · {v.submitter_name || "—"} · {timeAgo(v.created_at)}
                      </p>
                    </div>
                  </button>
                ))
              )}
            </div>

            <div className="flex-1 min-w-0">
              {selected ? (
                <div className="rounded-xl border border-border/50 bg-card flex flex-col md:max-h-[calc(100vh-16rem)]">
                  <div className="flex-1 overflow-y-auto p-5 space-y-4">
                    <div className="flex items-start gap-3">
                      <SkillIcon icon={selected.skill_icon} />
                      <div className="min-w-0 flex-1">
                        <h2 className="text-base font-bold leading-tight">{selected.skill_name}</h2>
                        <div className="flex items-center gap-2 mt-0.5">
                          <p className="text-xs text-muted-foreground font-mono">
                            {selected.skill_slug}
                          </p>
                          <Badge variant="outline" className="text-[10px] font-mono">
                            v{selected.version}
                          </Badge>
                        </div>
                      </div>
                      <Button variant="outline" size="sm" className="gap-1.5" asChild>
                        <a href={api.skillDownloadURL(selected.skill_id, selected.id)}>
                          <FileArchive className="h-3.5 w-3.5" /> 下载审阅
                        </a>
                      </Button>
                    </div>

                    <div className="space-y-3">
                      <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
                        审核要点
                      </p>
                      <ReviewField
                        icon={<Terminal className="h-3.5 w-3.5" />}
                        label="声明的工具权限"
                      >
                        {allowedTools.length > 0 ? (
                          <div className="flex flex-wrap gap-1">
                            {allowedTools.map((t) => (
                              <Badge key={t} variant="secondary" className="text-[10px] font-mono">
                                {t}
                              </Badge>
                            ))}
                          </div>
                        ) : (
                          <span className="text-xs text-muted-foreground/50">未声明</span>
                        )}
                      </ReviewField>
                      <ReviewField icon={<FileArchive className="h-3.5 w-3.5" />} label="包内容">
                        <span className="text-xs">
                          {selected.files?.length ?? 0} 个文件 · {formatBytes(selected.bundle_size)}
                        </span>
                      </ReviewField>
                      {selected.source_url && (
                        <ReviewField icon={<ShieldAlert className="h-3.5 w-3.5" />} label="来源">
                          <a
                            href={selected.source_url}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-primary hover:underline text-xs break-all"
                          >
                            {selected.source_url}
                          </a>
                          {selected.commit_hash && (
                            <span className="block text-[10px] text-muted-foreground font-mono mt-0.5">
                              commit {selected.commit_hash.slice(0, 10)}
                            </span>
                          )}
                        </ReviewField>
                      )}
                      {selected.bundle_sha256 && (
                        <ReviewField icon={<ShieldAlert className="h-3.5 w-3.5" />} label="SHA-256">
                          <code className="text-[10px] break-all">{selected.bundle_sha256}</code>
                        </ReviewField>
                      )}
                    </div>

                    {selected.files && selected.files.length > 0 && (
                      <>
                        <Separator />
                        <div>
                          <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide mb-2">
                            文件清单
                          </p>
                          <ul className="text-xs font-mono space-y-1 max-h-44 overflow-y-auto">
                            {selected.files.map((f) => (
                              <li key={f.path} className="flex justify-between gap-4">
                                <span className="truncate">{f.path}</span>
                                <span className="text-muted-foreground shrink-0">
                                  {formatBytes(f.size)}
                                </span>
                              </li>
                            ))}
                          </ul>
                        </div>
                      </>
                    )}

                    {selected.changelog && (
                      <>
                        <Separator />
                        <div>
                          <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide mb-1">
                            版本说明
                          </p>
                          <p className="text-sm">{selected.changelog}</p>
                        </div>
                      </>
                    )}

                    {selected.readme && (
                      <>
                        <Separator />
                        <div>
                          <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide mb-2">
                            SKILL.md
                          </p>
                          <pre className="text-xs whitespace-pre-wrap font-mono bg-muted/40 rounded-lg p-3 max-h-72 overflow-y-auto">
                            {selected.readme}
                          </pre>
                        </div>
                      </>
                    )}
                  </div>

                  <div className="border-t px-5 py-3 shrink-0 bg-card rounded-b-xl flex gap-3">
                    <Button
                      className="flex-1 gap-1.5 bg-emerald-600 hover:bg-emerald-700"
                      onClick={() => handleApprove(selected)}
                      disabled={submitting}
                    >
                      <Check className="h-4 w-4" /> 通过并上架
                    </Button>
                    <Button
                      variant="outline"
                      className="flex-1 gap-1.5 text-destructive border-destructive/30 hover:bg-destructive/5"
                      onClick={() => {
                        setRejectTarget(selected);
                        setRejectReason("");
                      }}
                      disabled={submitting}
                    >
                      <X className="h-4 w-4" /> 拒绝
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
                  <Inbox className="h-10 w-10 mb-3 opacity-20" />
                  <p className="text-sm">选择一个版本查看详情</p>
                </div>
              )}
            </div>
          </div>
        </TabsContent>

        {/* --- All skills --- */}
        <TabsContent value="all" className="pt-4 space-y-1">
          {allSkills.length === 0 ? (
            <p className="text-sm text-muted-foreground text-center py-12">暂无技能</p>
          ) : (
            allSkills.map((s) => (
              <div
                key={s.id}
                className="flex flex-wrap items-center gap-3 p-3 rounded-lg border border-border/50"
              >
                <SkillIcon icon={s.icon} size="h-9 w-9" />
                <div className="min-w-0">
                  <p className="text-sm font-medium truncate">{s.name}</p>
                  <p className="text-xs text-muted-foreground font-mono truncate">{s.slug}</p>
                </div>
                <SkillListingBadge listing={s.listing} />
                {s.latest_version && (
                  <Badge variant="outline" className="font-mono text-[10px]">
                    v{s.latest_version}
                  </Badge>
                )}
                <StarRating value={s.rating_avg} count={s.rating_count} />
                <span className="text-xs text-muted-foreground tabular-nums">
                  {s.install_count} 次安装
                </span>
                <span className="text-xs text-muted-foreground">
                  {s.owner_name} · {timeAgo(s.updated_at)}
                </span>
                <div className="ml-auto">
                  {(s.listing === "listed" || s.latest_version_id) && (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => handleToggleListing(s.id, s.listing, s.name)}
                      disabled={submitting}
                    >
                      {s.listing === "listed" ? "下架" : "上架"}
                    </Button>
                  )}
                </div>
              </div>
            ))
          )}
        </TabsContent>
      </Tabs>

      <Dialog
        open={!!rejectTarget}
        onOpenChange={(o) => {
          if (!o) {
            setRejectTarget(null);
            setRejectReason("");
          }
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>
              拒绝「{rejectTarget?.skill_name}」v{rejectTarget?.version}
            </DialogTitle>
            <DialogDescription>填写拒绝原因，提交者将在技能详情页看到。</DialogDescription>
          </DialogHeader>
          <div className="space-y-1.5 py-2">
            <Label htmlFor="skill-reject-reason">拒绝原因</Label>
            <Textarea
              id="skill-reject-reason"
              rows={4}
              autoFocus
              placeholder="例如：SKILL.md 声明的工具权限与脚本实际行为不符…"
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
            />
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => {
                setRejectTarget(null);
                setRejectReason("");
              }}
            >
              取消
            </Button>
            <Button
              variant="destructive"
              onClick={handleRejectConfirm}
              disabled={!rejectReason.trim() || submitting}
            >
              确认拒绝
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ReviewField({
  icon,
  label,
  children,
}: {
  icon: React.ReactNode;
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex items-start gap-2 text-sm">
      <span className="mt-0.5 text-muted-foreground shrink-0">{icon}</span>
      <div className="min-w-0 flex-1">
        <p className="text-xs text-muted-foreground mb-1">{label}</p>
        {children}
      </div>
    </div>
  );
}

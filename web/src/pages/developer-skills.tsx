import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Download, Sparkles, UploadCloud } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

import { SkillIcon, SkillListingBadge, StarRating, timeAgo } from "@/components/skill-bits";
import { useMySkillInstalls, useSkills } from "@/hooks/use-skills";
import { SubmitSkillDialog } from "./skills";

export function DeveloperSkillsPage() {
  const navigate = useNavigate();
  const [submitOpen, setSubmitOpen] = useState(false);

  const { data: mine = [], isLoading } = useSkills({ mine: true });
  const { data: installs = [] } = useMySkillInstalls();

  const pendingCount = mine.filter((s) => s.listing === "pending").length;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">我的技能</h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            管理已提交的技能包、查看审核状态与线上版本
            {pendingCount > 0 && ` · ${pendingCount} 个审核中`}
          </p>
        </div>
        <Button className="gap-1.5" onClick={() => setSubmitOpen(true)}>
          <UploadCloud className="h-4 w-4" /> 提交技能
        </Button>
      </div>

      <Tabs defaultValue="submitted">
        <TabsList>
          <TabsTrigger value="submitted">我提交的（{mine.length}）</TabsTrigger>
          <TabsTrigger value="installed">我安装的（{installs.length}）</TabsTrigger>
        </TabsList>

        <TabsContent value="submitted" className="pt-4">
          {isLoading ? (
            <div className="h-40 rounded-xl bg-muted/30 animate-pulse" />
          ) : mine.length === 0 ? (
            <EmptyState onSubmit={() => setSubmitOpen(true)} />
          ) : (
            <div className="rounded-xl border border-border/50 overflow-hidden">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>技能</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead>线上版本</TableHead>
                    <TableHead>评分</TableHead>
                    <TableHead className="text-right">安装</TableHead>
                    <TableHead className="text-right">更新时间</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {mine.map((s) => (
                    <TableRow
                      key={s.id}
                      className="cursor-pointer"
                      onClick={() => navigate(`/dashboard/skills/${s.id}`)}
                    >
                      <TableCell>
                        <div className="flex items-center gap-3">
                          <SkillIcon icon={s.icon} size="h-8 w-8" />
                          <div className="min-w-0">
                            <p className="font-medium truncate">{s.name}</p>
                            <p className="text-xs text-muted-foreground font-mono truncate">
                              {s.slug}
                            </p>
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-col items-start gap-1">
                          <SkillListingBadge listing={s.listing} />
                          {s.listing === "rejected" && s.reject_reason && (
                            <span className="text-[11px] text-destructive line-clamp-1 max-w-52">
                              {s.reject_reason}
                            </span>
                          )}
                        </div>
                      </TableCell>
                      <TableCell>
                        {s.latest_version ? (
                          <Badge variant="outline" className="font-mono text-[10px]">
                            v{s.latest_version}
                          </Badge>
                        ) : (
                          <span className="text-xs text-muted-foreground">—</span>
                        )}
                      </TableCell>
                      <TableCell>
                        <StarRating value={s.rating_avg} count={s.rating_count} />
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{s.install_count}</TableCell>
                      <TableCell className="text-right text-xs text-muted-foreground">
                        {timeAgo(s.updated_at)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </TabsContent>

        <TabsContent value="installed" className="pt-4">
          {installs.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
              <Download className="h-8 w-8 mb-2 opacity-20" />
              <p className="text-sm">还没有安装任何技能</p>
              <Button
                variant="outline"
                className="mt-4"
                onClick={() => navigate("/dashboard/skills")}
              >
                去技能市场看看
              </Button>
            </div>
          ) : (
            <div className="rounded-xl border border-border/50 overflow-hidden">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>技能</TableHead>
                    <TableHead>版本</TableHead>
                    <TableHead>Agent</TableHead>
                    <TableHead className="text-right">安装时间</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {installs.map((i) => (
                    <TableRow
                      key={i.id}
                      className="cursor-pointer"
                      onClick={() => navigate(`/dashboard/skills/${i.skill_id}`)}
                    >
                      <TableCell>
                        <div className="flex items-center gap-3">
                          <SkillIcon icon={i.skill_icon} size="h-8 w-8" />
                          <div className="min-w-0">
                            <p className="font-medium truncate">{i.skill_name}</p>
                            <p className="text-xs text-muted-foreground font-mono truncate">
                              {i.skill_slug}
                            </p>
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline" className="font-mono text-[10px]">
                          v{i.version || "—"}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground font-mono">
                        {i.agent_id || "全局"}
                      </TableCell>
                      <TableCell className="text-right text-xs text-muted-foreground">
                        {timeAgo(i.created_at)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </TabsContent>
      </Tabs>

      <SubmitSkillDialog open={submitOpen} onOpenChange={setSubmitOpen} />
    </div>
  );
}

function EmptyState({ onSubmit }: { onSubmit: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
      <Sparkles className="h-10 w-10 mb-3 opacity-20" />
      <p className="text-sm">你还没有提交过技能</p>
      <p className="text-xs mt-1">技能包为 zip，根目录需包含带 frontmatter 的 SKILL.md</p>
      <Button className="mt-4 gap-1.5" onClick={onSubmit}>
        <UploadCloud className="h-4 w-4" /> 提交技能
      </Button>
    </div>
  );
}

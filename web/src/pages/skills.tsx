import { useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Download, Github, Loader2, Search, Sparkles, Upload, UploadCloud } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useToast } from "@/hooks/use-toast";

import { SkillIcon, StarRating, timeAgo } from "@/components/skill-bits";
import { useImportSkill, useSkills, useSubmitSkillBundle } from "@/hooks/use-skills";
import type { Skill, SkillListParams } from "@/lib/api";

const SORTS: { key: NonNullable<SkillListParams["sort"]>; label: string }[] = [
  { key: "updated", label: "最近更新" },
  { key: "rating", label: "评分最高" },
  { key: "installs", label: "安装最多" },
  { key: "newest", label: "最新发布" },
];

export function SkillsPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("all");
  const [sort, setSort] = useState<NonNullable<SkillListParams["sort"]>>("updated");
  const [submitOpen, setSubmitOpen] = useState(false);

  const { data: skills = [], isLoading } = useSkills({ sort });

  const categories = useMemo(() => {
    const set = new Set<string>();
    for (const s of skills) if (s.category) set.add(s.category);
    return Array.from(set).sort();
  }, [skills]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return skills.filter((s) => {
      if (category !== "all" && s.category !== category) return false;
      if (!q) return true;
      return (
        s.name.toLowerCase().includes(q) ||
        s.slug.toLowerCase().includes(q) ||
        (s.description || "").toLowerCase().includes(q) ||
        (s.tags || "").toLowerCase().includes(q)
      );
    });
  }, [skills, search, category]);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">技能市场</h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            浏览、安装经过审核的 Agent Skills；也可以提交自己的技能包参与审核
          </p>
        </div>
        <Button className="gap-1.5" onClick={() => setSubmitOpen(true)}>
          <UploadCloud className="h-4 w-4" /> 提交技能
        </Button>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-[220px]">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
          <Input
            aria-label="搜索技能"
            placeholder="搜索技能名称、描述或标签…"
            className="pl-9"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        <Select value={category} onValueChange={setCategory}>
          <SelectTrigger className="w-40" aria-label="分类">
            <SelectValue placeholder="全部分类" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">全部分类</SelectItem>
            {categories.map((c) => (
              <SelectItem key={c} value={c}>
                {c}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Tabs value={sort} onValueChange={(v) => setSort(v as typeof sort)}>
          <TabsList>
            {SORTS.map((s) => (
              <TabsTrigger key={s.key} value={s.key}>
                {s.label}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
      </div>

      {isLoading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <div
              key={i}
              className="h-32 rounded-xl border border-border/50 bg-muted/30 animate-pulse"
            />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
          <Sparkles className="h-10 w-10 mb-3 opacity-20" />
          <p className="text-sm">还没有上架的技能</p>
          <Button variant="outline" className="mt-4 gap-1.5" onClick={() => setSubmitOpen(true)}>
            <UploadCloud className="h-4 w-4" /> 提交第一个技能
          </Button>
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {filtered.map((s) => (
            <SkillCard key={s.id} skill={s} onOpen={() => navigate(`/dashboard/skills/${s.id}`)} />
          ))}
        </div>
      )}

      <SubmitSkillDialog open={submitOpen} onOpenChange={setSubmitOpen} />
    </div>
  );
}

function SkillCard({ skill, onOpen }: { skill: Skill; onOpen: () => void }) {
  const tags = (skill.tags || "")
    .split(",")
    .map((t) => t.trim())
    .filter(Boolean)
    .slice(0, 3);

  return (
    <button
      onClick={onOpen}
      className="text-left rounded-xl border border-border/50 bg-card p-4 hover:border-primary/40 hover:shadow-sm transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <div className="flex items-start gap-3">
        <SkillIcon icon={skill.icon} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="font-semibold truncate">{skill.name}</p>
            {skill.latest_version && (
              <Badge variant="outline" className="text-[10px] font-mono shrink-0">
                v{skill.latest_version}
              </Badge>
            )}
            {skill.installed && (
              <Badge variant="secondary" className="text-[10px] shrink-0">
                已安装
              </Badge>
            )}
          </div>
          <p className="text-xs text-muted-foreground font-mono truncate">{skill.slug}</p>
        </div>
      </div>

      <p className="text-sm text-muted-foreground mt-3 line-clamp-2 min-h-[2.5rem]">
        {skill.description || "暂无描述"}
      </p>

      <div className="flex items-center justify-between mt-3">
        <StarRating value={skill.rating_avg} count={skill.rating_count} />
        <span className="flex items-center gap-1 text-xs text-muted-foreground tabular-nums">
          <Download className="h-3 w-3" /> {skill.install_count}
        </span>
      </div>

      {tags.length > 0 && (
        <div className="flex flex-wrap gap-1 mt-2">
          {tags.map((t) => (
            <Badge key={t} variant="outline" className="text-[10px]">
              {t}
            </Badge>
          ))}
        </div>
      )}

      <p className="text-[11px] text-muted-foreground/70 mt-2">
        {skill.owner_name ? `${skill.owner_name} · ` : ""}
        {timeAgo(skill.updated_at)}更新
      </p>
    </button>
  );
}

/**
 * Submission dialog: upload a zip bundle or import from a URL.
 * Both paths create a pending version in the review queue.
 */
export function SubmitSkillDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
}) {
  const { toast } = useToast();
  const navigate = useNavigate();
  const fileRef = useRef<HTMLInputElement>(null);

  const [mode, setMode] = useState<"upload" | "import">("upload");
  const [file, setFile] = useState<File | null>(null);
  const [sourceURL, setSourceURL] = useState("");
  const [slug, setSlug] = useState("");
  const [category, setCategory] = useState("");
  const [tags, setTags] = useState("");
  const [changelog, setChangelog] = useState("");

  const uploadMutation = useSubmitSkillBundle();
  const importMutation = useImportSkill();
  const submitting = uploadMutation.isPending || importMutation.isPending;

  function reset() {
    setFile(null);
    setSourceURL("");
    setSlug("");
    setCategory("");
    setTags("");
    setChangelog("");
    if (fileRef.current) fileRef.current.value = "";
  }

  async function handleSubmit() {
    const fields = { slug, category, tags, changelog };
    try {
      const result =
        mode === "upload"
          ? await uploadMutation.mutateAsync({ file: file as File, fields })
          : await importMutation.mutateAsync({ sourceURL: sourceURL.trim(), fields });
      toast({
        title: `「${result.slug}」v${result.version} 已提交审核`,
        description: "管理员审核通过后会自动上架到技能市场。",
      });
      onOpenChange(false);
      reset();
      navigate(`/dashboard/skills/${result.skill_id}`);
    } catch (e: any) {
      toast({ variant: "destructive", title: "提交失败", description: e.message });
    }
  }

  const canSubmit = mode === "upload" ? !!file : sourceURL.trim().length > 0;

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        onOpenChange(o);
        if (!o) reset();
      }}
    >
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>提交技能</DialogTitle>
          <DialogDescription>
            技能包需为 zip，根目录包含带 frontmatter 的 <code>SKILL.md</code>
            （必须有 name 与 description）。提交后进入审核队列。
          </DialogDescription>
        </DialogHeader>

        <Tabs value={mode} onValueChange={(v) => setMode(v as typeof mode)}>
          <TabsList className="w-full">
            <TabsTrigger value="upload" className="flex-1 gap-1.5">
              <Upload className="h-3.5 w-3.5" /> 上传技能包
            </TabsTrigger>
            <TabsTrigger value="import" className="flex-1 gap-1.5">
              <Github className="h-3.5 w-3.5" /> 从链接导入
            </TabsTrigger>
          </TabsList>
        </Tabs>

        <div className="space-y-3 py-1">
          {mode === "upload" ? (
            <div className="space-y-1.5">
              <Label htmlFor="skill-bundle">技能包（.zip，≤ 5 MB）</Label>
              <Input
                id="skill-bundle"
                ref={fileRef}
                type="file"
                accept=".zip,application/zip"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            </div>
          ) : (
            <div className="space-y-1.5">
              <Label htmlFor="skill-source">来源地址</Label>
              <Input
                id="skill-source"
                placeholder="https://github.com/owner/repo/tree/main/skills/foo"
                value={sourceURL}
                onChange={(e) => setSourceURL(e.target.value)}
              />
              <p className="text-[11px] text-muted-foreground">
                支持 GitHub 目录 / 文件、任意 .zip 链接，或直接指向 SKILL.md 的链接。
              </p>
            </div>
          )}

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label htmlFor="skill-slug">技能标识（可选）</Label>
              <Input
                id="skill-slug"
                placeholder="留空则由 name 生成"
                value={slug}
                onChange={(e) => setSlug(e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="skill-category">分类（可选）</Label>
              <Input
                id="skill-category"
                placeholder="engineering / writing…"
                value={category}
                onChange={(e) => setCategory(e.target.value)}
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="skill-tags">标签（逗号分隔，可选）</Label>
            <Input
              id="skill-tags"
              placeholder="code-review, quality"
              value={tags}
              onChange={(e) => setTags(e.target.value)}
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="skill-changelog">版本说明（可选）</Label>
            <Textarea
              id="skill-changelog"
              rows={3}
              placeholder="本次更新内容…"
              value={changelog}
              onChange={(e) => setChangelog(e.target.value)}
            />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={submitting}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={!canSubmit || submitting} className="gap-1.5">
            {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
            提交审核
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

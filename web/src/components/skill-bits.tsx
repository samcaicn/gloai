import { Sparkles, Star } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { SkillListing, SkillVersionStatus } from "@/lib/api";

/** Icon tile for a skill (emoji or a generic glyph). */
export function SkillIcon({ icon, size = "h-10 w-10" }: { icon?: string; size?: string }) {
  if (icon) {
    return (
      <div
        className={`${size} rounded-xl bg-muted flex items-center justify-center text-lg border`}
      >
        {icon}
      </div>
    );
  }
  return (
    <div className={`${size} rounded-xl bg-muted flex items-center justify-center border`}>
      <Sparkles className="h-4 w-4 text-muted-foreground/40" />
    </div>
  );
}

const LISTING_LABELS: Record<SkillListing, string> = {
  draft: "草稿",
  pending: "审核中",
  listed: "已上架",
  rejected: "已拒绝",
  unlisted: "已下架",
};

export function SkillListingBadge({ listing }: { listing?: string }) {
  switch (listing) {
    case "listed":
      return <Badge variant="default">{LISTING_LABELS.listed}</Badge>;
    case "pending":
      return (
        <Badge variant="outline" className="text-orange-500 border-orange-500">
          {LISTING_LABELS.pending}
        </Badge>
      );
    case "rejected":
      return <Badge variant="destructive">{LISTING_LABELS.rejected}</Badge>;
    case "unlisted":
      return <Badge variant="secondary">{LISTING_LABELS.unlisted}</Badge>;
    default:
      return <Badge variant="secondary">{LISTING_LABELS.draft}</Badge>;
  }
}

const VERSION_LABELS: Record<SkillVersionStatus, string> = {
  pending: "待审核",
  approved: "已通过",
  rejected: "已拒绝",
  superseded: "已被替代",
  cancelled: "已撤回",
};

export function SkillVersionBadge({ status }: { status?: string }) {
  switch (status) {
    case "approved":
      return <Badge variant="default">{VERSION_LABELS.approved}</Badge>;
    case "pending":
      return (
        <Badge variant="outline" className="text-orange-500 border-orange-500">
          {VERSION_LABELS.pending}
        </Badge>
      );
    case "rejected":
      return <Badge variant="destructive">{VERSION_LABELS.rejected}</Badge>;
    default:
      return (
        <Badge variant="secondary">
          {VERSION_LABELS[(status || "superseded") as SkillVersionStatus] ?? status}
        </Badge>
      );
  }
}

/**
 * Star rating display, optionally interactive.
 * Read-only mode renders half-filled stars via rounding to the nearest half.
 */
export function StarRating({
  value,
  count,
  onChange,
  size = "h-4 w-4",
}: {
  value: number;
  count?: number;
  onChange?: (v: number) => void;
  size?: string;
}) {
  const interactive = typeof onChange === "function";
  return (
    <div className="flex items-center gap-1">
      <div className="flex items-center">
        {[1, 2, 3, 4, 5].map((n) => {
          const filled = value >= n - 0.25;
          const star = (
            <Star
              className={`${size} ${
                filled ? "text-amber-500 fill-amber-500" : "text-muted-foreground/30"
              }`}
            />
          );
          return interactive ? (
            <button
              key={n}
              type="button"
              aria-label={`${n} 星`}
              onClick={() => onChange?.(n)}
              className="p-0.5 hover:scale-110 transition-transform"
            >
              {star}
            </button>
          ) : (
            <span key={n} className="px-px">
              {star}
            </span>
          );
        })}
      </div>
      {!interactive && (
        <span className="text-xs text-muted-foreground tabular-nums">
          {value > 0 ? value.toFixed(1) : "暂无评分"}
          {count ? `（${count}）` : ""}
        </span>
      )}
    </div>
  );
}

/** Human-readable byte size. */
export function formatBytes(n?: number) {
  if (!n) return "—";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

/** Relative time in Chinese, matching the other dashboard pages. */
export function timeAgo(ts?: number) {
  if (!ts) return "—";
  const diff = Math.floor((Date.now() - ts * 1000) / 1000);
  if (diff < 0) return "刚刚";
  if (diff < 60) return `${diff}秒前`;
  if (diff < 3600) return `${Math.floor(diff / 60)}分钟前`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}小时前`;
  return `${Math.floor(diff / 86400)}天前`;
}

import type { ReactNode } from "react";
import "./Tooltip.scss";

export function Tooltip({
  content,
  children,
}: {
  content: string;
  children: ReactNode;
}) {
  return (
    <span className="dshg-tooltip" data-tooltip={content}>
      {children}
    </span>
  );
}

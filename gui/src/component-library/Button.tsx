import type { ButtonHTMLAttributes, ReactNode } from "react";
import "./Button.scss";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: "primary" | "ghost" | "danger";
  children: ReactNode;
}

export function Button({ variant = "ghost", className = "", children, ...props }: ButtonProps) {
  return (
    <button
      type="button"
      className={`dshg-btn dshg-btn--${variant} ${className}`.trim()}
      {...props}
    >
      {children}
    </button>
  );
}

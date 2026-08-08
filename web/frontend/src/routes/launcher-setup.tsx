import { IconLanguage, IconMoon, IconSun } from "@tabler/icons-react"
import { createFileRoute } from "@tanstack/react-router"
import * as React from "react"
import { useTranslation } from "react-i18next"

import { postLauncherDashboardBind } from "@/api/launcher-auth"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { useTheme } from "@/hooks/use-theme"

function LauncherBindPage() {
  const { t, i18n } = useTranslation()
  const { theme, toggleTheme } = useTheme()
  const [joinCode, setJoinCode] = React.useState("")
  const [submitting, setSubmitting] = React.useState(false)
  const [error, setError] = React.useState("")

  const onSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setError("")
    const code = joinCode.trim()
    if (!/^\d{8}$/.test(code)) {
      setError(t("launcherBind.errorFormat"))
      return
    }
    setSubmitting(true)
    try {
      const result = await postLauncherDashboardBind(code)
      if (result.ok) {
        globalThis.location.assign("/")
        return
      }
      setError(result.error)
    } catch {
      setError(t("launcherBind.errorNetwork"))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="bg-background text-foreground flex min-h-dvh flex-col">
      <header className="border-border/50 flex h-14 shrink-0 items-center justify-end gap-2 border-b px-4">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="icon" aria-label="Language">
              <IconLanguage className="size-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem onClick={() => i18n.changeLanguage("en")}>
              English
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => i18n.changeLanguage("zh")}>
              简体中文
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <Button
          variant="outline"
          size="icon"
          type="button"
          onClick={() => toggleTheme()}
          aria-label={theme === "dark" ? "Light mode" : "Dark mode"}
        >
          {theme === "dark" ? (
            <IconSun className="size-4" />
          ) : (
            <IconMoon className="size-4" />
          )}
        </Button>
      </header>

      <div className="flex flex-1 items-center justify-center p-4">
        <Card className="w-full max-w-md" size="sm">
          <CardHeader>
            <CardTitle>{t("launcherBind.title")}</CardTitle>
            <CardDescription>{t("launcherBind.description")}</CardDescription>
          </CardHeader>
          <CardContent>
            <form className="flex flex-col gap-4" onSubmit={onSubmit}>
              <div className="flex flex-col gap-2">
                <Label htmlFor="bind-join-code">
                  {t("launcherBind.joinCodeLabel")}
                </Label>
                <Input
                  id="bind-join-code"
                  name="join_code"
                  type="text"
                  inputMode="numeric"
                  pattern="[0-9]*"
                  maxLength={8}
                  required
                  value={joinCode}
                  onChange={(e) => setJoinCode(e.target.value)}
                  placeholder={t("launcherBind.joinCodePlaceholder")}
                />
              </div>
              <Button type="submit" disabled={submitting}>
                {submitting ? t("labels.loading") : t("launcherBind.submit")}
              </Button>
              {error ? (
                <p className="text-destructive text-sm" role="alert">
                  {error}
                </p>
              ) : null}
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

export const Route = createFileRoute("/launcher-setup")({
  component: LauncherBindPage,
})

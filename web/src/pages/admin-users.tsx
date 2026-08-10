import { Users, MoreVertical, Check, X, Trash2 } from "lucide-react";
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
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useToast } from "@/hooks/use-toast";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  useAdminUsers,
  useUpdateUserStatus,
  useUpdateUserRole,
  useDeleteUser,
} from "@/hooks/use-admin";

const ROLE_OPTIONS = [
  { value: "member", label: "普通用户" },
  { value: "developer", label: "开发者" },
  { value: "admin", label: "管理员" },
];

export function AdminUsersPage() {
  const { data: users = [], isLoading: loading } = useAdminUsers();
  const updateStatusMutation = useUpdateUserStatus();
  const updateRoleMutation = useUpdateUserRole();
  const deleteUserMutation = useDeleteUser();
  const { toast } = useToast();
  const { confirm, ConfirmDialog } = useConfirm();

  async function handleUpdateStatus(id: string, status: string) {
    try {
      await updateStatusMutation.mutateAsync({ id, status });
      toast({ title: "状态已更新" });
    } catch (e: any) {
      toast({ variant: "destructive", title: "操作失败", description: e.message });
    }
  }

  async function handleUpdateRole(id: string, role: string) {
    try {
      await updateRoleMutation.mutateAsync({ id, role });
      toast({ title: "角色已更新" });
    } catch (e: any) {
      toast({ variant: "destructive", title: "操作失败", description: e.message });
    }
  }

  return (
    <div className="space-y-6">
      {ConfirmDialog}
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">用户管理</h1>
          <p className="text-sm text-muted-foreground mt-0.5">管理平台用户账号。</p>
        </div>
      </div>

      <div className="rounded-xl border border-border/50 overflow-hidden">
        <Table>
          <TableHeader className="bg-muted/30">
            <TableRow>
              <TableHead>用户名</TableHead>
              <TableHead>角色</TableHead>
              <TableHead>状态</TableHead>
              <TableHead>注册时间</TableHead>
              <TableHead className="text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading
              ? [1, 2, 3].map((i) => (
                  <TableRow key={i}>
                    <TableCell colSpan={5}>
                      <div className="h-4 w-full bg-muted animate-pulse rounded" />
                    </TableCell>
                  </TableRow>
                ))
              : users.map((u: any) => (
                  <TableRow key={u.id} className="group">
                    <TableCell className="font-bold">{u.username}</TableCell>
                    <TableCell>
                      <select
                        value={u.role}
                        disabled={u.role === "superadmin"}
                        onChange={(e) => handleUpdateRole(u.id, e.target.value)}
                        className="h-7 rounded-md border border-border/60 bg-background px-2 text-xs capitalize focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
                      >
                        {ROLE_OPTIONS.map((r) => (
                          <option key={r.value} value={r.value}>
                            {r.label}
                          </option>
                        ))}
                      </select>
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={u.status === "active" ? "default" : "outline"}
                        className="h-5"
                      >
                        {u.status}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      {new Date(u.created_at * 1000).toLocaleDateString()}
                    </TableCell>
                    <TableCell className="text-right">
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" className="h-8 w-8 rounded-full">
                            <MoreVertical className="h-4 w-4" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="rounded-xl">
                          <DropdownMenuItem
                            onClick={() =>
                              handleUpdateStatus(
                                u.id,
                                u.status === "active" ? "disabled" : "active",
                              )
                            }
                          >
                            {u.status === "active" ? (
                              <X className="h-3.5 w-3.5 mr-2" />
                            ) : (
                              <Check className="h-3.5 w-3.5 mr-2" />
                            )}
                            {u.status === "active" ? "禁用账号" : "恢复账号"}
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            className="text-destructive focus:bg-destructive/10 focus:text-destructive"
                            onSelect={async (e) => {
                              e.preventDefault();
                              const ok = await confirm({
                                title: "删除确认",
                                description: "确定要删除此用户？",
                                confirmText: "删除",
                                variant: "destructive",
                              });
                              if (ok) {
                                try {
                                  await deleteUserMutation.mutateAsync(u.id);
                                  toast({ title: "已删除用户" });
                                } catch (e: any) {
                                  toast({ variant: "destructive", title: "删除失败", description: e.message });
                                }
                              }
                            }}
                          >
                            <Trash2 className="h-3.5 w-3.5 mr-2" /> 删除用户
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}

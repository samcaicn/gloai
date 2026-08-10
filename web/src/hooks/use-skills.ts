import { useMutation, useQuery, useQueryClient, QueryClient } from "@tanstack/react-query";
import { api, type SkillListParams, type SkillSubmitFields } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";

/** Invalidate every skill query after a mutation that can change listings. */
export function invalidateSkillQueries(qc: QueryClient) {
  qc.invalidateQueries({ queryKey: ["skills"] });
}

export function useSkills(params?: SkillListParams) {
  return useQuery({
    queryKey: queryKeys.skills.all(params as Record<string, unknown>),
    queryFn: () => api.listSkills(params),
    staleTime: 30_000,
  });
}

export function useSkill(id?: string) {
  return useQuery({
    queryKey: queryKeys.skills.detail(id || ""),
    queryFn: () => api.getSkill(id as string),
    enabled: !!id,
  });
}

export function useSkillVersions(id?: string) {
  return useQuery({
    queryKey: queryKeys.skills.versions(id || ""),
    queryFn: () => api.listSkillVersions(id as string),
    enabled: !!id,
  });
}

export function useMySkillInstalls() {
  return useQuery({
    queryKey: queryKeys.skills.installs(),
    queryFn: () => api.mySkillInstalls(),
  });
}

export function useSubmitSkillBundle() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ file, fields }: { file: File; fields?: SkillSubmitFields }) =>
      api.submitSkillBundle(file, fields),
    onSuccess: () => invalidateSkillQueries(qc),
  });
}

export function useImportSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ sourceURL, fields }: { sourceURL: string; fields?: SkillSubmitFields }) =>
      api.importSkill(sourceURL, fields),
    onSuccess: () => invalidateSkillQueries(qc),
  });
}

export function useCancelSkillVersion() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ skillId, versionId }: { skillId: string; versionId: string }) =>
      api.cancelSkillVersion(skillId, versionId),
    onSuccess: () => invalidateSkillQueries(qc),
  });
}

export function useRateSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, rating, comment }: { id: string; rating: number; comment?: string }) =>
      api.rateSkill(id, rating, comment),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skills.detail(id) });
      qc.invalidateQueries({ queryKey: ["skills", {}] });
    },
  });
}

export function useDeleteSkillRating() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deleteSkillRating(id),
    onSuccess: (_data, id) => {
      qc.invalidateQueries({ queryKey: queryKeys.skills.detail(id) });
    },
  });
}

export function useInstallSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, agentId }: { id: string; agentId?: string }) =>
      api.installSkill(id, agentId),
    onSuccess: () => invalidateSkillQueries(qc),
  });
}

export function useUninstallSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, agentId }: { id: string; agentId?: string }) =>
      api.uninstallSkill(id, agentId),
    onSuccess: () => invalidateSkillQueries(qc),
  });
}

export function useDeleteSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deleteSkill(id),
    onSuccess: () => invalidateSkillQueries(qc),
  });
}

// --- Admin ---

export function useAdminSkills(listing?: string) {
  return useQuery({
    queryKey: queryKeys.skills.adminAll(listing),
    queryFn: () => api.adminListSkills(listing),
  });
}

export function usePendingSkillVersions() {
  return useQuery({
    queryKey: queryKeys.skills.adminPending(),
    queryFn: () => api.adminPendingSkillVersions(),
    refetchInterval: 60_000,
  });
}

export function useReviewSkillVersion() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      versionId,
      status,
      reason,
    }: {
      versionId: string;
      status: "approved" | "rejected";
      reason?: string;
    }) => api.reviewSkillVersion(versionId, status, reason),
    onSuccess: () => invalidateSkillQueries(qc),
  });
}

export function useSetSkillListing() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      listing,
      reason,
    }: {
      id: string;
      listing: "listed" | "unlisted";
      reason?: string;
    }) => api.setSkillListing(id, listing, reason),
    onSuccess: () => invalidateSkillQueries(qc),
  });
}

export function useAdminDeleteSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.adminDeleteSkill(id),
    onSuccess: () => invalidateSkillQueries(qc),
  });
}

import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Branch, Commit, FileChange, MergeRequest, RepoEntry, ReviewComment, RunStep, TaskRun, TestRun } from "../api/types";
import type { Tone } from "../components/ui";

export function useRuns(projectId: string) {
  return useQuery({ queryKey: ["runs", projectId], queryFn: () => api.get<TaskRun[]>(`/api/v1/projects/${projectId}/runs`), enabled: !!projectId });
}

export function useRun(taskId: string | null | undefined) {
  return useQuery({
    queryKey: ["run", taskId],
    queryFn: () => api.get<{ run: TaskRun | null; steps: RunStep[] }>(`/api/v1/tasks/${taskId}/run`),
    enabled: !!taskId,
  });
}

export function useMergeRequests(projectId: string) {
  return useQuery({ queryKey: ["merge-requests", projectId], queryFn: () => api.get<MergeRequest[]>(`/api/v1/projects/${projectId}/merge-requests`), enabled: !!projectId });
}

export interface MergeRequestView { mergeRequest: MergeRequest; comments: ReviewComment[]; files: FileChange[]; testRuns: TestRun[] }

export function useMergeRequest(id: string | null | undefined) {
  return useQuery({ queryKey: ["mr", id], queryFn: () => api.get<MergeRequestView>(`/api/v1/merge-requests/${id}`), enabled: !!id });
}

export function useTestRuns(projectId: string) {
  return useQuery({ queryKey: ["test-runs", projectId], queryFn: () => api.get<TestRun[]>(`/api/v1/projects/${projectId}/test-runs`), enabled: !!projectId });
}

export function useBranches(projectId: string) {
  return useQuery({ queryKey: ["branches", projectId], queryFn: () => api.get<Branch[]>(`/api/v1/projects/${projectId}/repo/branches`), enabled: !!projectId });
}

export function useLog(projectId: string, ref = "main") {
  return useQuery({ queryKey: ["repo", projectId, "log", ref], queryFn: () => api.get<Commit[]>(`/api/v1/projects/${projectId}/repo/log?ref=${encodeURIComponent(ref)}`), enabled: !!projectId });
}

export function useTree(projectId: string, ref: string) {
  return useQuery({ queryKey: ["repo", projectId, "tree", ref], queryFn: () => api.get<RepoEntry[]>(`/api/v1/projects/${projectId}/repo/tree?ref=${encodeURIComponent(ref)}`), enabled: !!projectId && !!ref });
}

export const RUN_TONE: Record<TaskRun["status"], Tone> = {
  RUNNING: "active", PAUSED: "muted", SUBMITTED: "review", BLOCKED: "danger", DONE: "ok", CANCELLED: "muted",
};
export const MR_TONE: Record<MergeRequest["status"], Tone> = {
  IN_REVIEW: "review", CHANGES_REQUESTED: "warn", AWAITING_APPROVAL: "accent", MERGED: "ok", CLOSED: "muted",
};
export const MR_LABEL: Record<MergeRequest["status"], string> = {
  IN_REVIEW: "In review", CHANGES_REQUESTED: "Changes requested", AWAITING_APPROVAL: "Waiting for your approval", MERGED: "Merged", CLOSED: "Closed",
};
export const GATES: { key: string; label: string }[] = [
  { key: "tests", label: "Tests run and passing" },
  { key: "review", label: "Review accepted" },
  { key: "criteria", label: "Criteria met with evidence" },
  { key: "secrets", label: "No secret in the code" },
  { key: "conflicts", label: "No conflict with main" },
];

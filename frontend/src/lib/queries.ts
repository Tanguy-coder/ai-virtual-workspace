import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Agent, Job, Task } from "../api/types";

export function useAgents(projectId: string) {
  return useQuery({
    queryKey: ["agents", projectId],
    queryFn: () => api.get<Agent[]>(`/api/v1/projects/${projectId}/agents`),
    enabled: !!projectId,
  });
}

export function useTasks(projectId: string) {
  return useQuery({
    queryKey: ["tasks", projectId],
    queryFn: () => api.get<Task[]>(`/api/v1/projects/${projectId}/tasks`),
    enabled: !!projectId,
  });
}

export function useOpenJobs(projectId: string) {
  return useQuery({
    queryKey: ["jobs", projectId],
    queryFn: () => api.get<Job[]>(`/api/v1/projects/${projectId}/jobs?open=true`),
    enabled: !!projectId,
  });
}

export function byId<T extends { id: string }>(items: T[] | undefined): Record<string, T> {
  return Object.fromEntries((items ?? []).map((i) => [i.id, i]));
}

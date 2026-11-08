import { create } from "zustand";

/**
 * What the global drawer shows, shared by every space: an agent (Office, Team, Org Chart...) or a task
 * (Kanban, timeline, agent profile). Opening one closes the other.
 */
export const useSelection = create<{
  agentId: string | null;
  taskId: string | null;
  open(id: string): void;
  openTask(id: string): void;
  close(): void;
}>((set) => ({
  agentId: null,
  taskId: null,
  open: (id) => set({ agentId: id, taskId: null }),
  openTask: (id) => set({ taskId: id, agentId: null }),
  close: () => set({ agentId: null, taskId: null }),
}));

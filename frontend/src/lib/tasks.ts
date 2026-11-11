import type { Priority, Task, TaskStatus } from "../api/types";
import type { Tone } from "../components/ui";

export const COLUMNS: { status: TaskStatus; label: string; future?: string }[] = [
  { status: "CREATED", label: "Created" },
  { status: "READY", label: "Ready" },
  { status: "ASSIGNED", label: "Assigned" },
  { status: "BLOCKED", label: "Blocked" },
  { status: "IN_PROGRESS", label: "In progress", future: "The assigned agent is working on its branch, in its sandbox." },
  { status: "IN_REVIEW", label: "In review", future: "Tests passed; a teammate reviews the merge request." },
  { status: "IN_TEST", label: "In test", future: "The platform runs the tests on the submission." },
  { status: "VALIDATION", label: "Validation", future: "Every gate is green; waiting for your approval to merge." },
  { status: "DONE", label: "Done", future: "Merged into develop after the Lead's review. Only the validation engine closes a task." },
];

export const STATUS_TONE: Record<TaskStatus, Tone> = {
  CREATED: "muted", READY: "muted", ASSIGNED: "active", IN_PROGRESS: "active", BLOCKED: "danger",
  IN_REVIEW: "review", IN_TEST: "testing", VALIDATION: "accent", DONE: "ok", CANCELLED: "muted",
};

export const PRIORITY_TONE: Record<Priority, Tone> = { LOW: "muted", MEDIUM: "muted", HIGH: "warn", CRITICAL: "danger" };

export function statusLabel(s: TaskStatus): string {
  return COLUMNS.find((c) => c.status === s)?.label ?? s.charAt(0) + s.slice(1).toLowerCase();
}

export interface Scheduled { task: Task; start: number; duration: number; level: number }

/**
 * Planned schedule from the Lead's estimates: a task starts when its last dependency ends. Days are working
 * days from the start of the plan, not calendar dates. Cancelled tasks are left out.
 */
export function schedule(tasks: Task[]): Scheduled[] {
  const live = tasks.filter((t) => t.status !== "CANCELLED");
  const byId = new Map(live.map((t) => [t.id, t]));
  const memo = new Map<string, { start: number; level: number }>();
  const visiting = new Set<string>();
  const place = (t: Task): { start: number; level: number } => {
    const known = memo.get(t.id);
    if (known) return known;
    if (visiting.has(t.id)) return { start: 0, level: 0 };
    visiting.add(t.id);
    let start = 0;
    let level = 0;
    for (const d of t.dependsOn) {
      const dep = byId.get(d);
      if (!dep) continue;
      const p = place(dep);
      start = Math.max(start, p.start + duration(dep));
      level = Math.max(level, p.level + 1);
    }
    visiting.delete(t.id);
    const r = { start, level };
    memo.set(t.id, r);
    return r;
  };
  return live.map((t) => ({ task: t, ...place(t), duration: duration(t) }))
    .sort((a, b) => a.start - b.start || a.task.number - b.task.number);
}

function duration(t: Task): number {
  return t.estimateDays && t.estimateDays > 0 ? Number(t.estimateDays) : 1;
}

/**
 * What dropping a card on a Kanban column means. Statuses are derived by the engine (dependencies, assignee,
 * blocking), so a drop maps to the action that produces the target status, or is refused with the reason.
 */
export type DropAction =
  | { kind: "none" }
  | { kind: "assign"; unblockFirst: boolean } // ask who
  | { kind: "unassign" }
  | { kind: "unblock" }
  | { kind: "block" } // ask why
  | { kind: "cancel" } // ask to confirm
  | { kind: "refuse"; reason: string };

const ENGINE: TaskStatus[] = ["IN_PROGRESS", "IN_REVIEW", "IN_TEST", "VALIDATION", "DONE"];

export function dropAction(task: Pick<Task, "status" | "assigneeId" | "dependsOn">, target: TaskStatus | "CANCELLED",
  unfinishedDeps: number): DropAction {
  const from = task.status;
  if (from === target) return { kind: "none" };
  if (ENGINE.includes(from) || from === "DONE" || from === "CANCELLED") {
    return { kind: "refuse", reason: "Work has started on this task; the execution engine moves it from here." };
  }
  if (target === "CANCELLED") return { kind: "cancel" };
  if (target === "BLOCKED") return { kind: "block" };
  if (ENGINE.includes(target)) {
    return { kind: "refuse", reason: `"${statusLabel(target)}" follows the real work: tests, review and your merge approval move it.` };
  }
  if (from === "BLOCKED") {
    if (target === "ASSIGNED" && !task.assigneeId) return { kind: "assign", unblockFirst: true };
    return { kind: "unblock" };
  }
  if (target === "ASSIGNED") {
    if (unfinishedDeps > 0) {
      return { kind: "refuse", reason: `It waits on ${unfinishedDeps} unfinished ${unfinishedDeps > 1 ? "tasks" : "task"}: set an assignee in the task panel, it is assigned once they are done.` };
    }
    return { kind: "assign", unblockFirst: false };
  }
  if (target === "READY") {
    if (from === "ASSIGNED") return { kind: "unassign" };
    return { kind: "refuse", reason: `It waits on ${unfinishedDeps || "its"} unfinished ${unfinishedDeps === 1 ? "dependency" : "dependencies"}; remove them in the task panel to make it ready.` };
  }
  // target CREATED: "waiting on dependencies" comes from adding a dependency.
  return { kind: "refuse", reason: "\"Created\" means waiting on other tasks: add a dependency in the task panel." };
}

import type { Priority, Task, TaskStatus } from "../api/types";
import type { Tone } from "../components/ui";

export const COLUMNS: { status: TaskStatus; label: string; future?: string }[] = [
  { status: "CREATED", label: "Created" },
  { status: "READY", label: "Ready" },
  { status: "ASSIGNED", label: "Assigned" },
  { status: "IN_PROGRESS", label: "In progress", future: "Reached when an agent starts working in its sandbox (increment 4)." },
  { status: "BLOCKED", label: "Blocked" },
  { status: "IN_REVIEW", label: "In review", future: "Reached on submission for code review (increment 4)." },
  { status: "IN_TEST", label: "In test", future: "Reached when QA runs the tests (increment 4)." },
  { status: "VALIDATION", label: "Validation", future: "The validation engine checks tests, review and criteria (increment 4)." },
  { status: "DONE", label: "Done", future: "Only the validation engine closes a task (increment 4)." },
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

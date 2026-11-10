import type { Agent, Department } from "../api/types";
import { Dot, type Tone } from "./ui";

/** Identity colors per department (identity only; state colors are separate). */
export const DEPARTMENT_COLORS: Record<Department, string> = {
  DIRECTION: "#6E6A86",
  ARCHITECTURE: "#4D7A99",
  PRODUCT: "#9A6A7E",
  BACKEND: "#4E6FA8",
  FRONTEND: "#4F8C7A",
  QA: "#A08A4A",
  SECURITY: "#9C5A52",
  DEVOPS: "#A8744A",
  OTHER: "#7A7F86",
};

export const DEPARTMENT_LABELS: Record<Department, string> = {
  DIRECTION: "Direction",
  ARCHITECTURE: "Architecture",
  PRODUCT: "Product",
  BACKEND: "Backend",
  FRONTEND: "Frontend",
  QA: "QA & Testing",
  SECURITY: "Security",
  DEVOPS: "DevOps",
  OTHER: "Specialists",
};

export function AgentAvatar({ agent, size = 36 }: { agent: Pick<Agent, "name" | "department" | "lead">; size?: number }) {
  const color = DEPARTMENT_COLORS[agent.department];
  const initials = agent.name.split(/\s+/).filter((w) => /[A-Za-z]/.test(w[0])).map((w) => w[0]).join("").slice(0, 2).toUpperCase();
  return (
    <span
      className="relative inline-flex shrink-0 items-center justify-center rounded-sm font-semibold text-white"
      style={{ width: size, height: size, fontSize: Math.max(9, size * 0.38), background: color }}
      aria-hidden
    >
      {initials}
      {agent.lead && size >= 28 && <span className="absolute -bottom-0.5 -right-0.5 h-2 w-2 rotate-45 bg-ink ring-2 ring-[var(--bg)]" />}
    </span>
  );
}

/** Visible state derived from real activity (docs/03-ux-ui.md §4.4). */
export function agentState(agent: Pick<Agent, "status" | "activity">): { label: string; tone: Tone; live: boolean } {
  if (agent.status === "PAUSED") return { label: "Paused", tone: "muted", live: false };
  if (agent.status === "DISMISSED") return { label: "Dismissed", tone: "muted", live: false };
  switch (agent.activity) {
    case "THINKING": return { label: "Thinking", tone: "active", live: true };
    case "WORKING": return { label: "Working on a task", tone: "active", live: true };
    case "AVAILABLE": return { label: "Available", tone: "ok", live: false };
    case "IN_MEETING": return { label: "In meeting", tone: "warn", live: false };
    case "BLOCKED": return { label: "Blocked", tone: "danger", live: false };
    default: return { label: agent.activity.toLowerCase(), tone: "muted", live: false };
  }
}

export function StateDot({ agent }: { agent: Pick<Agent, "status" | "activity"> }) {
  const s = agentState(agent);
  return (
    <span className="inline-flex items-center gap-1.5 text-[12px] text-muted">
      <Dot tone={s.tone} live={s.live} />
      {s.label}
    </span>
  );
}

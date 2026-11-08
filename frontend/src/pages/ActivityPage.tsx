import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router";
import { api } from "../api/client";
import type { Agent, EventRecord } from "../api/types";
import { AgentAvatar } from "../components/AgentAvatar";
import { Dot, Empty, PageHeader, Segmented, SkeletonRows } from "../components/ui";
import { describe, tone } from "../lib/events";
import { time } from "../lib/format";
import { useAgents } from "../lib/queries";

const FILTERS = [
  { value: "", label: "All" }, { value: "agent.", label: "Agents" }, { value: "task.", label: "Tasks" },
  { value: "meeting.", label: "Meetings" }, { value: "message.", label: "Messages" }, { value: "model.", label: "Model calls" },
];

export function ActivityPage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const [filter, setFilter] = useState("");
  const events = useQuery({
    queryKey: ["events", workspaceId, projectId],
    queryFn: () => api.get<EventRecord[]>(`/api/v1/workspaces/${workspaceId}/events?projectId=${projectId}&limit=300`),
  });
  const agents = useAgents(projectId);
  const shown = (events.data ?? []).filter((e) => !filter || e.type.startsWith(filter)).reverse();

  return (
    <div className="max-w-5xl">
      <PageHeader title="Activity" subtitle="Every recorded event of this project, newest first."
        action={<Segmented value={filter} onChange={setFilter} items={FILTERS} />} />
      {events.isPending ? <SkeletonRows rows={10} /> : shown.length === 0
        ? <Empty title="No event of this kind yet" />
        : <EventList events={shown} agents={agents.data ?? []} />}
    </div>
  );
}

/** Dense, time-first event log grouped by minute. Each line is a recorded fact. */
export function EventList({ events, agents, compact = false }: { events: EventRecord[]; agents: Agent[]; compact?: boolean }) {
  const byId = new Map(agents.map((a) => [a.id, a]));
  const names = Object.fromEntries(agents.map((a) => [a.id, a.name]));
  let lastMinute = "";
  return (
    <ol className="border-t border-line">
      {events.map((e) => {
        const actor = e.actor.kind === "AGENT" && e.actor.id ? byId.get(e.actor.id) : undefined;
        const t = tone(e);
        const minute = time(e.occurredAt).slice(0, 5);
        const showTime = minute !== lastMinute;
        lastMinute = minute;
        return (
          <li key={e.id} className={`grid grid-cols-[48px_20px_1fr] items-start gap-3 border-b border-line ${compact ? "py-1.5" : "py-2"} text-[13px]`}>
            <time className={`tabular pt-px font-mono text-[11px] ${showTime ? "text-muted" : "text-transparent"}`} dateTime={e.occurredAt} title={time(e.occurredAt)}>
              {minute}
            </time>
            <span className="pt-0.5">
              {actor ? <AgentAvatar agent={actor} size={18} /> : (
                <span className="flex h-[18px] w-[18px] items-center justify-center">
                  <Dot tone={t === "danger" ? "danger" : t === "warn" ? "warn" : "muted"} />
                </span>
              )}
            </span>
            <div className="min-w-0">
              <p className={`break-words ${t === "danger" ? "text-danger" : "text-fg"}`}>{describe(e, names)}</p>
              {!compact && <p className="font-mono text-[11px] leading-4 text-faint">{e.type}</p>}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

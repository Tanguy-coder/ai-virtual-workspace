import { useQuery } from "@tanstack/react-query";
import { ArrowRight } from "lucide-react";
import type { ReactNode } from "react";
import { Link, useParams } from "react-router";
import { api } from "../api/client";
import type { Approval, Briefing, Meeting, Project, ProjectDashboard } from "../api/types";
import { AgentAvatar, StateDot } from "../components/AgentAvatar";
import { Dot, SectionTitle, SkeletonRows, type Tone } from "../components/ui";
import { tokens, usd } from "../lib/format";
import { useAgents, useOpenJobs } from "../lib/queries";
import { useSelection } from "../lib/selection";
import { EventList } from "./ActivityPage";

/**
 * Executive Workspace: one key figure, then what needs a decision, then delivery and activity.
 * Every number comes from recorded data; what is not built yet is said, never invented.
 */
export function DashboardPage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const open = useSelection((s) => s.open);
  const project = useQuery({ queryKey: ["project", projectId], queryFn: () => api.get<Project>(`/api/v1/projects/${projectId}`) });
  const dashboard = useQuery({
    queryKey: ["dashboard", projectId],
    queryFn: () => api.get<ProjectDashboard>(`/api/v1/projects/${projectId}/dashboard`),
  });
  const agents = useAgents(projectId);
  const jobs = useOpenJobs(projectId);
  const waiting = useQuery({
    queryKey: ["waiting", workspaceId],
    queryFn: () => api.get<Briefing[]>(`/api/v1/workspaces/${workspaceId}/briefings/waiting`),
  });
  const approvals = useQuery({
    queryKey: ["approvals", workspaceId],
    queryFn: () => api.get<Approval[]>(`/api/v1/workspaces/${workspaceId}/approvals`),
  });
  const meetings = useQuery({
    queryKey: ["meetings", projectId],
    queryFn: () => api.get<Meeting[]>(`/api/v1/projects/${projectId}/meetings`),
  });

  const d = dashboard.data;
  const base = `/w/${workspaceId}/p/${projectId}`;
  const team = agents.data ?? [];
  const busy = team.filter((a) => a.status === "ACTIVE" && !["AVAILABLE", "BLOCKED"].includes(a.activity));
  const blocked = team.filter((a) => a.status === "ACTIVE" && a.activity === "BLOCKED");
  const t = d?.tasksByStatus ?? {};
  const taskTotal = Object.entries(t).filter(([k]) => k !== "CANCELLED").reduce((n, [, v]) => n + (v ?? 0), 0);
  const inFlight = (t.IN_PROGRESS ?? 0) + (t.IN_REVIEW ?? 0) + (t.IN_TEST ?? 0) + (t.VALIDATION ?? 0);
  const liveMeetings = (meetings.data ?? []).filter((m) => m.status === "IN_PROGRESS" || m.status === "SYNTHESIZING");

  const queue: { tone: Tone; title: string; detail: string; to?: string; onClick?: () => void; action: string }[] = [];
  for (const a of blocked) queue.push({ tone: "danger", title: `${a.name} is blocked`, detail: a.blockedReason ?? "No reason recorded", onClick: () => open(a.id), action: "Open" });
  for (const b of (waiting.data ?? []).filter((x) => x.projectId === projectId)) {
    queue.push(b.status === "AWAITING_ANSWERS"
      ? { tone: "accent", title: `Your Lead has ${b.questions.length} question${b.questions.length > 1 ? "s" : ""}`, detail: "Answers shape the architecture and the team", to: `${base}/briefing`, action: "Answer" }
      : { tone: "accent", title: "Your Lead proposes a team", detail: "Review, edit and hire", to: `${base}/briefing`, action: "Review" });
  }
  for (const a of (approvals.data ?? []).filter((x) => x.projectId === projectId)) {
    queue.push({ tone: "warn", title: a.summary, detail: a.action, to: `/w/${workspaceId}/inbox`, action: "Decide" });
  }
  const failed = (jobs.data ?? []).filter((j) => j.status === "FAILED");
  if (failed.length && !blocked.length) queue.push({ tone: "danger", title: `${failed.length} agent step${failed.length > 1 ? "s" : ""} failed`, detail: failed[0].lastError ?? "", to: `${base}/activity`, action: "See" });
  if (d && d.spend.unpricedCalls > 0) {
    queue.push({ tone: "warn", title: `${d.spend.unpricedCalls} model call${d.spend.unpricedCalls > 1 ? "s" : ""} without a price`, detail: "Not counted in spend or budgets until the model has a price", to: `/w/${workspaceId}/settings`, action: "Set price" });
  }

  const ratio = d?.dailyLimitUsd ? d.spend.todayUsd / d.dailyLimitUsd : null;

  return (
    <div>
      <div className="mb-8 flex flex-wrap items-end justify-between gap-6">
        <div className="min-w-0">
          <h1 className="text-[20px] font-semibold leading-[26px] tracking-[-0.01em]">{project.data?.name ?? "Project"}</h1>
          <p className="mt-0.5 text-[13px] text-muted">{project.data?.description || "Every figure on this page comes from recorded activity."}</p>
        </div>
        {d && (
          <div className="sm:text-right">
            <p className="label">Model spend today</p>
            <p className="tabular font-mono text-[28px] font-medium leading-8">
              {usd(d.spend.todayUsd)}
              <span className="text-[15px] text-faint"> / {d.dailyLimitUsd ? usd(d.dailyLimitUsd, 2) : "no limit"}</span>
            </p>
            {ratio !== null && (
              <span className="mt-1.5 block h-1 w-48 sm:ml-auto overflow-hidden rounded-full bg-line">
                <span className={`block h-full ${ratio >= 1 ? "bg-danger" : ratio >= 0.8 ? "bg-warn" : "bg-fg"}`} style={{ width: `${Math.min(ratio, 1) * 100}%` }} />
              </span>
            )}
          </div>
        )}
      </div>

      {!d ? <SkeletonRows rows={8} /> : (
        <div className="grid gap-x-12 gap-y-10 xl:grid-cols-[minmax(0,1fr)_360px]">
          <div className="min-w-0 space-y-10">
            <section>
              <SectionTitle count={queue.length}>What needs you</SectionTitle>
              {queue.length === 0 ? (
                <p className="py-3 text-[13px] text-muted">Nothing waits for a decision. Blockages, the Lead's questions and approvals will appear here first.</p>
              ) : (
                <ul>
                  {queue.map((q, i) => {
                    const body = (
                      <>
                        <Dot tone={q.tone} className="mt-1.5" />
                        <div className="min-w-0 flex-1">
                          <p className="font-medium">{q.title}</p>
                          <p className="truncate text-[12px] text-muted">{q.detail}</p>
                        </div>
                        <span className="flex items-center gap-1 text-[12px] font-medium text-fg opacity-70 group-hover:opacity-100">{q.action} <ArrowRight size={13} /></span>
                      </>
                    );
                    const cls = "group flex w-full items-start gap-3 border-b border-line py-2.5 text-left text-[13px] hover:bg-panel-2/60";
                    return <li key={i}>{q.to ? <Link to={q.to} className={cls}>{body}</Link> : <button onClick={q.onClick} className={cls}>{body}</button>}</li>;
                  })}
                </ul>
              )}
            </section>

            <section>
              <SectionTitle>Delivery</SectionTitle>
              <dl className="grid grid-cols-2 gap-x-8 gap-y-4 pt-2 sm:grid-cols-4">
                <Figure label="Tasks" value={taskTotal} detail={taskTotal ? `${t.ASSIGNED ?? 0} assigned · ${t.READY ?? 0} ready` : "no plan yet"} to={`${base}/tasks`} />
                <Figure label="In flight" value={inFlight} detail="in progress, review, test" to={`${base}/tasks`} />
                <Figure label="Done" value={t.DONE ?? 0} detail="validated by the engine" />
                <Figure label="Blocked" value={t.BLOCKED ?? 0} detail="tasks marked blocked" tone={(t.BLOCKED ?? 0) > 0 ? "danger" : undefined} to={`${base}/tasks`} />
                <Figure label="Agents" value={d.agentCount} detail={`${busy.length} at work · ${blocked.length} blocked`} to={`${base}/team`} />
                <Figure label="Spend total" value={usd(d.spend.totalUsd)} detail={`${d.spend.callsTotal} calls · ${tokens(d.spend.inputTokens + d.spend.outputTokens)} tokens`} mono />
                <Figure label="Documents" value={d.documents} detail="specifications and notes" to={`${base}/documents`} />
                <Figure label="Tests · findings" value="—" detail="from increments 4 and 5" muted />
              </dl>
            </section>

            <section>
              <SectionTitle action={<Link to={`${base}/activity`} className="flex items-center gap-1 text-[12px] font-medium text-muted hover:text-fg">All activity <ArrowRight size={13} /></Link>}>
                Recent activity
              </SectionTitle>
              <EventList events={[...d.recentEvents].reverse().slice(0, 12)} agents={team} compact />
            </section>
          </div>

          <aside className="space-y-10">
            <section>
              <SectionTitle count={busy.length}>At work now</SectionTitle>
              {busy.length === 0 && liveMeetings.length === 0 ? <p className="py-2 text-[13px] text-muted">Nobody is working at the moment.</p> : (
                <ul>
                  {liveMeetings.map((m) => (
                    <li key={m.id}>
                      <Link to={`${base}/meetings/${m.id}`} className="flex items-center gap-3 border-b border-line py-2 text-[13px] hover:bg-panel-2/60">
                        <Dot tone="warn" />
                        <span className="min-w-0 flex-1 truncate">{m.title}</span>
                        <span className="tabular font-mono text-[11px] text-faint">{m.participantIds.length} people</span>
                      </Link>
                    </li>
                  ))}
                  {busy.map((a) => (
                    <li key={a.id}>
                      <button onClick={() => open(a.id)} className="flex w-full items-center gap-3 border-b border-line py-2 text-left hover:bg-panel-2/60">
                        <AgentAvatar agent={a} size={22} />
                        <span className="min-w-0 flex-1 truncate text-[13px]">{a.name}</span>
                        <StateDot agent={a} />
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
            <section>
              <SectionTitle>Team</SectionTitle>
              <ul>
                {team.slice(0, 12).map((a) => (
                  <li key={a.id}>
                    <button onClick={() => open(a.id)} className="flex w-full items-center gap-3 border-b border-line py-1.5 text-left hover:bg-panel-2/60">
                      <AgentAvatar agent={a} size={20} />
                      <span className="min-w-0 flex-1 truncate text-[13px]">{a.name}</span>
                      <StateDot agent={a} />
                    </button>
                  </li>
                ))}
              </ul>
              {team.length > 12 && <Link to={`${base}/team`} className="mt-2 block text-[12px] text-muted hover:text-fg">All {team.length} agents</Link>}
            </section>
          </aside>
        </div>
      )}
    </div>
  );
}

function Figure({ label, value, detail, to, tone, mono, muted }: {
  label: string; value: ReactNode; detail: string; to?: string; tone?: "danger"; mono?: boolean; muted?: boolean;
}) {
  const body = (
    <>
      <dt className="text-[12px] text-muted">{label}</dt>
      <dd className={`tabular mt-0.5 text-[20px] font-medium leading-7 ${mono ? "font-mono text-[18px]" : ""} ${tone === "danger" ? "text-danger" : muted ? "text-faint" : ""}`}>{value}</dd>
      <dd className="truncate text-[12px] text-faint">{detail}</dd>
    </>
  );
  return to ? <Link to={to} className="block min-w-0 hover:[&_dd:first-of-type]:underline">{body}</Link> : <div className="min-w-0">{body}</div>;
}

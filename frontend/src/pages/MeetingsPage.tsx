import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, CircleSlash, Gavel, ListTodo, MessageSquareWarning, Mic, Plus, Send, Video, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api } from "../api/client";
import type { Agent, Meeting, MeetingDetail } from "../api/types";
import { AgentAvatar, StateDot } from "../components/AgentAvatar";
import { Badge, Button, Card, Empty, ErrorText, Field, Input, PageHeader, Select, Textarea, type Tone } from "../components/ui";
import { relative, time } from "../lib/format";
import { byId, useAgents } from "../lib/queries";
import { useSelection } from "../lib/selection";

const STATUS: Record<Meeting["status"], { label: string; tone: Tone }> = {
  IN_PROGRESS: { label: "In progress", tone: "active" },
  SYNTHESIZING: { label: "Writing minutes", tone: "accent" },
  ENDED: { label: "Ended", tone: "ok" },
  FAILED: { label: "Failed", tone: "danger" },
  CANCELLED: { label: "Cancelled", tone: "muted" },
};

/** UX §7 Meeting Room: agenda, participants, turns, the CTO's voice, then minutes with decisions and actions. */
export function MeetingsPage() {
  const { workspaceId = "", projectId = "", meetingId } = useParams();
  const navigate = useNavigate();
  const meetings = useQuery({
    queryKey: ["meetings", projectId],
    queryFn: () => api.get<Meeting[]>(`/api/v1/projects/${projectId}/meetings`),
  });
  const agents = useAgents(projectId);
  const [convening, setConvening] = useState(false);
  const selected = meetingId ?? meetings.data?.[0]?.id;
  const base = `/w/${workspaceId}/p/${projectId}/meetings`;

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader title="Meetings" subtitle="Every turn is a real model call; minutes feed shared memory and the plan."
        action={<Button onClick={() => setConvening(true)}><Plus size={14} /> Convene a meeting</Button>} />
      {convening && <Convene projectId={projectId} agents={agents.data ?? []} onDone={(id) => { setConvening(false); if (id) navigate(`${base}/${id}`); }} />}
      {meetings.isSuccess && meetings.data.length === 0 && !convening && (
        <Empty icon={<Video size={18} />} title="No meeting yet">
          The Lead runs the kickoff meeting once you approve the team. You can also convene agents yourself.
        </Empty>
      )}
      {meetings.data && meetings.data.length > 0 && (
        <div className="grid gap-5 lg:grid-cols-[320px_minmax(0,1fr)]">
          <nav className="space-y-2">
            {meetings.data.map((m) => (
              <Link key={m.id} to={`${base}/${m.id}`}
                className={`block rounded-md border p-3 transition ${m.id === selected ? "border-accent bg-accent/8 ring-2 ring-accent/15" : "border-line bg-panel hover:border-line-strong"}`}>
                <div className="flex items-center justify-between gap-2">
                  <p className="truncate text-[13px] font-semibold">{m.title}</p>
                  <Badge tone={STATUS[m.status].tone}>{STATUS[m.status].label}</Badge>
                </div>
                <p className="mt-1 text-[12px] text-muted">{m.purpose === "KICKOFF" ? "Kickoff" : "Meeting"} · {relative(m.startedAt)}</p>
              </Link>
            ))}
          </nav>
          {selected && <Room key={selected} meetingId={selected} agents={byId(agents.data)} projectId={projectId} />}
        </div>
      )}
    </div>
  );
}

function Room({ meetingId, agents, projectId }: { meetingId: string; agents: Record<string, Agent>; projectId: string }) {
  const queryClient = useQueryClient();
  const openAgent = useSelection((s) => s.open);
  const openTask = useSelection((s) => s.openTask);
  const detail = useQuery({ queryKey: ["meeting", meetingId], queryFn: () => api.get<MeetingDetail>(`/api/v1/meetings/${meetingId}`) });
  const [draft, setDraft] = useState("");
  const bottom = useRef<HTMLDivElement>(null);
  const speak = useMutation({
    mutationFn: () => api.post(`/api/v1/meetings/${meetingId}/speak`, { content: draft }),
    onSuccess: () => { setDraft(""); queryClient.invalidateQueries({ queryKey: ["meeting", meetingId] }); },
  });
  const cancel = useMutation({
    mutationFn: () => api.post(`/api/v1/meetings/${meetingId}/cancel`, {}),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["meetings", projectId] }),
  });
  const toTask = useMutation({
    mutationFn: (index: number) => api.post<{ id: string }>(`/api/v1/meetings/${meetingId}/action-items/${index}/task`, {}),
    onSuccess: (t) => {
      queryClient.invalidateQueries({ queryKey: ["meeting", meetingId] });
      queryClient.invalidateQueries({ queryKey: ["tasks", projectId] });
      openTask(t.id);
    },
  });
  useEffect(() => { bottom.current?.scrollIntoView({ block: "nearest" }); }, [detail.data?.messages.length]);

  const d = detail.data;
  if (!d) return <Card><p className="text-[13px] text-muted">Loading…</p></Card>;
  const m = d.meeting;
  const open = m.status === "IN_PROGRESS" || m.status === "SYNTHESIZING";
  const nextSpeaker = m.status === "IN_PROGRESS" ? d.participantIds[m.turnIndex] : m.status === "SYNTHESIZING" ? m.organizerId : null;

  return (
    <div className="min-w-0 space-y-5">
      <Card title={m.title} subtitle={`${m.purpose === "KICKOFF" ? "Kickoff" : "Meeting"} · started ${relative(m.startedAt)} · ${m.rounds} round${m.rounds > 1 ? "s" : ""}`}
        action={<div className="flex items-center gap-2">
          <Badge tone={STATUS[m.status].tone}>{STATUS[m.status].label}</Badge>
          {open && <Button size="sm" variant="danger" onClick={() => cancel.mutate()}><CircleSlash size={13} /> End</Button>}
        </div>}>
        <div className="grid gap-5 md:grid-cols-[minmax(0,1fr)_220px]">
          <div>
            <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-faint">Agenda</p>
            <p className="whitespace-pre-wrap text-[13px] leading-relaxed">{m.agenda}</p>
            {m.error && <p className="mt-3 rounded-lg border border-danger/30 bg-danger/5 px-3 py-2 text-[12px] text-danger">{m.error}</p>}
          </div>
          <div>
            <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-faint">Participants</p>
            <ul className="space-y-1.5">
              {d.participantIds.map((id) => {
                const a = agents[id];
                if (!a) return null;
                return (
                  <li key={id}>
                    <button onClick={() => openAgent(id)} className={`flex w-full items-center gap-2 rounded-lg px-1.5 py-1 text-left hover:bg-panel-2 ${id === nextSpeaker ? "bg-accent/10" : ""}`}>
                      <AgentAvatar agent={a} size={24} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[12px] font-medium">{a.name}{id === m.organizerId && <span className="text-faint"> · organizer</span>}</span>
                        {id === nextSpeaker ? <span className="flex items-center gap-1 text-[11px] text-accent-2"><Mic size={10} /> {m.status === "SYNTHESIZING" ? "writing the minutes" : "speaking next"}</span> : <StateDot agent={a} />}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>
        </div>
      </Card>

      <Card title="Transcript" padded={false}>
        <div className="max-h-[560px] space-y-4 overflow-y-auto p-5">
          {d.messages.length === 0 && <p className="text-[13px] text-muted">Nobody has spoken yet.</p>}
          {d.messages.map((msg) => {
            const a = msg.authorKind === "AGENT" ? agents[msg.authorId ?? ""] : null;
            return (
              <div key={msg.id} className={`flex gap-3 ${a ? "" : "flex-row-reverse"}`}>
                {a ? <AgentAvatar agent={a} size={32} /> : <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-accent text-[11px] font-semibold text-white">CTO</span>}
                <div className={`max-w-[85%] rounded-md px-4 py-2.5 ${a ? "rounded-tl-md border border-line bg-panel-2" : "rounded-tr-md bg-ink text-ink-fg"}`}>
                  <p className={`text-[12px] ${a ? "text-muted" : "text-ink-fg/75"}`}>
                    <span className={`font-medium ${a ? "text-fg" : "text-white"}`}>{a?.name ?? "You"}</span>{a && ` · ${a.title}`} · {time(msg.createdAt)}
                  </p>
                  <p className="mt-1 whitespace-pre-wrap text-[13px] leading-relaxed">{msg.content}</p>
                </div>
              </div>
            );
          })}
          {nextSpeaker && agents[nextSpeaker]?.activity === "THINKING" && (
            <div className="flex items-center gap-2 text-[12px] text-active">
              <span className="flex gap-1">{[0, 1, 2].map((i) => <span key={i} className="aiw-pulse h-1.5 w-1.5 rounded-full bg-active" style={{ animationDelay: `${i * 0.2}s` }} />)}</span>
              {agents[nextSpeaker].name} is {m.status === "SYNTHESIZING" ? "writing the minutes" : "preparing their turn"}
            </div>
          )}
          <div ref={bottom} />
        </div>
        {m.status === "IN_PROGRESS" && (
          <form className="flex gap-2 border-t border-line p-4" onSubmit={(e) => { e.preventDefault(); if (draft.trim()) speak.mutate(); }}>
            <Textarea rows={2} value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Speak as CTO — the next speakers will hear you" />
            <Button type="submit" disabled={!draft.trim() || speak.isPending}><Send size={14} /> Speak</Button>
          </form>
        )}
      </Card>

      {m.minutes && (
        <Card title="Minutes" subtitle={`Published ${m.endedAt ? relative(m.endedAt) : ""} · decisions recorded in shared memory`}>
          <p className="text-[13px] leading-relaxed">{m.minutes.summary}</p>
          <div className="mt-5 grid gap-5 md:grid-cols-2">
            <div>
              <p className="mb-2 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-faint"><Gavel size={12} /> Decisions</p>
              {m.minutes.decisions.length === 0 && <p className="text-[12px] text-muted">None.</p>}
              <ul className="space-y-2">
                {m.minutes.decisions.map((x, i) => (
                  <li key={i} className="rounded-lg border border-line bg-panel-2 p-2.5">
                    <p className="flex items-center gap-1.5 text-[13px] font-medium"><CheckCircle2 size={13} className="text-ok" /> {x.title}</p>
                    <p className="mt-0.5 text-[12px] leading-relaxed text-muted">{x.detail}</p>
                  </li>
                ))}
              </ul>
            </div>
            <div>
              <p className="mb-2 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-faint"><MessageSquareWarning size={12} /> Disagreements</p>
              {m.minutes.disagreements.length === 0 && <p className="text-[12px] text-muted">None recorded.</p>}
              <ul className="space-y-2">
                {m.minutes.disagreements.map((x, i) => (
                  <li key={i} className="rounded-lg border border-warn/30 bg-warn/5 p-2.5">
                    <p className="text-[13px] font-medium">{x.topic}</p>
                    <p className="mt-0.5 text-[12px] leading-relaxed text-muted">{x.positions}</p>
                  </li>
                ))}
              </ul>
            </div>
          </div>
          <div className="mt-5">
            <p className="mb-2 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-faint"><ListTodo size={12} /> Action items</p>
            {m.minutes.actionItems.length === 0 && <p className="text-[12px] text-muted">None.</p>}
            <ul className="divide-y divide-line rounded-lg border border-line">
              {m.minutes.actionItems.map((x, i) => {
                const owner = x.ownerAgentId ? agents[x.ownerAgentId] : undefined;
                return (
                  <li key={i} className="flex flex-wrap items-center gap-3 px-3 py-2.5">
                    {owner ? <AgentAvatar agent={owner} size={22} /> : <span className="h-[22px] w-[22px] rounded-md border border-dashed border-line-strong" />}
                    <div className="min-w-0 flex-1">
                      <p className="text-[13px]">{x.title}</p>
                      <p className="text-[11px] text-muted">{owner?.name ?? "No owner"}{x.due && ` · due ${x.due}`}</p>
                    </div>
                    {x.taskId
                      ? <Button size="sm" variant="subtle" onClick={() => openTask(x.taskId!)}>Open task</Button>
                      : <Button size="sm" variant="ghost" disabled={toTask.isPending} onClick={() => toTask.mutate(i)}>Make it a task</Button>}
                  </li>
                );
              })}
            </ul>
            <ErrorText error={toTask.error} />
          </div>
        </Card>
      )}
    </div>
  );
}

function Convene({ projectId, agents, onDone }: { projectId: string; agents: Agent[]; onDone: (id?: string) => void }) {
  const active = agents.filter((a) => a.status === "ACTIVE");
  const [title, setTitle] = useState("");
  const [agenda, setAgenda] = useState("");
  const [organizerId, setOrganizerId] = useState(active.find((a) => a.lead)?.id ?? "");
  const [participants, setParticipants] = useState<string[]>([]);
  const [rounds, setRounds] = useState("1");
  const convene = useMutation({
    mutationFn: () => api.post<Meeting>(`/api/v1/projects/${projectId}/meetings`, {
      title, agenda, organizerId, participantIds: participants.filter((p) => p !== organizerId), rounds: Number(rounds),
    }),
    onSuccess: (m) => onDone(m.id),
  });
  const count = new Set([organizerId, ...participants]).size;
  return (
    <Card className="mb-5" title="Convene a meeting" subtitle={`${count} participant${count > 1 ? "s" : ""} · each turn is a model call (${count * Number(rounds) + 1} calls in total)`}
      action={<button onClick={() => onDone()} className="rounded-lg p-1 text-muted hover:bg-panel-2" aria-label="Close"><X size={16} /></button>}>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-4">
          <Field label="Title"><Input autoFocus maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} placeholder="API contract review" /></Field>
          <Field label="Agenda"><Textarea rows={5} value={agenda} onChange={(e) => setAgenda(e.target.value)} placeholder={"1. …\n2. …"} /></Field>
        </div>
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3">
            <Field label="Organizer (writes the minutes)">
              <Select value={organizerId} onChange={(e) => setOrganizerId(e.target.value)}>
                {active.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </Select>
            </Field>
            <Field label="Rounds">
              <Select value={rounds} onChange={(e) => setRounds(e.target.value)}>
                {["1", "2", "3"].map((r) => <option key={r} value={r}>{r}</option>)}
              </Select>
            </Field>
          </div>
          <Field label="Participants">
            <div className="grid max-h-44 grid-cols-2 gap-1 overflow-y-auto rounded-lg border border-line bg-panel-2 p-2">
              {active.filter((a) => a.id !== organizerId).map((a) => (
                <label key={a.id} className="flex items-center gap-2 rounded-md px-1.5 py-1 text-[12px] hover:bg-panel">
                  <input type="checkbox" className="accent-[var(--accent)]" checked={participants.includes(a.id)}
                    onChange={(e) => setParticipants(e.target.checked ? [...participants, a.id] : participants.filter((p) => p !== a.id))} />
                  <AgentAvatar agent={a} size={18} /> <span className="truncate">{a.name}</span>
                </label>
              ))}
            </div>
          </Field>
          <ErrorText error={convene.error} />
          <Button disabled={!title.trim() || !agenda.trim() || !organizerId || count < 2 || convene.isPending} onClick={() => convene.mutate()}>
            <Video size={14} /> Start the meeting
          </Button>
        </div>
      </div>
    </Card>
  );
}

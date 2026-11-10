import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, CheckCircle2, Circle, Lock, LockOpen, Pencil, Plus, Send, X } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { Agent, EventRecord, Message, Task } from "../api/types";
import { describe } from "../lib/events";
import { relative, time } from "../lib/format";
import { byId, useAgents, useTasks } from "../lib/queries";
import { useSelection } from "../lib/selection";
import { PRIORITY_TONE, STATUS_TONE, statusLabel } from "../lib/tasks";
import { Link } from "react-router";
import { RUN_TONE, useRun } from "../lib/engineering";
import { AgentAvatar } from "./AgentAvatar";
import { Badge, Button, ErrorText, Field, Input, Select, Tabs, Textarea } from "./ui";

/** Global task drawer (UX §7 Tasks): criteria, assignee, dependencies, discussion and history. */
export function TaskDrawer({ workspaceId }: { workspaceId: string }) {
  const { taskId, close, open: openAgent } = useSelection();
  const queryClient = useQueryClient();
  const detail = useQuery({
    queryKey: ["task", taskId],
    queryFn: () => api.get<{ task: Task; threadId: string }>(`/api/v1/tasks/${taskId}`),
    enabled: !!taskId,
  });
  const t = detail.data?.task;
  const run = useRun(taskId);
  const start = useMutation({
    mutationFn: () => api.post(`/api/v1/tasks/${taskId}/run`, {}),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["run", taskId] }),
  });
  const agents = useAgents(t?.projectId ?? "");
  const tasks = useTasks(t?.projectId ?? "");
  const [tab, setTab] = useState<"details" | "discussion" | "history">("details");
  const [blocking, setBlocking] = useState(false);
  const [reason, setReason] = useState("");
  const [editing, setEditing] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [close]);
  useEffect(() => { setTab("details"); setBlocking(false); setEditing(false); }, [taskId]);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["task", taskId] });
    if (t) queryClient.invalidateQueries({ queryKey: ["tasks", t.projectId] });
  };
  const act = useMutation({
    mutationFn: ({ path, body, method = "post" }: { path: string; body?: unknown; method?: "post" | "put" | "del" }) =>
      method === "put" ? api.put(`/api/v1/tasks/${taskId}${path}`, body ?? {})
        : method === "del" ? api.del(`/api/v1/tasks/${taskId}${path}`)
          : api.post(`/api/v1/tasks/${taskId}${path}`, body ?? {}),
    onSuccess: () => { setBlocking(false); setReason(""); refresh(); },
  });

  if (!taskId) return null;
  const agentsById = byId(agents.data);
  const tasksById = byId(tasks.data);
  const closed = t && (t.status === "DONE" || t.status === "CANCELLED");
  const assignee = t?.assigneeId ? agentsById[t.assigneeId] : undefined;
  const candidates = (tasks.data ?? []).filter((x) => t && x.id !== t.id && !t.dependsOn.includes(x.id) && x.status !== "CANCELLED");

  return (
    <aside className="fixed inset-y-0 right-0 z-40 flex w-full max-w-[560px] flex-col slide-in border-l border-line-strong bg-panel shadow-panel" aria-label="Task">
      {t && (
        <>
          <header className="border-b border-line p-5">
            <div className="flex items-start gap-3">
              <div className="min-w-0 flex-1">
                <p className="tabular text-[12px] font-semibold text-faint">T-{t.number}</p>
                <h2 className="mt-0.5 text-[17px] font-semibold leading-snug tracking-tight">{t.title}</h2>
                <div className="mt-2 flex flex-wrap items-center gap-1.5">
                  <Badge tone={STATUS_TONE[t.status]}>{statusLabel(t.status)}</Badge>
                  <Badge tone={PRIORITY_TONE[t.priority]}>{t.priority.toLowerCase()}</Badge>
                  {t.estimateDays != null && <Badge>{Number(t.estimateDays)} d estimated</Badge>}
                  <Badge>{t.sourceKind === "PLAN" ? "planned by the Lead" : t.sourceKind === "MEETING" ? "from a meeting" : "created by you"}</Badge>
                </div>
              </div>
              {!closed && !editing && (
                <Button variant="ghost" size="sm" onClick={() => { setTab("details"); setEditing(true); }}><Pencil size={13} /> Edit</Button>
              )}
              <button onClick={close} className="rounded-lg p-1.5 text-muted hover:bg-panel-2 hover:text-fg" aria-label="Close"><X size={18} /></button>
            </div>
            {t.status === "BLOCKED" && t.blockedReason && (
              <p className="mt-3 rounded-lg border border-danger/30 bg-danger/5 px-3 py-2 text-[12px] text-danger">Blocked: {t.blockedReason}</p>
            )}
          </header>
          <div className="border-b border-line px-5 py-2.5">
            <Tabs value={tab} onChange={setTab} items={[
              { value: "details", label: "Details" },
              { value: "discussion", label: "Discussion" },
              { value: "history", label: "History" },
            ]} />
          </div>

          {tab === "details" && editing && <EditTask task={t} onDone={() => { setEditing(false); refresh(); }} onCancel={() => setEditing(false)} />}
          {tab === "details" && !editing && (
            <div className="min-h-0 flex-1 space-y-6 overflow-y-auto p-5">
              {!run.data?.run && t.status === "ASSIGNED" && (
                <section className="flex items-center gap-3 rounded-md border border-line bg-panel-2/60 px-3 py-2.5 text-[13px]">
                  <span className="text-muted">Work starts by itself when the agent is free.</span>
                  <Button variant="ghost" size="sm" className="ml-auto" disabled={start.isPending} onClick={() => start.mutate()}>Start work now</Button>
                  <ErrorText error={start.error} />
                </section>
              )}
              {run.data?.run && (
                <section className="rounded-md border border-line bg-panel-2/60 px-3 py-2.5">
                  <div className="flex items-center gap-2 text-[13px]">
                    <Badge tone={RUN_TONE[run.data.run.status]} dot>{run.data.run.status.charAt(0) + run.data.run.status.slice(1).toLowerCase()}</Badge>
                    <span className="font-mono text-[12px] text-muted">{run.data.run.branch}</span>
                    <Link to={`/w/${workspaceId}/p/${t.projectId}/runs/${t.id}`} onClick={close} className="ml-auto text-[13px] font-medium hover:underline">Follow the work →</Link>
                  </div>
                  <p className="mt-1 text-[12px] text-faint">{run.data.run.steps} steps · {run.data.steps.at(-1)?.summary}</p>
                </section>
              )}

              <section>
                <p className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-faint">Assignee</p>
                <div className="flex items-center gap-3">
                  {assignee
                    ? <button onClick={() => openAgent(assignee.id)} className="flex items-center gap-2.5 rounded-lg border border-line bg-panel-2 px-2.5 py-1.5 hover:border-line-strong">
                        <AgentAvatar agent={assignee} size={26} />
                        <span className="text-[13px] font-medium">{assignee.name}</span>
                        <span className="text-[12px] text-muted">{assignee.title}</span>
                      </button>
                    : <span className="text-[13px] text-muted">Nobody</span>}
                  {!closed && (
                    <div className="ml-auto w-48">
                      <Select value={t.assigneeId ?? ""} aria-label="Assignee"
                        onChange={(e) => act.mutate({ path: "/assignee", method: "put", body: { agentId: e.target.value || null } })}>
                        <option value="">Unassigned</option>
                        {agents.data?.filter((a) => a.status !== "DISMISSED").map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
                      </Select>
                    </div>
                  )}
                </div>
                {t.status === "CREATED" && t.assigneeId && (
                  <p className="mt-2 text-[12px] text-faint">Assigned once its dependencies are done.</p>
                )}
              </section>

              {t.description && (
                <section>
                  <p className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-faint">Description</p>
                  <p className="whitespace-pre-wrap text-[13px] leading-relaxed">{t.description}</p>
                </section>
              )}

              <section>
                <p className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-faint">Acceptance criteria</p>
                {t.acceptanceCriteria.length === 0 && <p className="text-[13px] text-muted">None yet.</p>}
                <ul className="space-y-1.5">
                  {t.acceptanceCriteria.map((c, i) => (
                    <li key={i} className="flex gap-2 text-[13px]">
                      {c.met ? <CheckCircle2 size={15} className="mt-0.5 shrink-0 text-ok" /> : <Circle size={15} className="mt-0.5 shrink-0 text-faint" />}
                      <span className="leading-relaxed">{c.text}{c.met && c.evidence && <span className="block text-[12px] text-faint">{c.evidence}</span>}</span>
                    </li>
                  ))}
                </ul>
                <p className="mt-2 text-[11px] text-faint">Criteria are checked with evidence during the review (a test that proves them), never by hand.</p>
              </section>

              <section>
                <p className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-faint">Depends on</p>
                {t.dependsOn.length === 0 && <p className="text-[13px] text-muted">No dependency: the task can start as soon as it is assigned.</p>}
                <ul className="space-y-1.5">
                  {t.dependsOn.map((d) => {
                    const dep = tasksById[d];
                    return (
                      <li key={d} className="flex items-center gap-2 rounded-lg border border-line bg-panel-2 px-2.5 py-1.5 text-[13px]">
                        <span className="tabular text-[12px] text-faint">T-{dep?.number}</span>
                        <button className="min-w-0 flex-1 truncate text-left hover:underline" onClick={() => useSelection.getState().openTask(d)}>{dep?.title}</button>
                        {dep && <Badge tone={STATUS_TONE[dep.status]}>{statusLabel(dep.status)}</Badge>}
                        {!closed && (
                          <button className="rounded p-0.5 text-faint hover:text-danger" aria-label="Remove dependency"
                            onClick={() => act.mutate({ path: `/dependencies/${d}`, method: "del" })}><X size={13} /></button>
                        )}
                      </li>
                    );
                  })}
                </ul>
                {!closed && candidates.length > 0 && (
                  <div className="mt-2 flex items-center gap-2">
                    <Plus size={14} className="text-faint" />
                    <Select value="" aria-label="Add a dependency"
                      onChange={(e) => e.target.value && act.mutate({ path: "/dependencies", body: { dependsOn: [e.target.value] } })}>
                      <option value="">Add a dependency…</option>
                      {candidates.map((c) => <option key={c.id} value={c.id}>T-{c.number} {c.title}</option>)}
                    </Select>
                  </div>
                )}
              </section>

              <ErrorText error={act.error} />
              {!closed && (
                <section className="space-y-2 border-t border-line pt-4">
                  {blocking ? (
                    <div className="space-y-2">
                      <Textarea rows={2} autoFocus value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Why is this task blocked?" />
                      <div className="flex gap-2">
                        <Button variant="danger" disabled={!reason.trim()} onClick={() => act.mutate({ path: "/block", body: { reason } })}>Block</Button>
                        <Button variant="subtle" onClick={() => setBlocking(false)}>Cancel</Button>
                      </div>
                    </div>
                  ) : (
                    <div className="flex flex-wrap gap-2">
                      {t.status === "BLOCKED"
                        ? <Button variant="ghost" onClick={() => act.mutate({ path: "/unblock" })}><LockOpen size={14} /> Unblock</Button>
                        : <Button variant="ghost" onClick={() => setBlocking(true)}><Lock size={14} /> Mark blocked</Button>}
                      <Button variant="danger" onClick={() => act.mutate({ path: "/cancel" })}><Ban size={14} /> Cancel task</Button>
                    </div>
                  )}
                </section>
              )}
            </div>
          )}
          {tab === "discussion" && <Discussion threadId={detail.data?.threadId} task={t} names={agentsById} />}
          {tab === "history" && <History workspaceId={workspaceId} taskId={t.id} names={Object.fromEntries((agents.data ?? []).map((a) => [a.id, a.name]))} />}
        </>
      )}
    </aside>
  );
}

function Discussion({ threadId, task, names }: { threadId?: string; task: Task; names: Record<string, Agent> }) {
  const queryClient = useQueryClient();
  const messages = useQuery({
    queryKey: ["thread", threadId],
    queryFn: () => api.get<Message[]>(`/api/v1/threads/${threadId}/messages`),
    enabled: !!threadId,
  });
  const [draft, setDraft] = useState("");
  const send = useMutation({
    mutationFn: () => api.post(`/api/v1/threads/${threadId}/messages`, { content: draft, toAgentId: task.assigneeId, type: "RECOMMENDATION" }),
    onSuccess: () => { setDraft(""); queryClient.invalidateQueries({ queryKey: ["thread", threadId] }); },
  });
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-5">
        {!threadId && <p className="text-[13px] text-muted">No discussion yet on this task.</p>}
        {messages.data?.map((m) => {
          const author = m.authorKind === "AGENT" ? names[m.authorId ?? ""] : null;
          return (
            <div key={m.id} className="flex gap-2.5">
              {author ? <AgentAvatar agent={author} size={28} /> : <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-accent/20 text-[10px] font-semibold text-accent-2">CTO</span>}
              <div className="min-w-0 flex-1 rounded-md border border-line bg-panel-2 px-3 py-2">
                <p className="text-[12px] text-muted">
                  <span className="font-medium text-fg">{author?.name ?? "You"}</span>
                  {m.toAgentId && <> → {names[m.toAgentId]?.name ?? "agent"}</>} · {m.type.toLowerCase().replace("_", " ")} · {time(m.createdAt)}
                </p>
                <p className="mt-1 whitespace-pre-wrap text-[13px] leading-relaxed">{m.content}</p>
              </div>
            </div>
          );
        })}
      </div>
      {threadId && (
        <form className="flex gap-2 border-t border-line p-4" onSubmit={(e) => { e.preventDefault(); if (draft.trim()) send.mutate(); }}>
          <Textarea rows={2} value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Write to the assignee (recorded in the thread)" />
          <Button type="submit" disabled={!draft.trim() || send.isPending} aria-label="Send"><Send size={14} /></Button>
        </form>
      )}
    </div>
  );
}

function History({ workspaceId, taskId, names }: { workspaceId: string; taskId: string; names: Record<string, string> }) {
  const events = useQuery({
    queryKey: ["history", taskId],
    queryFn: () => api.get<EventRecord[]>(`/api/v1/workspaces/${workspaceId}/events?subjectId=${taskId}&limit=200`),
  });
  return (
    <ol className="min-h-0 flex-1 space-y-3 overflow-y-auto p-5">
      {events.data?.map((e) => (
        <li key={e.id} className="flex gap-3 text-[13px]">
          <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-line-strong" />
          <div>
            <p>{describe(e, names)}</p>
            <p className="text-[11px] text-faint">{relative(e.occurredAt)} · {e.actor.kind === "AGENT" ? names[e.actor.id ?? ""] ?? "agent" : e.actor.kind === "HUMAN" ? "you" : "platform"}</p>
          </div>
        </li>
      ))}
    </ol>
  );
}

/** Edits what the task asks for. Status, assignee and dependencies have their own controls. */
function EditTask({ task, onDone, onCancel }: { task: Task; onDone(): void; onCancel(): void }) {
  const [title, setTitle] = useState(task.title);
  const [description, setDescription] = useState(task.description ?? "");
  const [criteria, setCriteria] = useState(task.acceptanceCriteria.map((c) => c.text).join("\n"));
  const [priority, setPriority] = useState(task.priority);
  const [estimate, setEstimate] = useState(task.estimateDays == null ? "" : String(Number(task.estimateDays)));
  const save = useMutation({
    mutationFn: () => api.put(`/api/v1/tasks/${task.id}`, {
      title: title.trim(), description: description.trim(), priority,
      acceptanceCriteria: criteria.split("\n").map((c) => c.trim()).filter(Boolean),
      estimateDays: estimate === "" ? null : Number(estimate),
    }),
    onSuccess: onDone,
  });
  return (
    <form className="min-h-0 flex-1 space-y-4 overflow-y-auto p-5" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <Field label="Title"><Input required maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} /></Field>
      <Field label="Description"><Textarea rows={6} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
      <Field label="Acceptance criteria" hint="One per line, objective and verifiable. Editing them resets their checks.">
        <Textarea rows={5} value={criteria} onChange={(e) => setCriteria(e.target.value)} />
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Priority">
          <Select value={priority} onChange={(e) => setPriority(e.target.value as Task["priority"])}>
            {(["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const).map((p) => <option key={p} value={p}>{p.toLowerCase()}</option>)}
          </Select>
        </Field>
        <Field label="Estimate (days)"><Input type="number" min={0} max={100} step={0.5} value={estimate} onChange={(e) => setEstimate(e.target.value)} /></Field>
      </div>
      <ErrorText error={save.error} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="subtle" onClick={onCancel}>Cancel</Button>
        <Button type="submit" disabled={save.isPending || !title.trim()}>Save</Button>
      </div>
    </form>
  );
}

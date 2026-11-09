import { useMutation, useQueryClient } from "@tanstack/react-query";
import { GitFork, Link2, ListChecks, Plus, X } from "lucide-react";
import { useMemo, useState } from "react";
import { Link, useParams } from "react-router";
import { api } from "../api/client";
import type { Agent, Priority, Task, TaskStatus } from "../api/types";
import { AgentAvatar, DEPARTMENT_COLORS } from "../components/AgentAvatar";
import { Badge, Button, Card, Dot, Empty, ErrorText, Field, Input, PageHeader, Segmented, Select, Textarea } from "../components/ui";
import { byId, useAgents, useTasks } from "../lib/queries";
import { useSelection } from "../lib/selection";
import { COLUMNS, dropAction, PRIORITY_TONE, schedule, STATUS_TONE, statusLabel, type DropAction } from "../lib/tasks";

type View = "kanban" | "list" | "timeline" | "graph";

export function TasksPage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const tasks = useTasks(projectId);
  const agents = useAgents(projectId);
  const [view, setView] = useState<View>("kanban");
  const [creating, setCreating] = useState(false);
  const [assignee, setAssignee] = useState("");
  const agentsById = byId(agents.data);
  const visible = (tasks.data ?? []).filter((t) => !assignee || t.assigneeId === assignee);
  const counts = useMemo(() => {
    const all = tasks.data ?? [];
    return { total: all.filter((t) => t.status !== "CANCELLED").length, blocked: all.filter((t) => t.status === "BLOCKED").length, assigned: all.filter((t) => t.status === "ASSIGNED").length };
  }, [tasks.data]);

  return (
    <div>
      <PageHeader title="Tasks"
        subtitle={tasks.data?.length ? `${counts.total} tasks · ${counts.assigned} assigned · ${counts.blocked} blocked` : "The plan, its dependencies and who owns what."}
        action={<>
          <div className="w-48">
            <Select value={assignee} onChange={(e) => setAssignee(e.target.value)} aria-label="Filter by assignee">
              <option value="">Everyone</option>
              {agents.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </Select>
          </div>
          <Segmented value={view} onChange={setView} items={[
            { value: "kanban", label: "Kanban" }, { value: "list", label: "List" },
            { value: "timeline", label: "Timeline" }, { value: "graph", label: "Graph" },
          ]} />
          <Button onClick={() => setCreating(true)}><Plus size={14} /> New task</Button>
        </>} />

      {creating && <NewTask projectId={projectId} agents={agents.data ?? []} tasks={tasks.data ?? []} onDone={() => setCreating(false)} />}

      {tasks.isSuccess && tasks.data.length === 0 && !creating && (
        <Empty title="No task yet"
          action={<div className="flex gap-2">
            <Link to={`/w/${workspaceId}/p/${projectId}/briefing`}><Button>Brief your Lead</Button></Link>
            <Button variant="ghost" onClick={() => setCreating(true)}>Create a task yourself</Button>
          </div>}>
          In Mode A, the Lead plans and assigns the tasks after the kickoff meeting. In Mode B, you create them.
        </Empty>
      )}

      {visible.length > 0 && view === "kanban" && <Kanban tasks={visible} agents={agentsById} allTasks={tasks.data ?? []} />}
      {visible.length > 0 && view === "list" && <TaskList tasks={visible} agents={agentsById} all={tasks.data ?? []} />}
      {visible.length > 0 && view === "timeline" && <Timeline tasks={tasks.data ?? []} highlight={assignee} agents={agentsById} />}
      {visible.length > 0 && view === "graph" && <Graph tasks={tasks.data ?? []} highlight={assignee} agents={agentsById} />}
    </div>
  );
}

function TaskCard({ task, agent, all, draggable = false, onDragStart, onDragEnd }: {
  task: Task; agent?: Agent; all?: Record<string, Task>; draggable?: boolean; onDragStart?: () => void; onDragEnd?: () => void;
}) {
  const openTask = useSelection((s) => s.openTask);
  const waiting = all ? task.dependsOn.filter((d) => all[d] && all[d].status !== "DONE").length : 0;
  return (
    <button onClick={() => openTask(task.id)} draggable={draggable}
      onDragStart={(e) => { e.dataTransfer.setData("text/task-id", task.id); e.dataTransfer.effectAllowed = "move"; onDragStart?.(); }}
      onDragEnd={onDragEnd}
      className={`group w-full rounded-md border border-line bg-panel-solid p-3 text-left transition hover:border-line-strong ${draggable ? "cursor-grab active:cursor-grabbing" : ""}`}>
      <div className="flex items-center justify-between gap-2">
        <span className="tabular text-[11px] font-semibold text-faint">T-{task.number}</span>
        <Badge tone={PRIORITY_TONE[task.priority]}>{task.priority.toLowerCase()}</Badge>
      </div>
      <p className="mt-1.5 text-[13px] font-medium leading-snug">{task.title}</p>
      {task.status === "BLOCKED" && task.blockedReason && <p className="mt-1.5 line-clamp-2 text-[12px] text-danger">{task.blockedReason}</p>}
      <div className="mt-3 flex items-center gap-2 text-[11px] text-muted">
        {agent ? <AgentAvatar agent={agent} size={20} /> : <span className="h-5 w-5 rounded-md border border-dashed border-line-strong" />}
        <span className="min-w-0 flex-1 truncate">{agent?.name ?? "Unassigned"}</span>
        {waiting > 0 && <span className="flex items-center gap-0.5" title="Waiting on unfinished dependencies"><Link2 size={11} /> {waiting}</span>}
        {task.estimateDays != null && <span className="tabular">{Number(task.estimateDays)}d</span>}
      </div>
    </button>
  );
}

const MOVABLE: TaskStatus[] = ["CREATED", "READY", "ASSIGNED", "BLOCKED"];

/** A drop that needs one more answer: who (assign), why (block) or a confirmation (cancel). */
type Pending = { task: Task; action: Extract<DropAction, { kind: "assign" | "block" | "cancel" }> };

function Kanban({ tasks, agents, allTasks }: { tasks: Task[]; agents: Record<string, Agent>; allTasks: Task[] }) {
  const all = byId(allTasks);
  const queryClient = useQueryClient();
  const [dragging, setDragging] = useState<Task | null>(null);
  const [over, setOver] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const run = useMutation({
    mutationFn: async ({ task, steps }: { task: Task; steps: { path: string; method: "post" | "put"; body?: unknown }[] }) => {
      for (const st of steps) {
        const url = `/api/v1/tasks/${task.id}${st.path}`;
        await (st.method === "put" ? api.put(url, st.body ?? {}) : api.post(url, st.body ?? {}));
      }
    },
    onSuccess: (_d, v) => {
      setPending(null);
      queryClient.invalidateQueries({ queryKey: ["tasks", v.task.projectId] });
      queryClient.invalidateQueries({ queryKey: ["task", v.task.id] });
    },
    onError: (e) => setNotice((e as Error).message),
  });

  function drop(task: Task, target: TaskStatus | "CANCELLED") {
    setOver(null);
    setDragging(null);
    const unfinished = task.dependsOn.filter((d) => all[d] && all[d].status !== "DONE").length;
    const a = dropAction(task, target, unfinished);
    if (a.kind === "none") return;
    if (a.kind === "refuse") return setNotice(a.reason);
    setNotice(null);
    if (a.kind === "unassign") return run.mutate({ task, steps: [{ path: "/assignee", method: "put", body: { agentId: null } }] });
    if (a.kind === "unblock") return run.mutate({ task, steps: [{ path: "/unblock", method: "post" }] });
    setPending({ task, action: a });
  }

  const zone = (key: string, target: TaskStatus | "CANCELLED") => ({
    onDragOver: (e: React.DragEvent) => {
      if (!dragging) return;
      e.preventDefault();
      setOver(key);
    },
    onDragLeave: () => setOver((o) => (o === key ? null : o)),
    onDrop: (e: React.DragEvent) => {
      e.preventDefault();
      const task = all[e.dataTransfer.getData("text/task-id")];
      if (task) drop(task, target);
    },
  });

  return (
    <div>
      {notice && (
        <div role="status" className="mb-3 flex items-start gap-2 rounded-md border border-line-strong bg-panel px-3 py-2 text-[13px]">
          <span className="flex-1">{notice}</span>
          <button onClick={() => setNotice(null)} aria-label="Dismiss" className="text-muted hover:text-fg"><X size={14} /></button>
        </div>
      )}
      <p className="mb-2 text-[12px] text-faint">Drag a card to assign, unassign, block, unblock or cancel it. The later columns follow real work only.</p>
      <div className="-mx-1 flex gap-3 overflow-x-auto px-1 pb-4">
        {COLUMNS.map((c) => {
          const items = tasks.filter((t) => t.status === c.status);
          const target = dragging ? dropAction(dragging, c.status, dragging.dependsOn.filter((d) => all[d] && all[d].status !== "DONE").length) : null;
          const accepts = target && target.kind !== "refuse" && target.kind !== "none";
          return (
            <section key={c.status} {...zone(c.status, c.status)}
              className={`flex w-72 shrink-0 flex-col rounded-md transition ${over === c.status ? (accepts ? "bg-accent/10 ring-1 ring-accent" : "bg-danger/5 ring-1 ring-danger/40") : dragging && accepts ? "bg-panel-2 ring-1 ring-line-strong" : "bg-panel-2/60"}`}>
              <header className="flex items-center gap-2 px-3 py-2.5">
                <Dot tone={STATUS_TONE[c.status]} />
                <h3 className="text-[12px] font-semibold">{c.label}</h3>
                <span className="tabular ml-auto text-[11px] text-faint">{items.length}</span>
              </header>
              <div className="flex min-h-24 flex-col gap-2 px-2 pb-2">
                {items.map((t) => (
                  <TaskCard key={t.id} task={t} agent={t.assigneeId ? agents[t.assigneeId] : undefined} all={all}
                    draggable={MOVABLE.includes(t.status)} onDragStart={() => setDragging(t)} onDragEnd={() => { setDragging(null); setOver(null); }} />
                ))}
                {items.length === 0 && c.future && <p className="px-1.5 py-3 text-[11px] leading-relaxed text-faint">{c.future}</p>}
              </div>
            </section>
          );
        })}
      </div>
      {dragging && (
        <div {...zone("cancel", "CANCELLED")}
          className={`fixed bottom-6 left-1/2 z-30 -translate-x-1/2 rounded-md border border-dashed px-5 py-3 text-[13px] ${over === "cancel" ? "border-danger bg-danger/10 text-danger" : "border-line-strong bg-panel text-muted"}`}>
          Drop here to cancel T-{dragging.number}
        </div>
      )}
      {pending && <DropDialog pending={pending} agents={Object.values(agents)} busy={run.isPending} error={run.error}
        onCancel={() => setPending(null)}
        onConfirm={(value) => {
          const { task, action } = pending;
          if (action.kind === "assign") {
            run.mutate({ task, steps: [...(action.unblockFirst ? [{ path: "/unblock", method: "post" as const }] : []),
              { path: "/assignee", method: "put", body: { agentId: value } }] });
          } else if (action.kind === "block") {
            run.mutate({ task, steps: [{ path: "/block", method: "post", body: { reason: value } }] });
          } else {
            run.mutate({ task, steps: [{ path: "/cancel", method: "post" }] });
          }
        }} />}
    </div>
  );
}

function DropDialog({ pending, agents, busy, error, onCancel, onConfirm }: {
  pending: Pending; agents: Agent[]; busy: boolean; error: unknown; onCancel(): void; onConfirm(value: string): void;
}) {
  const { task, action } = pending;
  const [value, setValue] = useState(action.kind === "assign" ? task.assigneeId ?? "" : "");
  const title = action.kind === "assign" ? `Assign T-${task.number}` : action.kind === "block" ? `Block T-${task.number}` : `Cancel T-${task.number}?`;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" onClick={onCancel}>
      <form role="dialog" aria-label={title} onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => { e.preventDefault(); onConfirm(value); }}
        className="w-full max-w-sm rounded-md border border-line-strong bg-panel p-4">
        <p className="text-[14px] font-semibold">{title}</p>
        <p className="mt-0.5 text-[13px] text-muted">{task.title}</p>
        <div className="mt-3">
          {action.kind === "assign" && (
            <Field label="Assignee">
              <Select autoFocus required value={value} onChange={(e) => setValue(e.target.value)}>
                <option value="">Choose…</option>
                {agents.filter((a) => a.status === "ACTIVE").map((a) => <option key={a.id} value={a.id}>{a.name} · {a.title}</option>)}
              </Select>
            </Field>
          )}
          {action.kind === "block" && (
            <Field label="Why is it blocked?"><Textarea autoFocus required rows={3} value={value} onChange={(e) => setValue(e.target.value)} /></Field>
          )}
          {action.kind === "cancel" && <p className="text-[13px]">The task leaves the board. Its history is kept.</p>}
        </div>
        <ErrorText error={error} />
        <div className="mt-4 flex justify-end gap-2">
          <Button type="button" variant="subtle" onClick={onCancel}>Back</Button>
          <Button type="submit" variant={action.kind === "cancel" ? "danger" : "primary"} disabled={busy || (action.kind !== "cancel" && !value.trim())}>
            {action.kind === "assign" ? "Assign" : action.kind === "block" ? "Block" : "Cancel task"}
          </Button>
        </div>
      </form>
    </div>
  );
}

function TaskList({ tasks, agents, all }: { tasks: Task[]; agents: Record<string, Agent>; all: Task[] }) {
  const openTask = useSelection((s) => s.openTask);
  const byNumber = byId(all);
  return (
    <Card padded={false}>
      <table className="w-full text-[13px]">
        <thead>
          <tr className="border-b border-line text-left text-[11px] uppercase tracking-wider text-faint">
            <th className="px-4 py-2.5 font-semibold">Task</th><th className="px-3 font-semibold">Status</th>
            <th className="px-3 font-semibold">Assignee</th><th className="px-3 font-semibold">Depends on</th>
            <th className="px-3 font-semibold">Priority</th><th className="px-3 text-right font-semibold">Estimate</th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((t) => {
            const a = t.assigneeId ? agents[t.assigneeId] : undefined;
            return (
              <tr key={t.id} onClick={() => openTask(t.id)} className="cursor-pointer border-b border-line last:border-0 hover:bg-panel-2/60">
                <td className="px-4 py-2.5"><span className="tabular mr-2 text-faint">T-{t.number}</span>{t.title}</td>
                <td className="px-3"><Badge tone={STATUS_TONE[t.status]}>{statusLabel(t.status)}</Badge></td>
                <td className="px-3">{a ? <span className="flex items-center gap-2"><AgentAvatar agent={a} size={20} />{a.name}</span> : <span className="text-faint">—</span>}</td>
                <td className="tabular px-3 text-muted">{t.dependsOn.map((d) => `T-${byNumber[d]?.number}`).join(", ") || "—"}</td>
                <td className="px-3"><Badge tone={PRIORITY_TONE[t.priority]}>{t.priority.toLowerCase()}</Badge></td>
                <td className="tabular px-3 text-right text-muted">{t.estimateDays != null ? `${Number(t.estimateDays)} d` : "—"}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </Card>
  );
}

const DAY = 46;
const ROW = 38;

/** Gantt from the Lead's estimates and the dependency graph (working days from the start of the plan). */
function Timeline({ tasks, agents, highlight }: { tasks: Task[]; agents: Record<string, Agent>; highlight: string }) {
  const openTask = useSelection((s) => s.openTask);
  const plan = schedule(tasks);
  const rows = new Map(plan.map((s, i) => [s.task.id, i]));
  const days = Math.max(5, Math.ceil(Math.max(...plan.map((s) => s.start + s.duration))));
  const width = days * DAY;
  return (
    <Card padded={false} title="Timeline" subtitle="Planned order from the Lead's estimates and the dependencies — working days, not calendar dates.">
      <div className="flex overflow-x-auto">
        <div className="w-72 shrink-0 border-r border-line">
          <div className="h-8 border-b border-line" />
          {plan.map((s) => (
            <button key={s.task.id} onClick={() => openTask(s.task.id)} style={{ height: ROW }}
              className={`flex w-full items-center gap-2 border-b border-line/50 px-4 text-left text-[12px] hover:bg-panel-2/60 ${highlight && s.task.assigneeId !== highlight ? "opacity-40" : ""}`}>
              <span className="tabular text-faint">T-{s.task.number}</span>
              <span className="truncate">{s.task.title}</span>
            </button>
          ))}
        </div>
        <div className="relative" style={{ width, minWidth: width }}>
          <div className="flex h-8 border-b border-line">
            {Array.from({ length: days }, (_, d) => (
              <span key={d} style={{ width: DAY }} className="tabular flex items-center justify-center border-r border-line/50 text-[11px] text-faint">D{d + 1}</span>
            ))}
          </div>
          <svg className="absolute left-0 top-8" width={width} height={plan.length * ROW}>
            {Array.from({ length: days }, (_, d) => <line key={d} x1={d * DAY} x2={d * DAY} y1={0} y2={plan.length * ROW} stroke="var(--line)" strokeOpacity={0.5} />)}
            {plan.flatMap((s) => s.task.dependsOn.filter((d) => rows.has(d)).map((d) => {
              const from = plan[rows.get(d)!];
              const x1 = (from.start + from.duration) * DAY;
              const y1 = rows.get(d)! * ROW + ROW / 2;
              const x2 = s.start * DAY;
              const y2 = rows.get(s.task.id)! * ROW + ROW / 2;
              return <path key={`${d}-${s.task.id}`} d={`M${x1},${y1} C${x1 + 18},${y1} ${x2 - 18},${y2} ${x2},${y2}`} fill="none" stroke="var(--line-strong)" strokeWidth={1.5} markerEnd="url(#arrow)" />;
            }))}
            <defs><marker id="arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="var(--line-strong)" /></marker></defs>
          </svg>
          {plan.map((s, i) => {
            const a = s.task.assigneeId ? agents[s.task.assigneeId] : undefined;
            const color = a ? DEPARTMENT_COLORS[a.department] : "var(--faint)";
            return (
              <button key={s.task.id} onClick={() => openTask(s.task.id)} title={`${s.task.title} · ${a?.name ?? "unassigned"}`}
                className={`absolute flex items-center gap-1.5 overflow-hidden rounded-md px-2 text-[11px] font-medium text-white shadow-sm transition hover:brightness-110 ${highlight && s.task.assigneeId !== highlight ? "opacity-30" : ""} ${s.task.status === "BLOCKED" ? "ring-2 ring-danger" : ""}`}
                style={{ left: s.start * DAY + 2, top: 32 + i * ROW + 7, width: s.duration * DAY - 4, height: ROW - 14, background: `linear-gradient(90deg, ${color}, color-mix(in srgb, ${color} 70%, #1a1530))` }}>
                <span className="truncate">{a?.name ?? "Unassigned"}</span>
              </button>
            );
          })}
        </div>
      </div>
    </Card>
  );
}

/** Dependency graph: one column per dependency level. */
function Graph({ tasks, agents, highlight }: { tasks: Task[]; agents: Record<string, Agent>; highlight: string }) {
  const openTask = useSelection((s) => s.openTask);
  const plan = schedule(tasks);
  const levels = new Map<number, typeof plan>();
  plan.forEach((s) => levels.set(s.level, [...(levels.get(s.level) ?? []), s]));
  const W = 220, H = 64, GX = 80, GY = 18;
  const pos = new Map<string, { x: number; y: number }>();
  [...levels.entries()].forEach(([level, items]) => items.forEach((s, i) => pos.set(s.task.id, { x: level * (W + GX), y: i * (H + GY) })));
  const width = levels.size * (W + GX) - GX + 2;
  const height = Math.max(...[...levels.values()].map((l) => l.length)) * (H + GY);
  return (
    <Card title={<span className="flex items-center gap-2"><GitFork size={14} /> Dependency graph</span>} subtitle="Columns are dependency levels: a task starts only when everything to its left that it points from is done.">
      <div className="overflow-x-auto">
        <div className="relative" style={{ width, height }}>
          <svg className="absolute inset-0" width={width} height={height}>
            <defs><marker id="garrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="var(--line-strong)" /></marker></defs>
            {plan.flatMap((s) => s.task.dependsOn.filter((d) => pos.has(d)).map((d) => {
              const a = pos.get(d)!, b = pos.get(s.task.id)!;
              const x1 = a.x + W, y1 = a.y + H / 2, x2 = b.x, y2 = b.y + H / 2;
              return <path key={`${d}-${s.task.id}`} d={`M${x1},${y1} C${x1 + GX / 2},${y1} ${x2 - GX / 2},${y2} ${x2 - 2},${y2}`} fill="none" stroke="var(--line-strong)" strokeWidth={1.5} markerEnd="url(#garrow)" />;
            }))}
          </svg>
          {plan.map((s) => {
            const p = pos.get(s.task.id)!;
            const a = s.task.assigneeId ? agents[s.task.assigneeId] : undefined;
            return (
              <button key={s.task.id} onClick={() => openTask(s.task.id)} style={{ left: p.x, top: p.y, width: W, height: H }}
                className={`absolute flex flex-col justify-between rounded-md border bg-panel-solid px-3 py-2 text-left transition hover:border-line-strong ${s.task.status === "BLOCKED" ? "border-danger/60" : "border-line"} ${highlight && s.task.assigneeId !== highlight ? "opacity-35" : ""}`}>
                <span className="flex items-center gap-1.5 text-[11px]">
                  <span className="tabular font-semibold text-faint">T-{s.task.number}</span>
                  <Badge tone={STATUS_TONE[s.task.status]}>{statusLabel(s.task.status)}</Badge>
                </span>
                <span className="flex items-center gap-1.5">
                  {a && <AgentAvatar agent={a} size={16} />}
                  <span className="truncate text-[12px] font-medium">{s.task.title}</span>
                </span>
              </button>
            );
          })}
        </div>
      </div>
    </Card>
  );
}

function NewTask({ projectId, agents, tasks, onDone }: { projectId: string; agents: Agent[]; tasks: Task[]; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [criteria, setCriteria] = useState("");
  const [priority, setPriority] = useState<Priority>("MEDIUM");
  const [estimate, setEstimate] = useState("");
  const [assigneeId, setAssigneeId] = useState("");
  const [deps, setDeps] = useState<string[]>([]);
  const create = useMutation({
    mutationFn: () => api.post<Task>(`/api/v1/projects/${projectId}/tasks`, {
      title, description, priority, assigneeId: assigneeId || null, dependsOn: deps,
      acceptanceCriteria: criteria.split("\n").map((s) => s.trim()).filter(Boolean),
      estimateDays: estimate ? Number(estimate) : null,
    }),
    onSuccess: (t) => {
      queryClient.invalidateQueries({ queryKey: ["tasks", projectId] });
      onDone();
      useSelection.getState().openTask(t.id);
    },
  });
  return (
    <Card className="mb-5" title="New task" subtitle="Mode B: you plan and assign yourself"
      action={<button onClick={onDone} className="rounded-lg p-1 text-muted hover:bg-panel-2" aria-label="Close"><X size={16} /></button>}>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-4">
          <Field label="Title"><Input autoFocus maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Create the accounts service" /></Field>
          <Field label="Description"><Textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
          <Field label="Acceptance criteria" hint="One per line; objective and verifiable."><Textarea rows={3} value={criteria} onChange={(e) => setCriteria(e.target.value)} /></Field>
        </div>
        <div className="space-y-4">
          <div className="grid grid-cols-3 gap-3">
            <Field label="Assignee">
              <Select value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)}>
                <option value="">Unassigned</option>
                {agents.filter((a) => a.status !== "DISMISSED").map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </Select>
            </Field>
            <Field label="Priority">
              <Select value={priority} onChange={(e) => setPriority(e.target.value as Priority)}>
                {(["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const).map((p) => <option key={p} value={p}>{p.toLowerCase()}</option>)}
              </Select>
            </Field>
            <Field label="Estimate (days)"><Input type="number" min={0} max={100} step={0.5} value={estimate} onChange={(e) => setEstimate(e.target.value)} /></Field>
          </div>
          <Field label="Depends on">
            <div className="max-h-40 space-y-1 overflow-y-auto rounded-lg border border-line bg-panel-2 p-2">
              {tasks.filter((t) => t.status !== "CANCELLED").length === 0 && <p className="text-[12px] text-faint">No other task.</p>}
              {tasks.filter((t) => t.status !== "CANCELLED").map((t) => (
                <label key={t.id} className="flex items-center gap-2 text-[12px]">
                  <input type="checkbox" className="accent-[var(--accent)]" checked={deps.includes(t.id)}
                    onChange={(e) => setDeps(e.target.checked ? [...deps, t.id] : deps.filter((d) => d !== t.id))} />
                  <span className="tabular text-faint">T-{t.number}</span> {t.title}
                </label>
              ))}
            </div>
          </Field>
          <ErrorText error={create.error} />
          <Button disabled={!title.trim() || create.isPending} onClick={() => create.mutate()}><ListChecks size={14} /> Create task</Button>
        </div>
      </div>
    </Card>
  );
}

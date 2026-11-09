import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Pause, Play, RotateCcw, Send, UserMinus, X } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "../api/client";
import type { Agent, AgentMessage, AgentStep, Department, Provider } from "../api/types";
import { useOpenJobs, useTasks } from "../lib/queries";
import { STATUS_TONE, statusLabel } from "../lib/tasks";
import { useSelection } from "../lib/selection";
import { relative, time, tokens, usd } from "../lib/format";
import { useProviderModels } from "../lib/providers";
import { AgentAvatar, DEPARTMENT_COLORS, DEPARTMENT_LABELS, StateDot } from "./AgentAvatar";
import { Badge, Button, ErrorText, Field, Input, Select, Tabs, Textarea } from "./ui";

/** Global agent drawer (docs/03-ux-ui.md §5), opened from any space. */
export function AgentDrawer({ workspaceId }: { workspaceId: string }) {
  const { agentId, close } = useSelection();
  const agent = useQuery({
    queryKey: ["agent", agentId],
    queryFn: () => api.get<Agent>(`/api/v1/agents/${agentId}`),
    enabled: !!agentId,
  });
  const [tab, setTab] = useState<"work" | "chat" | "profile" | "settings">("work");

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [close]);

  if (!agentId) return null;
  const a = agent.data;
  return (
    <aside className="fixed inset-y-0 right-0 z-40 flex w-full max-w-[520px] flex-col slide-in border-l border-line-strong bg-panel shadow-panel" aria-label="Agent">
      {a && (
        <>
          <header className="flex items-start gap-3 border-b border-line p-5">
            <AgentAvatar agent={a} size={48} />
            <div className="min-w-0 flex-1">
              <p className="truncate text-[16px] font-semibold tracking-tight">{a.name}</p>
              <p className="mt-0.5 truncate text-[12px] text-muted">
                {a.title} · <span style={{ color: DEPARTMENT_COLORS[a.department] }}>{DEPARTMENT_LABELS[a.department]}</span>
              </p>
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <StateDot agent={a} />
                {a.model ? <Badge className="font-mono">{a.model}</Badge> : <Badge tone="warn">No model</Badge>}
              </div>
            </div>
            <button onClick={close} className="rounded-lg p-1.5 text-muted hover:bg-panel-2 hover:text-fg" aria-label="Close">
              <X size={18} />
            </button>
          </header>
          <div className="border-b border-line px-5 py-2.5">
            <Tabs value={tab} onChange={setTab} items={[
              { value: "work", label: "Work" },
              { value: "chat", label: "Conversation" },
              { value: "profile", label: "Profile" },
              { value: "settings", label: "Settings" },
            ]} />
          </div>
          {a.activity === "BLOCKED" && <Blocked agent={a} />}
          {tab === "work" && <Work agent={a} />}
          {tab === "chat" && <Chat agent={a} />}
          {tab === "profile" && <Profile agent={a} />}
          {tab === "settings" && <AgentSettings agent={a} workspaceId={workspaceId} />}
        </>
      )}
    </aside>
  );
}

function Chat({ agent }: { agent: Agent }) {
  const queryClient = useQueryClient();
  const messages = useQuery({
    queryKey: ["messages", agent.id],
    queryFn: () => api.get<AgentMessage[]>(`/api/v1/agents/${agent.id}/messages`),
  });
  const [draft, setDraft] = useState("");
  const send = useMutation({
    mutationFn: (content: string) => api.post(`/api/v1/agents/${agent.id}/messages`, { content }),
    onSuccess: () => {
      setDraft("");
      queryClient.invalidateQueries({ queryKey: ["messages", agent.id] });
    },
  });
  const bottom = useRef<HTMLDivElement>(null);
  useEffect(() => {
    bottom.current?.scrollIntoView({ block: "end" });
  }, [messages.data?.length, agent.activity]);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (draft.trim()) send.mutate(draft);
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-5">
        {messages.data?.length === 0 && (
          <p className="text-[13px] leading-relaxed text-muted">
            Start the conversation. {agent.lead ? "Your Lead has the project's specifications in context." : ""}
          </p>
        )}
        {messages.data?.map((m) => (
          <div key={m.id} className={`flex ${m.authorKind === "HUMAN" ? "justify-end" : "justify-start"}`}>
            <div className={`max-w-[88%] rounded-md px-4 py-2.5 text-[13px] ${m.authorKind === "HUMAN" ? "rounded-br-md bg-ink text-ink-fg" : "rounded-bl-md border border-line bg-panel-2"}`}>
              <p className="whitespace-pre-wrap break-words leading-relaxed">{m.content}</p>
              <p className={`mt-1 text-[11px] ${m.authorKind === "HUMAN" ? "text-ink-fg/70" : "text-faint"}`}>
                {time(m.createdAt)}{m.status === "PENDING" && " · waiting for the agent"}
              </p>
              {m.status === "FAILED" && <p className="mt-1.5 rounded-md bg-black/20 px-2 py-1 text-[12px] text-red-100">Not processed: {m.error}</p>}
            </div>
          </div>
        ))}
        {agent.status === "ACTIVE" && agent.activity === "THINKING" && (
          <div className="flex items-center gap-2 text-[12px] text-active">
            <span className="flex gap-1">{[0, 1, 2].map((i) => <span key={i} className="aiw-pulse h-1.5 w-1.5 rounded-full bg-active" style={{ animationDelay: `${i * 0.2}s` }} />)}</span>
            {agent.name} is thinking
          </div>
        )}
        <div ref={bottom} />
      </div>
      <form onSubmit={submit} className="border-t border-line p-4">
        <div className="relative">
          <Textarea rows={3} value={draft} placeholder={`Message ${agent.name}…`} className="pr-12"
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) submit(e); }} />
          <button type="submit" aria-label="Send" disabled={send.isPending || !draft.trim() || agent.status !== "ACTIVE"}
            className="absolute bottom-2.5 right-2.5 flex h-8 w-8 items-center justify-center rounded-lg bg-accent text-white disabled:opacity-30">
            <Send size={15} />
          </button>
        </div>
        <p className="mt-1.5 text-[11px] text-faint">Ctrl+Enter to send · each reply is a real, billed model call</p>
        <ErrorText error={send.error} />
      </form>
    </div>
  );
}

function Blocked({ agent }: { agent: Agent }) {
  const queryClient = useQueryClient();
  const jobs = useOpenJobs(agent.projectId);
  const failed = jobs.data?.find((j) => j.agentId === agent.id && j.status === "FAILED");
  const retry = useMutation({
    mutationFn: (id: string) => api.post(`/api/v1/jobs/${id}/retry`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["jobs", agent.projectId] });
      queryClient.invalidateQueries({ queryKey: ["agent", agent.id] });
    },
  });
  return (
    <div className="flex items-start gap-3 border-b border-danger/30 bg-danger/5 px-5 py-3">
      <AlertTriangle size={16} className="mt-0.5 shrink-0 text-danger" />
      <div className="min-w-0 flex-1 text-[12px]">
        <p className="font-semibold text-danger">Blocked</p>
        <p className="mt-0.5 text-muted">{agent.blockedReason}</p>
        <ErrorText error={retry.error} />
      </div>
      {failed && <Button size="sm" variant="ghost" disabled={retry.isPending} onClick={() => retry.mutate(failed.id)}><RotateCcw size={13} /> Retry</Button>}
    </div>
  );
}

function Work({ agent }: { agent: Agent }) {
  const tasks = useTasks(agent.projectId);
  const openTask = useSelection((s) => s.openTask);
  const steps = useQuery({
    queryKey: ["steps", agent.id],
    queryFn: () => api.get<AgentStep[]>(`/api/v1/agents/${agent.id}/steps?limit=30`),
  });
  const mine = (tasks.data ?? []).filter((t) => t.assigneeId === agent.id && t.status !== "CANCELLED");
  return (
    <div className="min-h-0 flex-1 space-y-6 overflow-y-auto p-5">
      <section>
        <p className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-faint">Mission · {mine.length} task{mine.length === 1 ? "" : "s"}</p>
        {mine.length === 0 && <p className="text-[13px] text-muted">No task assigned.</p>}
        <ul className="space-y-1.5">
          {mine.map((t) => (
            <li key={t.id}>
              <button onClick={() => openTask(t.id)} className="flex w-full items-center gap-2.5 rounded-lg border border-line bg-panel-2 px-3 py-2 text-left hover:border-line-strong">
                <span className="tabular text-[11px] font-semibold text-faint">T-{t.number}</span>
                <span className="min-w-0 flex-1 truncate text-[13px]">{t.title}</span>
                <Badge tone={STATUS_TONE[t.status]}>{statusLabel(t.status)}</Badge>
              </button>
            </li>
          ))}
        </ul>
      </section>
      <section>
        <p className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-faint">Recorded steps</p>
        {steps.data?.length === 0 && <p className="text-[13px] text-muted">No structured work recorded yet.</p>}
        <ol className="space-y-2">
          {steps.data?.map((s) => (
            <li key={s.id} className="flex gap-3 text-[12px]">
              <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${s.outcome === "OK" ? "bg-ok" : "bg-danger"}`} />
              <div className="min-w-0 flex-1">
                <p className="text-[13px]">{s.summary}</p>
                <p className="tabular text-faint">
                  {relative(s.createdAt)} · {s.model} · {tokens(s.inputTokens)} in / {tokens(s.outputTokens)} out · {(s.durationMs / 1000).toFixed(1)} s
                  {s.costUsd != null && ` · ${usd(s.costUsd)}`}
                </p>
                {s.error && <p className="text-danger">{s.error}</p>}
              </div>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}

function Profile({ agent }: { agent: Agent }) {
  return (
    <div className="space-y-6 overflow-y-auto p-5 text-[13px]">
      <dl className="grid grid-cols-2 gap-4">
        <div><dt className="text-[12px] text-muted">Level</dt><dd className="mt-0.5 font-medium capitalize">{agent.seniority.toLowerCase()}</dd></div>
        <div>
          <dt className="text-[12px] text-muted">Simulated experience</dt>
          <dd className="mt-0.5 font-medium" title="A role-play profile element, not the real experience of the AI model">{agent.simulatedYears} years <span className="text-faint">ⓘ</span></dd>
        </div>
      </dl>
      <div>
        <p className="mb-2 text-[12px] text-muted">Skills</p>
        <div className="flex flex-wrap gap-1.5">{agent.skills.map((s) => <Badge key={s} tone="accent">{s}</Badge>)}</div>
      </div>
      <div>
        <p className="mb-2 text-[12px] text-muted">Responsibilities</p>
        <ul className="space-y-1.5">{agent.responsibilities.map((r) => <li key={r} className="flex gap-2"><span className="text-accent-2">—</span>{r}</li>)}</ul>
      </div>
    </div>
  );
}

function AgentSettings({ agent, workspaceId }: { agent: Agent; workspaceId: string }) {
  const queryClient = useQueryClient();
  const providers = useQuery({ queryKey: ["providers", workspaceId], queryFn: () => api.get<Provider[]>(`/api/v1/workspaces/${workspaceId}/providers`) });
  const [providerId, setProviderId] = useState(agent.providerId ?? "");
  const [model, setModel] = useState(agent.model ?? "");
  const providerModels = useProviderModels(agent.workspaceId, providerId || null);
  const [instructions, setInstructions] = useState(agent.systemInstructions);
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["agent", agent.id] });
    queryClient.invalidateQueries({ queryKey: ["agents", agent.projectId] });
  };
  const bind = useMutation({ mutationFn: () => api.put(`/api/v1/agents/${agent.id}/model`, { providerId, model }), onSuccess: refresh });
  const save = useMutation({ mutationFn: () => api.put(`/api/v1/agents/${agent.id}/instructions`, { systemInstructions: instructions }), onSuccess: refresh });
  const status = useMutation({ mutationFn: (s: string) => api.put(`/api/v1/agents/${agent.id}/status`, { status: s }), onSuccess: refresh });
  const dept = useMutation({ mutationFn: (d: Department) => api.put(`/api/v1/agents/${agent.id}/department`, { department: d }), onSuccess: refresh });
  const [confirmDismiss, setConfirmDismiss] = useState(false);

  return (
    <div className="space-y-6 overflow-y-auto p-5">
      <section className="space-y-3">
        <h3 className="text-[13px] font-semibold">Model</h3>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Provider">
            <Select value={providerId} onChange={(e) => setProviderId(e.target.value)}>
              <option value="">Choose…</option>
              {providers.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </Select>
          </Field>
          <Field label="Model id">
            <Input list={`agent-models-${agent.id}`} value={model} onChange={(e) => setModel(e.target.value)}
              placeholder={providers.data?.find((p) => p.id === providerId)?.defaultModel ?? "model id"} />
            <datalist id={`agent-models-${agent.id}`}>{providerModels.data?.map((m) => <option key={m} value={m} />)}</datalist>
          </Field>
        </div>
        <Button variant="ghost" onClick={() => bind.mutate()} disabled={!providerId || !model || bind.isPending}>Apply model</Button>
        <ErrorText error={bind.error} />
      </section>
      <section className="space-y-3">
        <h3 className="text-[13px] font-semibold">Department</h3>
        <Select value={agent.department} onChange={(e) => dept.mutate(e.target.value as Department)} disabled={agent.lead}>
          {(Object.keys(DEPARTMENT_LABELS) as Department[]).map((d) => <option key={d} value={d}>{DEPARTMENT_LABELS[d]}</option>)}
        </Select>
        <ErrorText error={dept.error} />
      </section>
      <section className="space-y-3">
        <Field label="System instructions" hint="Edits are recorded in the audit log.">
          <Textarea rows={12} className="font-mono text-[12px]" value={instructions} onChange={(e) => setInstructions(e.target.value)} />
        </Field>
        <Button variant="ghost" onClick={() => save.mutate()} disabled={save.isPending || instructions === agent.systemInstructions}>Save instructions</Button>
        <ErrorText error={save.error} />
      </section>
      <section className="flex flex-wrap gap-2 border-t border-line pt-5">
        {agent.status === "ACTIVE"
          ? <Button variant="ghost" onClick={() => status.mutate("PAUSED")}><Pause size={14} /> Pause</Button>
          : <Button variant="ghost" onClick={() => status.mutate("ACTIVE")}><Play size={14} /> Resume</Button>}
        {!agent.lead && (confirmDismiss
          ? <>
              <Button variant="danger" onClick={() => status.mutate("DISMISSED")}>Confirm: dismiss {agent.name}</Button>
              <Button variant="subtle" onClick={() => setConfirmDismiss(false)}>Cancel</Button>
            </>
          : <Button variant="danger" onClick={() => setConfirmDismiss(true)}><UserMinus size={14} /> Dismiss</Button>)}
        <ErrorText error={status.error} />
      </section>
    </div>
  );
}

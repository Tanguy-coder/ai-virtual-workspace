import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, GitPullRequest, Pause, Play, Send } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { api } from "../../api/client";
import type { RunStep } from "../../api/types";
import { AgentAvatar } from "../../components/AgentAvatar";
import { Badge, Button, Card, Dot, ErrorText, SectionTitle, SkeletonRows, Textarea } from "../../components/ui";
import { GATES, MR_LABEL, MR_TONE, RUN_TONE, useMergeRequests, useRun } from "../../lib/engineering";
import { time, tokens, usd } from "../../lib/format";
import { byId, useAgents, useTasks } from "../../lib/queries";
import { useSelection } from "../../lib/selection";

const KIND: Record<RunStep["kind"], { label: string; cls: string }> = {
  MODEL: { label: "M", cls: "bg-active" },
  TOOL: { label: "T", cls: "bg-faint" },
  PLATFORM: { label: "P", cls: "bg-testing" },
  MESSAGE: { label: "✉", cls: "bg-warn" },
};

/** Watching an agent work on one task (docs/10, view 1): every recorded step, the budget, the criteria, the gates. */
export function RunPage() {
  const { workspaceId = "", projectId = "", taskId = "" } = useParams();
  const run = useRun(taskId);
  const tasks = useTasks(projectId);
  const agents = useAgents(projectId);
  const mrs = useMergeRequests(projectId);
  const queryClient = useQueryClient();
  const openAgent = useSelection((s) => s.open);
  const [draft, setDraft] = useState("");
  const task = tasks.data?.find((t) => t.id === taskId);
  const r = run.data?.run;
  const agent = r ? byId(agents.data)[r.agentId] : undefined;
  const mr = mrs.data?.find((m) => m.taskId === taskId && m.status !== "CLOSED");
  const act = useMutation({
    mutationFn: ({ path, body }: { path: string; body?: unknown }) => api.post(`/api/v1/tasks/${taskId}/run${path}`, body ?? {}),
    onSuccess: () => {
      setDraft("");
      queryClient.invalidateQueries({ queryKey: ["run", taskId] });
    },
  });

  if (run.isLoading || tasks.isLoading) return <SkeletonRows rows={8} />;
  const steps = run.data?.steps ?? [];
  const used = r ? r.inputTokens + r.outputTokens : 0;
  const pct = (n: number) => (r ? Math.min(100, (n / r.tokenBudget) * 100) : 0);

  return (
    <div className="grid gap-5 lg:grid-cols-[1fr_340px]">
      <div className="min-w-0">
        <Link to={`/w/${workspaceId}/p/${projectId}/runs`} className="mb-3 inline-flex items-center gap-1 text-[12px] text-muted hover:text-fg"><ArrowLeft size={13} /> All work</Link>
        <p className="tabular text-[12px] font-semibold text-faint">T-{task?.number}</p>
        <h1 className="text-[20px] font-semibold tracking-tight">{task?.title ?? "Task"}</h1>
        {!r && <p className="mt-3 text-[13px] text-muted">No work has started on this task. It starts by itself once the task is assigned and its agent is free.</p>}
        {r && (
          <>
            <div className="mt-2 flex flex-wrap items-center gap-1.5">
              <Badge tone={RUN_TONE[r.status]} dot>{r.status === "SUBMITTED" ? "Submitted" : r.status.charAt(0) + r.status.slice(1).toLowerCase()}{r.status === "RUNNING" ? ` · step ${r.steps}` : ""}</Badge>
              {agent && <button onClick={() => openAgent(agent.id)} className="inline-flex items-center gap-1.5 rounded-full bg-panel-2 py-0.5 pl-0.5 pr-2 text-[12px] hover:bg-line"><AgentAvatar agent={agent} size={18} />{agent.name}</button>}
              <Badge className="font-mono">{r.branch}</Badge>
              {r.image && <Badge className="font-mono">{r.image} · network off</Badge>}
              {agent?.model && <Badge className="font-mono">{agent.model}</Badge>}
              <span className="ml-auto flex gap-1.5">
                {r.status === "RUNNING" && <Button variant="ghost" size="sm" disabled={r.pauseRequested || act.isPending} onClick={() => act.mutate({ path: "/pause" })}><Pause size={13} /> {r.pauseRequested ? "Pausing…" : "Pause"}</Button>}
                {(r.status === "PAUSED" || r.status === "BLOCKED") && <Button variant="ghost" size="sm" disabled={act.isPending} onClick={() => act.mutate({ path: "/resume" })}><Play size={13} /> Resume</Button>}
              </span>
            </div>
            {r.blockedReason && <p className="mt-3 rounded-md border border-danger/30 bg-danger/5 px-3 py-2 text-[13px] text-danger">Blocked: {r.blockedReason}</p>}
            <ErrorText error={act.error} />

            <Card className="mt-4" title="Token budget for this task" action={<span className="font-mono text-[12px] text-muted">{tokens(used)} / {tokens(r.tokenBudget)} · {usd(r.costUsd)}</span>}>
              <div className="flex h-1.5 overflow-hidden rounded-full bg-panel-2">
                <i className="block bg-testing" style={{ width: `${pct(r.cacheReadTokens)}%` }} />
                <i className="block bg-active" style={{ width: `${pct(r.inputTokens)}%` }} />
                <i className="block bg-review" style={{ width: `${pct(r.outputTokens)}%` }} />
              </div>
              <p className="mt-2 flex flex-wrap gap-x-4 text-[12px] text-muted">
                <span><Dot tone="testing" /> read from cache {tokens(r.cacheReadTokens)}</span>
                <span><Dot tone="active" /> input {tokens(r.inputTokens)}</span>
                <span><Dot tone="review" /> output {tokens(r.outputTokens)}</span>
                <span>{r.steps} model steps</span>
              </p>
            </Card>

            <SectionTitle count={steps.length}>Recorded steps</SectionTitle>
            <ol className="divide-y divide-line">
              {steps.map((s, i) => <Step key={s.id} step={s} live={i === steps.length - 1 && r.status === "RUNNING"} />)}
            </ol>

            {r.status !== "DONE" && r.status !== "CANCELLED" && (
              <form className="mt-4 flex items-end gap-2" onSubmit={(e) => { e.preventDefault(); act.mutate({ path: "/messages", body: { content: draft } }); }}>
                <Textarea rows={2} value={draft} onChange={(e) => setDraft(e.target.value)} placeholder={`Message ${agent?.name ?? "the agent"}: read at its next step`} />
                <Button type="submit" disabled={!draft.trim() || act.isPending} aria-label="Send"><Send size={14} /></Button>
              </form>
            )}
          </>
        )}
      </div>

      <aside className="space-y-4">
        <Card title="Acceptance criteria" action={<span className="text-[12px] text-muted">{task?.acceptanceCriteria.filter((c) => c.met).length ?? 0} of {task?.acceptanceCriteria.length ?? 0} with evidence</span>}>
          <ul className="space-y-2.5">
            {task?.acceptanceCriteria.map((c, i) => (
              <li key={i} className="grid grid-cols-[16px_1fr] gap-x-2 text-[13px]">
                <span className={`mt-1 h-3 w-3 rounded-full ${c.met ? "bg-ok" : "border border-line-strong"}`} />
                <span>{c.text}</span>
                <span className="col-start-2 text-[12px] text-faint">{c.met ? c.evidence : "no evidence yet"}</span>
              </li>
            ))}
          </ul>
        </Card>
        <Card title="Validation engine" action={<span className="text-[11px] text-faint">closes the task, never the agent</span>}>
          <ul className="divide-y divide-line">
            {GATES.map((g) => {
              const gate = mr?.checks?.[g.key];
              return (
                <li key={g.key} className="flex items-center gap-2 py-1.5 text-[13px]">
                  <Dot tone={gate ? (gate.status === "ok" ? "ok" : "danger") : "muted"} />{g.label}
                  <span className="ml-auto text-[12px] text-faint">{gate?.detail ?? "pending"}</span>
                </li>
              );
            })}
          </ul>
          {mr && (
            <Link to={`/w/${workspaceId}/p/${projectId}/merge-requests/${mr.id}`} className="mt-3 flex items-center gap-2 text-[13px] hover:underline">
              <GitPullRequest size={14} /> !{mr.number} · <Badge tone={MR_TONE[mr.status]}>{MR_LABEL[mr.status]}</Badge>
            </Link>
          )}
        </Card>
        <Card title="Guardrails">
          <p className="text-[12px] leading-relaxed text-muted">
            Writes stay on the task's branch; CI files, Dockerfiles and .env need your approval. Commands run in a container without
            network (package installs excepted). 40 steps without submitting, the token budget, or the same failing call three times
            stop the work and tell the Lead.
          </p>
        </Card>
      </aside>
    </div>
  );
}

function Step({ step, live }: { step: RunStep; live: boolean }) {
  const [open, setOpen] = useState(false);
  const k = KIND[step.kind];
  const d = step.detail as Record<string, unknown>;
  const hasDetail = d.out || d.text || d.old || d.excerpt || d.output || d.args;
  return (
    <li className="py-2">
      <button className="grid w-full grid-cols-[56px_18px_1fr_auto] items-start gap-x-2.5 text-left" onClick={() => hasDetail && setOpen(!open)} aria-expanded={open}>
        <span className="font-mono text-[11px] leading-5 text-faint">{time(step.createdAt)}</span>
        <span className={`mt-0.5 flex h-4 w-4 items-center justify-center rounded text-[9px] font-semibold text-white ${step.outcome === "OK" ? k.cls : "bg-danger"}`}>{k.label}</span>
        <span className="min-w-0">
          <span className={`block text-[13px] ${step.kind === "MODEL" ? "italic text-muted" : ""}`}>
            {step.tool && <code className="mr-1.5 rounded bg-panel-2 px-1 text-[12px]">{step.tool}</code>}
            {step.tool ? step.summary.replace(step.tool, "").trim() : step.summary}
            {live && <span className="ml-1 text-accent">▍</span>}
          </span>
        </span>
        <span className="font-mono text-[11px] leading-5 text-faint">
          {step.inputTokens + step.outputTokens > 0 ? `${tokens(step.inputTokens + step.outputTokens)} tok` : step.durationMs > 0 ? `${(step.durationMs / 1000).toFixed(1)} s` : ""}
        </span>
      </button>
      {open && (
        <div className="ml-[84px] mt-1.5 space-y-1.5">
          {typeof d.old === "string" && <pre className="overflow-x-auto rounded border border-line bg-panel px-2 py-1 font-mono text-[12px]"><span className="block bg-danger/10">{String(d.old).split("\n").map((l) => "− " + l).join("\n")}</span><span className="block bg-ok/10">{String(d.new ?? "").split("\n").map((l) => "+ " + l).join("\n")}</span></pre>}
          {typeof d.excerpt === "string" && <pre className="max-h-64 overflow-auto rounded border border-line bg-panel px-2 py-1 font-mono text-[12px]">{d.excerpt}</pre>}
          {typeof d.text === "string" && <pre className="whitespace-pre-wrap rounded border border-line bg-panel px-2 py-1 text-[12px]">{d.text}</pre>}
          {typeof d.out === "string" && d.out && !d.excerpt && !d.old && <pre className="max-h-64 overflow-auto rounded border border-line bg-panel-2 px-2 py-1 font-mono text-[11px] text-muted">{d.out}</pre>}
        </div>
      )}
    </li>
  );
}

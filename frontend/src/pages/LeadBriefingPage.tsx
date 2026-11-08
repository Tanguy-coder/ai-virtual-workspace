import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, ArrowRight, Check, FileText, Lightbulb, MessagesSquare, RotateCcw, Sparkles } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { api } from "../api/client";
import type { Agent, Briefing, BriefingDetail, Meeting, ProjectDocument } from "../api/types";
import { AgentAvatar, StateDot } from "../components/AgentAvatar";
import { Markdown } from "../components/Markdown";
import { ProposalReview } from "../components/ProposalReview";
import { Badge, Button, Card, Empty, ErrorText, PageHeader, Textarea } from "../components/ui";
import { relative } from "../lib/format";
import { useAgents, useOpenJobs, useTasks } from "../lib/queries";

const STEPS = ["Analysis", "Questions", "Architecture & team", "Hiring", "Kickoff", "Plan"] as const;

/** UX §7 Lead Briefing: specification on the left, the Lead's analysis, questions and proposal on the right. */
export function LeadBriefingPage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const queryClient = useQueryClient();
  const briefings = useQuery({
    queryKey: ["briefings", projectId],
    queryFn: () => api.get<Briefing[]>(`/api/v1/projects/${projectId}/lead/briefings`),
  });
  const current = briefings.data?.[0];
  const detail = useQuery({
    queryKey: ["briefing", current?.id],
    queryFn: () => api.get<BriefingDetail>(`/api/v1/briefings/${current!.id}`),
    enabled: !!current,
  });
  const agents = useAgents(projectId);
  const lead = agents.data?.find((a) => a.lead);
  const documents = useQuery({
    queryKey: ["documents", projectId],
    queryFn: () => api.get<ProjectDocument[]>(`/api/v1/projects/${projectId}/documents`),
  });
  const specs = documents.data?.filter((d) => d.kind === "SPECIFICATION") ?? [];
  const meetings = useQuery({
    queryKey: ["meetings", projectId],
    queryFn: () => api.get<Meeting[]>(`/api/v1/projects/${projectId}/meetings`),
  });
  const tasks = useTasks(projectId);
  const start = useMutation({
    mutationFn: () => api.post<Briefing>(`/api/v1/projects/${projectId}/lead/briefings`, {}),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["briefings", projectId] }),
  });

  const b = detail.data?.briefing ?? current;
  const kickoff = meetings.data?.find((m) => m.purpose === "KICKOFF");
  const planned = (tasks.data ?? []).filter((t) => t.sourceKind === "PLAN").length;
  const canStart = !b || ["APPROVED", "FAILED", "CANCELLED"].includes(b.status);
  const base = `/w/${workspaceId}/p/${projectId}`;

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader title="Lead Briefing"
        subtitle="Your Lead analyzes the specification, asks what it needs, then proposes an architecture and a justified team."
        action={canStart && (
          <Button disabled={specs.length === 0 || start.isPending} onClick={() => start.mutate()}>
            <Sparkles size={14} /> {b ? "Brief the Lead again" : "Brief your Lead"}
          </Button>
        )} />
      <ErrorText error={start.error} />

      {b && <Stepper briefing={b} kickoff={kickoff} planned={planned} />}

      <div className="mt-5 grid gap-5 lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]">
        <div className="lg:sticky lg:top-0 lg:self-start">
          <SpecificationPanel projectId={projectId} documents={specs} base={base} />
        </div>
        <div className="min-w-0 space-y-5">
          {!b && (
            <Empty icon={<Sparkles size={18} />} title="Your Lead is waiting for a brief">
              {specs.length === 0
                ? <>Import a specification in <Link className="font-medium underline" to={`${base}/documents`}>Documents</Link> first.</>
                : "Brief the Lead: it reads the specification, lists requirements and risks, and asks you only what the document does not answer."}
            </Empty>
          )}
          {b && lead && <LeadStatus briefing={b} lead={lead} projectId={projectId} />}
          {b?.analysis && <AnalysisCard briefing={b} />}
          {b?.status === "AWAITING_ANSWERS" && <QuestionsForm briefing={b} />}
          {b && b.status !== "AWAITING_ANSWERS" && b.questions.some((q) => q.answer) && <AnswersCard briefing={b} />}
          {b?.architecture && (
            <Card title="Architecture note" subtitle={b.status === "APPROVED" ? "Approved · recorded in shared memory" : "Proposed by the Lead"}>
              <Markdown text={b.architecture} />
            </Card>
          )}
          {detail.data?.proposals.map((p, i) => {
            const previous = detail.data.proposals[i + 1];
            if (p.status === "SUPERSEDED") return null;
            return <ProposalReview key={p.id} proposal={p} previous={previous} workspaceId={workspaceId} projectId={projectId} />;
          })}
          {b?.status === "APPROVED" && (
            <Card title="Next" subtitle="What the platform did after your approval">
              <div className="grid gap-3 sm:grid-cols-2">
                <NextLink to={kickoff ? `${base}/meetings/${kickoff.id}` : `${base}/meetings`} icon={<MessagesSquare size={16} />}
                  title={kickoff ? `Kickoff meeting · ${kickoff.status.toLowerCase().replace("_", " ")}` : "No kickoff meeting"}
                  detail={kickoff ? `Started ${relative(kickoff.startedAt)}` : "You chose to skip it"} />
                <NextLink to={`${base}/tasks`} icon={<Check size={16} />} title={planned ? `${planned} planned tasks` : "Plan not created yet"}
                  detail={planned ? "Assigned by the Lead" : "The Lead plans after the kickoff minutes"} />
              </div>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}

function Stepper({ briefing, kickoff, planned }: { briefing: Briefing; kickoff?: Meeting; planned: number }) {
  const s = briefing.status;
  const done = [
    !!briefing.analysis,
    briefing.questions.length === 0 ? !!briefing.analysis : briefing.questions.every((q) => q.answer),
    s === "APPROVED" || s === "PROPOSED",
    s === "APPROVED",
    kickoff?.status === "ENDED",
    planned > 0,
  ];
  const active = done.findIndex((d) => !d);
  return (
    <ol className="flex flex-wrap items-center gap-1.5 rounded-md border border-line bg-panel p-1.5">
      {STEPS.map((label, i) => {
        const state = done[i] ? "done" : i === active ? (s === "FAILED" ? "failed" : "active") : "todo";
        return (
          <li key={label} className={`flex items-center gap-2 rounded-lg px-3 py-1.5 text-[12px] font-medium ${
            state === "active" ? "bg-accent/12 text-accent-2" : state === "failed" ? "bg-danger/10 text-danger" : state === "done" ? "text-fg" : "text-faint"}`}>
            <span className={`flex h-5 w-5 items-center justify-center rounded-full text-[10px] ${
              state === "done" ? "bg-ok/15 text-ok" : state === "active" ? "bg-accent text-white" : state === "failed" ? "bg-danger text-white" : "bg-panel-2"}`}>
              {state === "done" ? <Check size={11} /> : i + 1}
            </span>
            {label}
          </li>
        );
      })}
    </ol>
  );
}

function LeadStatus({ briefing, lead, projectId }: { briefing: Briefing; lead: Agent; projectId: string }) {
  const queryClient = useQueryClient();
  const jobs = useOpenJobs(projectId);
  const failedJob = jobs.data?.find((j) => j.status === "FAILED" && j.payload.briefingId === briefing.id);
  const retry = useMutation({
    mutationFn: (id: string) => api.post(`/api/v1/jobs/${id}/retry`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["jobs", projectId] });
      queryClient.invalidateQueries({ queryKey: ["briefings", projectId] });
    },
  });
  const working = briefing.status === "ANALYZING" || briefing.status === "ARCHITECTING";
  const text: Record<string, string> = {
    ANALYZING: "is reading the specification",
    AWAITING_ANSWERS: "is waiting for your answers",
    ARCHITECTING: "is designing the architecture and the team",
    PROPOSED: "is waiting for your decision on the team",
    APPROVED: "has an approved architecture",
    FAILED: "could not finish this step",
    CANCELLED: "had its proposal rejected",
  };
  return (
    <div className={`flex flex-wrap items-center gap-3 rounded-md border px-4 py-3 ${briefing.status === "FAILED" ? "border-danger/40 bg-danger/5" : "border-line bg-panel"}`}>
      <AgentAvatar agent={lead} size={36} />
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-medium">{lead.name} {text[briefing.status]}</p>
        <div className="mt-0.5 flex items-center gap-3">
          <StateDot agent={lead} />
          {working && lead.activity !== "THINKING" && <span className="text-[12px] text-faint">queued — waiting for a worker or for the agent to be active</span>}
        </div>
        {briefing.error && <p className="mt-1 text-[12px] text-danger">{briefing.error}</p>}
      </div>
      {failedJob && (
        <Button variant="ghost" disabled={retry.isPending} onClick={() => retry.mutate(failedJob.id)}><RotateCcw size={14} /> Retry</Button>
      )}
    </div>
  );
}

function AnalysisCard({ briefing }: { briefing: Briefing }) {
  const a = briefing.analysis!;
  return (
    <Card title="Analysis" subtitle={`By the Lead · ${relative(briefing.createdAt)}`}>
      <p className="text-[13px] leading-relaxed">{a.summary}</p>
      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <List title="Functional" items={a.functional} />
        <List title="Non-functional" items={a.nonFunctional} />
      </div>
      {a.domains.length > 0 && (
        <div className="mt-4">
          <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-faint">Domains</p>
          <div className="flex flex-wrap gap-1.5">{a.domains.map((d) => <Badge key={d} tone="accent">{d}</Badge>)}</div>
        </div>
      )}
      {a.risks.length > 0 && (
        <div className="mt-4">
          <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-faint">Risks</p>
          <ul className="space-y-2">
            {a.risks.map((r, i) => (
              <li key={i} className="flex gap-2.5 rounded-lg border border-line bg-panel-2 p-2.5">
                <AlertTriangle size={14} className={`mt-0.5 shrink-0 ${r.impact === "HIGH" ? "text-danger" : r.impact === "MEDIUM" ? "text-warn" : "text-muted"}`} />
                <div className="text-[12px] leading-relaxed">
                  <p className="font-medium text-fg">{r.risk} <span className="text-faint">· {r.impact.toLowerCase()} impact</span></p>
                  <p className="text-muted">{r.mitigation}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Card>
  );
}

function List({ title, items }: { title: string; items: string[] }) {
  return (
    <div>
      <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-faint">{title}</p>
      <ul className="list-disc space-y-1 pl-4 text-[12px] leading-relaxed text-muted marker:text-faint">
        {items.map((i, k) => <li key={k}>{i}</li>)}
      </ul>
    </div>
  );
}

function QuestionsForm({ briefing }: { briefing: Briefing }) {
  const queryClient = useQueryClient();
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const submit = useMutation({
    mutationFn: () => api.put(`/api/v1/briefings/${briefing.id}/answers`, { answers }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["briefing"] }),
  });
  const complete = briefing.questions.every((q) => answers[q.id]?.trim());
  return (
    <section className="rounded-md border border-line-strong bg-panel">
      <header className="flex items-center gap-2 border-b border-line px-5 py-3.5">
        <Lightbulb size={15} className="text-faint" />
        <h2 className="text-[13px] font-semibold">The Lead needs your answers</h2>
        <Badge tone="accent">{briefing.questions.length}</Badge>
      </header>
      <div className="space-y-4 p-5">
        {briefing.questions.map((q, i) => (
          <div key={q.id}>
            <p className="text-[13px] font-medium"><span className="tabular mr-1.5 text-faint">{i + 1}.</span>{q.text}</p>
            <p className="mb-1.5 mt-0.5 text-[12px] text-faint">{q.why}</p>
            <Textarea rows={2} value={answers[q.id] ?? ""} placeholder="Your answer (or “no preference”)"
              onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })} />
          </div>
        ))}
        <ErrorText error={submit.error} />
        <Button disabled={!complete || submit.isPending} onClick={() => submit.mutate()}>
          Send answers to the Lead <ArrowRight size={14} />
        </Button>
      </div>
    </section>
  );
}

function AnswersCard({ briefing }: { briefing: Briefing }) {
  return (
    <Card title="Your answers">
      <dl className="space-y-3">
        {briefing.questions.map((q) => (
          <div key={q.id}>
            <dt className="text-[12px] text-muted">{q.text}</dt>
            <dd className="mt-0.5 text-[13px]">{q.answer}</dd>
          </div>
        ))}
      </dl>
    </Card>
  );
}

function SpecificationPanel({ projectId, documents, base }: { projectId: string; documents: ProjectDocument[]; base: string }) {
  const [index, setIndex] = useState(0);
  const doc = documents[Math.min(index, documents.length - 1)];
  const content = useQuery({
    queryKey: ["document", doc?.id],
    queryFn: () => api.get<ProjectDocument>(`/api/v1/projects/${projectId}/documents/${doc!.id}`),
    enabled: !!doc,
  });
  return (
    <Card padded={false} title={<span className="flex items-center gap-2"><FileText size={14} /> Specification</span>}
      action={documents.length > 1 && (
        <select className="rounded-md border border-line bg-panel-2 px-2 py-1 text-[12px]" value={index} onChange={(e) => setIndex(Number(e.target.value))}>
          {documents.map((d, i) => <option key={d.id} value={i}>{d.title}</option>)}
        </select>
      )}>
      {doc ? (
        <div className="max-h-[calc(100vh-260px)] overflow-y-auto px-5 py-4">
          {content.data?.content ? <Markdown text={content.data.content} /> : <p className="text-[12px] text-faint">Loading…</p>}
        </div>
      ) : (
        <p className="px-5 py-6 text-[13px] text-muted">No specification yet. <Link to={`${base}/documents`} className="font-medium underline">Import one</Link>.</p>
      )}
    </Card>
  );
}

function NextLink({ to, icon, title, detail }: { to: string; icon: React.ReactNode; title: string; detail: string }) {
  return (
    <Link to={to} className="group flex items-center gap-3 rounded-lg border border-line bg-panel-2 p-3 transition hover:border-line-strong">
      <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-accent/15 text-accent-2">{icon}</span>
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-medium">{title}</p>
        <p className="text-[12px] text-muted">{detail}</p>
      </div>
      <ArrowRight size={14} className="text-faint transition group-hover:translate-x-0.5 group-hover:text-fg" />
    </Link>
  );
}

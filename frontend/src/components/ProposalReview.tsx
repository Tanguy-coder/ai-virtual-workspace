import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, ChevronUp, Minus, Plus, Quote, Sparkles, UserPlus } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router";
import { api } from "../api/client";
import type { Department, HiringProposal, ProposalLine, RoleTemplate } from "../api/types";
import { usd } from "../lib/format";
import { DEPARTMENT_COLORS, DEPARTMENT_LABELS } from "./AgentAvatar";
import { Badge, Button, ErrorText, Select, Textarea } from "./ui";

interface Editable extends ProposalLine { include: boolean; added?: boolean }

const DEPARTMENTS = Object.keys(DEPARTMENT_LABELS) as Department[];
const SENIORITY = ["JUNIOR", "MID", "SENIOR", "PRINCIPAL"];

/** UX §6.1: review of the Lead's team proposal; the CTO edits, asks for a revision, rejects or hires. */
export function ProposalReview({ proposal, previous, workspaceId, projectId }: {
  proposal: HiringProposal; previous?: HiringProposal; workspaceId: string; projectId: string;
}) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const pending = proposal.status === "PENDING";
  const [lines, setLines] = useState<Editable[]>(() => proposal.lines.map((l) => ({ ...l, include: true })));
  const [open, setOpen] = useState<number | null>(null);
  const [kickoff, setKickoff] = useState(true);
  const [mode, setMode] = useState<"none" | "revise" | "reject" | "add">("none");
  const [comment, setComment] = useState("");
  const catalog = useQuery({
    queryKey: ["catalog", workspaceId],
    queryFn: () => api.get<RoleTemplate[]>(`/api/v1/workspaces/${workspaceId}/role-templates`),
    enabled: mode === "add",
  });

  const diff = useMemo(() => {
    if (!previous) return null;
    const before = new Map(previous.lines.map((l) => [l.templateId, l]));
    const now = new Set(proposal.lines.map((l) => l.templateId));
    return {
      changed: new Map(proposal.lines.map((l) => {
        const b = before.get(l.templateId);
        return [l.templateId, !b ? "new" : b.count !== l.count ? `${b.count} → ${l.count}` : null] as const;
      })),
      removed: previous.lines.filter((l) => !now.has(l.templateId)),
    };
  }, [previous, proposal.lines]);

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["briefing"] });
    queryClient.invalidateQueries({ queryKey: ["briefings", projectId] });
  };
  const accept = useMutation({
    mutationFn: () => api.post(`/api/v1/hiring-proposals/${proposal.id}/accept`, {
      kickoff,
      lines: lines.filter((l) => l.include && l.count > 0).map((l) => ({
        templateId: l.templateId, count: l.count, department: l.department, seniority: l.seniority,
        skills: l.skills, responsibilities: l.responsibilities,
      })),
    }),
    onSuccess: () => {
      invalidate();
      queryClient.invalidateQueries({ queryKey: ["agents", projectId] });
      navigate(`/w/${workspaceId}/p/${projectId}/office`);
    },
  });
  const revise = useMutation({
    mutationFn: () => api.post(`/api/v1/hiring-proposals/${proposal.id}/revise`, { comment }),
    onSuccess: () => { setMode("none"); invalidate(); },
  });
  const reject = useMutation({
    mutationFn: () => api.post(`/api/v1/hiring-proposals/${proposal.id}/reject`, { reason: comment || null }),
    onSuccess: () => { setMode("none"); invalidate(); },
  });

  const total = lines.filter((l) => l.include).reduce((n, l) => n + l.count, 0);
  const update = (i: number, patch: Partial<Editable>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));

  return (
    <section className="overflow-hidden rounded-md border border-line bg-panel">
      <header className="border-b border-line px-5 py-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <Sparkles size={15} className="text-faint" />
              <h2 className="text-[14px] font-semibold tracking-tight">Team proposal by the Lead</h2>
              <Badge tone="accent">v{proposal.version}</Badge>
              {proposal.status !== "PENDING" && <Badge tone={proposal.status === "ACCEPTED" ? "ok" : "muted"}>{proposal.status.toLowerCase()}</Badge>}
            </div>
            <p className="mt-1.5 max-w-3xl text-[13px] leading-relaxed text-muted">{proposal.rationale}</p>
          </div>
          <div className="text-right">
            <p className="tabular text-[13px] font-semibold">{total} agent{total === 1 ? "" : "s"}</p>
            <p className="text-[11px] text-faint" title="Estimate from list prices, assuming about 200k input and 20k output tokens per agent per day. The real cost appears in the dashboard.">
              {proposal.estimatedDailyUsd != null ? `est. ${usd(proposal.estimatedDailyUsd * (total / Math.max(1, proposal.lines.reduce((n, l) => n + l.count, 0))), 2)}/day · estimate` : "no price known"}
            </p>
          </div>
        </div>
        {previous && (
          <p className="mt-2 text-[12px] text-muted">
            Revised after your comment: <span className="italic">“{previous.revisionComment}”</span>
            {diff && diff.removed.length > 0 && <> · removed: {diff.removed.map((l) => l.title).join(", ")}</>}
          </p>
        )}
      </header>

      <ul className="divide-y divide-line">
        {lines.map((l, i) => {
          const color = DEPARTMENT_COLORS[l.department];
          const change = diff?.changed.get(l.templateId);
          return (
            <li key={`${l.templateId}-${i}`} className={`px-5 py-3.5 transition ${l.include ? "" : "opacity-45"}`}>
              <div className="flex flex-wrap items-center gap-3">
                <input type="checkbox" className="h-4 w-4 accent-[var(--accent)]" checked={l.include} disabled={!pending}
                  onChange={(e) => update(i, { include: e.target.checked })} aria-label={`Hire ${l.title}`} />
                <span className="h-8 w-1 rounded-full" style={{ background: color }} />
                <div className="min-w-48 flex-1">
                  <div className="flex items-center gap-2">
                    <p className="font-semibold tracking-tight">{l.title}</p>
                    {change && <Badge tone={change === "new" ? "ok" : "warn"}>{change}</Badge>}
                    {l.added && <Badge tone="accent">added by you</Badge>}
                  </div>
                  <p className="mt-0.5 text-[12px] leading-relaxed text-muted">{l.justification}</p>
                </div>
                <div className="flex items-center gap-1 rounded-lg border border-line bg-panel-2 p-0.5">
                  <button className="rounded-md p-1 text-muted hover:bg-panel hover:text-fg disabled:opacity-30" disabled={!pending || l.count <= 1}
                    onClick={() => update(i, { count: l.count - 1 })} aria-label="Fewer"><Minus size={13} /></button>
                  <span className="tabular w-6 text-center text-[13px] font-semibold">{l.count}</span>
                  <button className="rounded-md p-1 text-muted hover:bg-panel hover:text-fg disabled:opacity-30" disabled={!pending || l.count >= 4}
                    onClick={() => update(i, { count: l.count + 1 })} aria-label="More"><Plus size={13} /></button>
                </div>
                <div className="w-36">
                  <Select value={l.department} disabled={!pending} onChange={(e) => update(i, { department: e.target.value as Department })} aria-label="Department">
                    {DEPARTMENTS.map((d) => <option key={d} value={d}>{DEPARTMENT_LABELS[d]}</option>)}
                  </Select>
                </div>
                <div className="w-28">
                  <Select value={l.seniority} disabled={!pending} onChange={(e) => update(i, { seniority: e.target.value })} aria-label="Level">
                    {SENIORITY.map((s) => <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</option>)}
                  </Select>
                </div>
                <button className="rounded-md p-1.5 text-muted hover:bg-panel-2 hover:text-fg" onClick={() => setOpen(open === i ? null : i)}
                  aria-label="Details">{open === i ? <ChevronUp size={15} /> : <ChevronDown size={15} />}</button>
              </div>
              {l.quote && (
                <p className="ml-11 mt-2 flex gap-1.5 text-[12px] italic text-faint">
                  <Quote size={12} className="mt-0.5 shrink-0" /> From the specification: “{l.quote}”
                </p>
              )}
              {open === i && (
                <div className="ml-11 mt-3 grid gap-3 md:grid-cols-2">
                  <label className="block space-y-1">
                    <span className="text-[12px] font-medium text-muted">Skills (comma-separated)</span>
                    <Textarea rows={2} disabled={!pending} value={l.skills.join(", ")}
                      onChange={(e) => update(i, { skills: e.target.value.split(",").map((s) => s.trim()).filter(Boolean) })} />
                  </label>
                  <label className="block space-y-1">
                    <span className="text-[12px] font-medium text-muted">Responsibilities (one per line)</span>
                    <Textarea rows={3} disabled={!pending} value={l.responsibilities.join("\n")}
                      onChange={(e) => update(i, { responsibilities: e.target.value.split("\n").map((s) => s.trim()).filter(Boolean) })} />
                  </label>
                </div>
              )}
            </li>
          );
        })}
      </ul>

      {pending && (
        <footer className="space-y-3 border-t border-line bg-panel-2/50 px-5 py-4">
          {mode === "add" && (
            <div className="flex items-center gap-2">
              <Select defaultValue="" aria-label="Role to add" onChange={(e) => {
                const t = catalog.data?.find((x) => x.id === e.target.value);
                if (!t) return;
                setLines((ls) => [...ls, {
                  templateId: t.id, title: t.title, count: 1, department: t.department, seniority: "SENIOR",
                  justification: "Added by the CTO.", quote: null, skills: t.skills, responsibilities: t.responsibilities,
                  include: true, added: true,
                }]);
                setMode("none");
              }}>
                <option value="" disabled>Choose a role from the catalog…</option>
                {catalog.data?.filter((t) => t.id !== "rt_cto").map((t) => <option key={t.id} value={t.id}>{t.title}</option>)}
              </Select>
              <Button variant="subtle" onClick={() => setMode("none")}>Cancel</Button>
            </div>
          )}
          {(mode === "revise" || mode === "reject") && (
            <div className="space-y-2">
              <Textarea rows={3} autoFocus value={comment} onChange={(e) => setComment(e.target.value)}
                placeholder={mode === "revise" ? "What should the Lead change? e.g. no DevOps for now, one more QA…" : "Why do you reject this team? (optional)"} />
              <div className="flex gap-2">
                {mode === "revise"
                  ? <Button disabled={!comment.trim() || revise.isPending} onClick={() => revise.mutate()}>Send to the Lead</Button>
                  : <Button variant="danger" disabled={reject.isPending} onClick={() => reject.mutate()}>Reject the proposal</Button>}
                <Button variant="subtle" onClick={() => setMode("none")}>Cancel</Button>
              </div>
            </div>
          )}
          <ErrorText error={accept.error ?? revise.error ?? reject.error} />
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="subtle" onClick={() => setMode("add")}><Plus size={14} /> Add role</Button>
            <label className="ml-1 flex items-center gap-2 text-[12px] text-muted">
              <input type="checkbox" className="accent-[var(--accent)]" checked={kickoff} onChange={(e) => setKickoff(e.target.checked)} />
              Then the Lead runs the kickoff meeting and plans the tasks
            </label>
            <div className="ml-auto flex gap-2">
              <Button variant="ghost" onClick={() => { setComment(""); setMode("revise"); }}>Ask Lead to revise</Button>
              <Button variant="danger" onClick={() => { setComment(""); setMode("reject"); }}>Reject</Button>
              <Button disabled={total === 0 || accept.isPending} onClick={() => accept.mutate()}>
                <UserPlus size={14} /> Hire {total} ▸
              </Button>
            </div>
          </div>
        </footer>
      )}
    </section>
  );
}

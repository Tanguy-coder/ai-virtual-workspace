import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { api } from "../../api/client";
import type { Finding, ScanKind, Severity } from "../../api/types";
import { Badge, Button, Card, Empty, ErrorText, PageHeader, SectionTitle, SkeletonRows, Table, Tabs, Textarea } from "../../components/ui";
import { SEVERITY_TONE, useFindings, useScans, useSecurity } from "../../lib/engineering";
import { relative } from "../../lib/format";
import { byId, useTasks } from "../../lib/queries";

const KINDS: { kind: ScanKind; label: string; what: string }[] = [
  { kind: "SECRETS", label: "Secrets", what: "Gitleaks on develop" },
  { kind: "SAST", label: "Code", what: "Semgrep on develop" },
  { kind: "DEPENDENCIES", label: "Dependencies", what: "OSV-Scanner on the lockfiles" },
  { kind: "DAST", label: "Running app", what: "OWASP ZAP on the test environment" },
  { kind: "LOAD", label: "Load", what: "k6 on the test environment" },
  { kind: "E2E", label: "End-to-end", what: "The project's test:e2e on the test environment" },
];
const SEVERITIES: Severity[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"];
const STATUS_LABEL: Record<Finding["status"], string> = { OPEN: "Open", FIX_TASK: "Fix in progress", RISK_ACCEPTED: "Risk accepted", FIXED: "Fixed" };
type Filter = "open" | "accepted" | "fixed" | "all";

/** Security Center (docs/18): findings with proof, severity and fix, from scanners run by the platform, never by a model. */
export function SecurityPage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const summary = useSecurity(projectId);
  const findings = useFindings(projectId);
  const scans = useScans(projectId);
  const tasks = byId(useTasks(projectId).data);
  const qc = useQueryClient();
  const [filter, setFilter] = useState<Filter>("open");
  const [selected, setSelected] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const refresh = () => qc.invalidateQueries({ queryKey: ["security", projectId] });
  const scanNow = useMutation({ mutationFn: () => api.post(`/api/v1/projects/${projectId}/security/scans`), onSuccess: refresh });
  const fixTask = useMutation({ mutationFn: (id: string) => api.post(`/api/v1/projects/${projectId}/security/findings/${id}/fix-task`), onSuccess: refresh });
  const accept = useMutation({
    mutationFn: (id: string) => api.post(`/api/v1/projects/${projectId}/security/findings/${id}/accept-risk`, { reason }),
    onSuccess: () => { setReason(""); refresh(); },
  });

  const all = findings.data ?? [];
  const shown = all.filter((f) => filter === "all" || (filter === "open" ? f.status === "OPEN" || f.status === "FIX_TASK"
    : filter === "accepted" ? f.status === "RISK_ACCEPTED" : f.status === "FIXED"));
  const current = shown.find((f) => f.id === selected) ?? shown[0];
  const load = summary.data?.latest.LOAD;
  const s = summary.data;

  return (
    <div>
      <PageHeader
        title="Security Center"
        subtitle="Recognised scanners run by the platform after each merge into develop and each deployment. No model call: scans cost no tokens."
        action={<Button variant="primary" disabled={!s?.enabled || s?.scanning || scanNow.isPending} onClick={() => scanNow.mutate()}>
          {s?.scanning ? "Scanning…" : "Scan develop now"}
        </Button>}
      />
      {s && !s.enabled && <p className="mb-4 text-[13px] text-warn">Scans are disabled on this platform (aiw.security.scans-enabled).</p>}
      <ErrorText error={scanNow.error} />

      {s && (
        <div className="mb-6 flex flex-wrap items-stretch gap-px overflow-hidden rounded border border-line bg-line">
          {SEVERITIES.map((sev) => (
            <div key={sev} className="min-w-[110px] flex-1 bg-panel px-4 py-3">
              <div className="text-[11px] uppercase tracking-wide text-muted">{sev.toLowerCase()}</div>
              <div className={`tabular text-[22px] ${s.open[sev] > 0 && (sev === "CRITICAL" || sev === "HIGH") ? "text-danger" : ""}`}>{s.open[sev]}</div>
            </div>
          ))}
          <div className="min-w-[200px] flex-[2] bg-panel px-4 py-3 text-[13px]">
            <div className="text-[11px] uppercase tracking-wide text-muted">develop → main</div>
            {s.blocking > 0
              ? <div className="mt-1 text-danger">{s.blocking} critical or high finding{s.blocking > 1 ? "s" : ""} block the merge into main</div>
              : <div className="mt-1 text-ok">No critical or high finding open</div>}
          </div>
        </div>
      )}

      <SectionTitle>Scanners</SectionTitle>
      {summary.isLoading ? <SkeletonRows rows={3} /> : (
        <Table className="mb-6" head={["Scan", "Tool", "Last run", "Result", "When"]}>
          {KINDS.map(({ kind, label, what }) => {
            const last = s?.latest[kind];
            return (
              <tr key={kind}>
                <td><div>{label}</div><div className="text-[12px] text-muted">{what}</div></td>
                <td className="font-mono text-[12px] text-muted">{last?.tool ?? "—"}</td>
                <td className="font-mono text-[12px] text-muted">{last ? `qs-${last.number} · ${last.trigger}${last.commitSha ? ` · ${last.commitSha.slice(0, 7)}` : ""}` : "never"}</td>
                <td>{!last ? <span className="text-faint">{kind === "DAST" || kind === "LOAD" || kind === "E2E" ? "After the first deployment" : "After the first merge"}</span>
                  : last.status === "RUNNING" ? <Badge tone="active" dot>running</Badge>
                  : last.status === "FAILED" ? <Badge tone="danger" dot>failed</Badge>
                  : last.status === "SKIPPED" ? <span className="text-muted">skipped · {last.summary.metrics?.reason}</span>
                  : <span className="text-[13px]">{last.summary.findings ?? 0} finding{last.summary.findings === 1 ? "" : "s"}
                      {last.summary.opened ? ` · ${last.summary.opened} new` : ""}{last.summary.fixed ? ` · ${last.summary.fixed} fixed` : ""}</span>}
                  {last?.error && <div className="max-w-[420px] truncate text-[12px] text-danger" title={last.error}>{last.error}</div>}
                </td>
                <td className="text-muted">{last ? relative(last.startedAt) : ""}</td>
              </tr>
            );
          })}
        </Table>
      )}

      {load?.status === "DONE" && load.summary.metrics && (
        <p className="mb-6 text-[13px] text-muted">
          Last load test: {load.summary.metrics.vus} virtual users for {load.summary.metrics.seconds} s · p95{" "}
          <span className="tabular text-fg">{Math.round(load.summary.metrics.p95Ms ?? 0)} ms</span> (budget 500) · errors{" "}
          <span className="tabular text-fg">{((load.summary.metrics.errorRate ?? 0) * 100).toFixed(2)} %</span> (budget 1 %) ·{" "}
          <span className="tabular text-fg">{Math.round(load.summary.metrics.rps ?? 0)}</span> requests/s
        </p>
      )}

      <SectionTitle count={all.length}>Findings</SectionTitle>
      <Tabs value={filter} onChange={setFilter} items={[
        { value: "open", label: "Open", count: all.filter((f) => f.status === "OPEN" || f.status === "FIX_TASK").length },
        { value: "accepted", label: "Risk accepted", count: all.filter((f) => f.status === "RISK_ACCEPTED").length },
        { value: "fixed", label: "Fixed", count: all.filter((f) => f.status === "FIXED").length },
        { value: "all", label: "All" },
      ]} />
      {findings.isLoading && <SkeletonRows />}
      {!findings.isLoading && shown.length === 0 && (
        <Empty title={filter === "open" ? "No open finding" : "Nothing here"}>
          {filter === "open" ? "Critical and high findings get a fix task automatically; develop reaches main only once they are fixed or accepted." : ""}
        </Empty>
      )}
      {shown.length > 0 && (
        <div className="mt-3 grid gap-5 lg:grid-cols-[1fr_440px]">
          <Table head={["", "Severity", "Finding", "Where", "Status"]}>
            {shown.map((f) => (
              <tr key={f.id} onClick={() => setSelected(f.id)} className={`cursor-pointer ${current?.id === f.id ? "bg-accent-tint" : "hover:bg-panel-2"}`}>
                <td className="font-mono text-[12px] text-muted">F-{f.number}</td>
                <td><Badge tone={SEVERITY_TONE[f.severity]} dot>{f.severity.toLowerCase()}</Badge></td>
                <td className="max-w-[360px]"><div className="truncate">{f.title}</div><div className="text-[12px] text-muted">{f.tool} · {f.kind.toLowerCase()}</div></td>
                <td className="max-w-[220px] truncate font-mono text-[12px] text-muted">{f.file ? `${f.file}${f.line ? `:${f.line}` : ""}` : f.location}</td>
                <td className="text-[13px]">{STATUS_LABEL[f.status]}{f.taskId && tasks[f.taskId] ? ` · T-${tasks[f.taskId].number}` : ""}</td>
              </tr>
            ))}
          </Table>
          {current && (
            <Card title={`F-${current.number} · ${current.title}`} subtitle={`${current.severity} · ${current.tool} · ${current.rule}`}>
              <dl className="space-y-3 text-[13px]">
                <div>
                  <dt className="text-[11px] uppercase tracking-wide text-muted">Where</dt>
                  <dd className="font-mono text-[12px]">{current.file ? `${current.file}${current.line ? `:${current.line}` : ""}` : current.location}</dd>
                </div>
                <div>
                  <dt className="text-[11px] uppercase tracking-wide text-muted">Proof</dt>
                  <dd><pre className="max-h-56 overflow-auto whitespace-pre-wrap rounded border border-line bg-panel-2 px-2 py-1.5 font-mono text-[12px]">{current.evidence || "—"}</pre></dd>
                </div>
                <div>
                  <dt className="text-[11px] uppercase tracking-wide text-muted">What to do</dt>
                  <dd className="whitespace-pre-wrap">{current.recommendation}</dd>
                </div>
                {current.reference && (
                  <div>
                    <dt className="text-[11px] uppercase tracking-wide text-muted">Reference</dt>
                    <dd className="break-words text-muted">{current.reference.startsWith("http") ? <a className="underline" href={current.reference} target="_blank" rel="noreferrer">{current.reference}</a> : current.reference}</dd>
                  </div>
                )}
                <div>
                  <dt className="text-[11px] uppercase tracking-wide text-muted">Status</dt>
                  <dd>
                    {STATUS_LABEL[current.status]} · found {relative(current.createdAt)}{current.fixedAt ? ` · fixed ${relative(current.fixedAt)}` : ""}
                    {current.taskId && tasks[current.taskId] && (
                      <> · <Link className="underline" to={`/w/${workspaceId}/p/${projectId}/runs/${current.taskId}`}>T-{tasks[current.taskId].number} {tasks[current.taskId].status.toLowerCase().replace("_", " ")}</Link></>
                    )}
                    {current.acceptedReason && <div className="mt-1 text-muted">Accepted: {current.acceptedReason}</div>}
                  </dd>
                </div>
              </dl>
              {current.status === "OPEN" && (
                <Button className="mt-4" variant="signal" disabled={fixTask.isPending} onClick={() => fixTask.mutate(current.id)}>Create the fix task</Button>
              )}
              <ErrorText error={fixTask.error} />
              {(current.status === "OPEN" || current.status === "FIX_TASK") && (
                <div className="mt-4 border-t border-line pt-3">
                  <p className="mb-2 text-[12px] text-muted">Accept the risk: the finding stops blocking main. Your reason is kept in the audit log.</p>
                  <Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Why this risk is acceptable" />
                  <Button className="mt-2" variant="ghost" size="sm" disabled={!reason.trim() || accept.isPending} onClick={() => accept.mutate(current.id)}>Accept the risk</Button>
                  <ErrorText error={accept.error} />
                </div>
              )}
            </Card>
          )}
        </div>
      )}

      {!!scans.data?.length && (
        <>
          <SectionTitle count={scans.data.length}>Scan history</SectionTitle>
          <Table head={["Scan", "Kind", "Trigger", "Commit", "Result", "Duration", "When"]}>
            {scans.data.slice(0, 30).map((q) => (
              <tr key={q.id}>
                <td className="font-mono text-[12px]">qs-{q.number}</td>
                <td>{q.kind.toLowerCase()} · <span className="text-muted">{q.tool}</span></td>
                <td className="text-muted">{q.trigger}</td>
                <td className="font-mono text-[12px] text-muted">{q.commitSha?.slice(0, 7) ?? ""}</td>
                <td className="text-[13px]">{q.status === "DONE" ? `${q.summary.findings ?? 0} findings` : q.status.toLowerCase()}</td>
                <td className="tabular font-mono text-[12px]">{q.durationMs != null ? `${(q.durationMs / 1000).toFixed(1)} s` : ""}</td>
                <td className="text-muted">{relative(q.startedAt)}</td>
              </tr>
            ))}
          </Table>
        </>
      )}
    </div>
  );
}

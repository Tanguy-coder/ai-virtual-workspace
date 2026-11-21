import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router";
import { api } from "../../api/client";
import type { Deployment, Environment } from "../../api/types";
import { Badge, Button, Card, Empty, ErrorText, Field, Input, PageHeader, SectionTitle, Segmented, Select, SkeletonRows, Table } from "../../components/ui";
import { DEPLOY_TONE, useDelivery } from "../../lib/engineering";
import { relative } from "../../lib/format";

const STATUS_LABEL: Record<Deployment["status"], string> = {
  PENDING_APPROVAL: "Waiting for approval", REJECTED: "Rejected", QUEUED: "Queued", BUILDING: "Building", STARTING: "Starting",
  HEALTHY: "Healthy", FAILED: "Failed", SUPERSEDED: "Replaced", STOPPED: "Stopped",
};
const ENV_TONE: Record<Environment["status"], "ok" | "danger" | "active" | "muted"> = {
  HEALTHY: "ok", UNHEALTHY: "danger", DEPLOYING: "active", EMPTY: "muted", STOPPED: "muted",
};

/** Deployment Center (docs/18): the test environment, deployments (always approved by a human), releases, the remote. */
export function DeploymentPage() {
  const { projectId = "" } = useParams();
  const delivery = useDelivery(projectId);
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: ["delivery", projectId] });
  const [selected, setSelected] = useState<string | null>(null);
  const [bump, setBump] = useState<"patch" | "minor" | "major">("minor");
  const [remoteForm, setRemoteForm] = useState<{ provider: string; url: string; token: string } | null>(null);

  const deploy = useMutation({ mutationFn: (ref: string) => api.post(`/api/v1/projects/${projectId}/deployments`, { ref }), onSuccess: refresh });
  const rollback = useMutation({ mutationFn: (id: string) => api.post(`/api/v1/projects/${projectId}/deployments/${id}/rollback`), onSuccess: refresh });
  const stop = useMutation({ mutationFn: () => api.post(`/api/v1/projects/${projectId}/environments/test/stop`), onSuccess: refresh });
  const release = useMutation({ mutationFn: () => api.post(`/api/v1/projects/${projectId}/releases`, { bump }), onSuccess: refresh });
  const saveRemote = useMutation({
    mutationFn: () => api.put(`/api/v1/projects/${projectId}/remote`, remoteForm),
    onSuccess: () => { setRemoteForm(null); refresh(); },
  });
  const push = useMutation({ mutationFn: () => api.post(`/api/v1/projects/${projectId}/remote/push`), onSuccess: refresh });

  const d = delivery.data;
  const env = d?.environments.find((e) => e.name === "test");
  const current = d?.deployments.find((x) => x.id === env?.currentDeploymentId);
  const shown = d?.deployments.find((x) => x.id === selected) ?? d?.deployments[0];
  const log = useQuery({
    queryKey: ["deployment-log", shown?.id, shown?.status],
    queryFn: () => api.get<{ log: string }>(`/api/v1/deployments/${shown!.id}/log`),
    enabled: !!shown,
  });
  const busy = d?.deployments.some((x) => ["QUEUED", "BUILDING", "STARTING"].includes(x.status));

  return (
    <div>
      <PageHeader
        title="Deployment Center"
        subtitle="The test environment runs the project's own Dockerfile on this machine. Every deployment is approved by a human; a healthy one is then tested live (OWASP ZAP, k6, E2E)."
        action={<Button variant="primary" disabled={busy || deploy.isPending} onClick={() => deploy.mutate("main")}>Deploy main to test</Button>}
      />
      <ErrorText error={deploy.error ?? rollback.error ?? stop.error} />
      {delivery.isLoading && <SkeletonRows />}

      {d && (
        <>
          <SectionTitle>Environment</SectionTitle>
          <div className="mb-6 rounded border border-line bg-panel px-4 py-3 text-[13px]">
            {!env ? <span className="text-muted">Created on the first deployment, on a port of this machine (18000–18999).</span> : (
              <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
                <span className="font-medium">test</span>
                <Badge tone={ENV_TONE[env.status]} dot>{env.status.toLowerCase()}</Badge>
                <a className="font-mono text-[12px] underline" href={env.url} target="_blank" rel="noreferrer">{env.url}</a>
                {current && <span className="text-muted">d-{current.number} · {current.ref} · <span className="font-mono text-[12px]">{current.commitSha.slice(0, 7)}</span> · {relative(current.finishedAt ?? current.createdAt)}</span>}
                {env.status === "HEALTHY" && <Button className="ml-auto" variant="ghost" size="sm" disabled={stop.isPending} onClick={() => stop.mutate()}>Stop</Button>}
              </div>
            )}
          </div>

          <SectionTitle count={d.deployments.length}>Deployments</SectionTitle>
          {d.deployments.length === 0 ? (
            <Empty title="No deployment yet">The Lead asks for one each time develop reaches main; you can also deploy main now. The project needs a Dockerfile at its root.</Empty>
          ) : (
            <div className="mb-6 grid gap-5 lg:grid-cols-[1fr_440px]">
              <Table head={["", "Ref", "Commit", "Requested by", "Status", "When", ""]}>
                {d.deployments.map((x) => (
                  <tr key={x.id} onClick={() => setSelected(x.id)} className={`cursor-pointer ${shown?.id === x.id ? "bg-accent-tint" : "hover:bg-panel-2"}`}>
                    <td className="font-mono text-[12px]">d-{x.number}</td>
                    <td>{x.rollbackOf ? "rollback" : x.ref}</td>
                    <td className="font-mono text-[12px] text-muted">{x.commitSha.slice(0, 7)}</td>
                    <td className="text-muted">{x.requestedByKind === "HUMAN" ? "CTO" : "Lead"}</td>
                    <td><Badge tone={DEPLOY_TONE[x.status]} dot>{STATUS_LABEL[x.status]}</Badge></td>
                    <td className="text-muted">{relative(x.createdAt)}</td>
                    <td>{x.status === "SUPERSEDED" && x.image && (
                      <Button variant="ghost" size="sm" disabled={busy || rollback.isPending} onClick={(e) => { e.stopPropagation(); rollback.mutate(x.id); }}>Roll back</Button>
                    )}</td>
                  </tr>
                ))}
              </Table>
              {shown && (
                <Card title={`d-${shown.number} · ${STATUS_LABEL[shown.status]}`} subtitle={`${shown.ref} · ${shown.commitSha.slice(0, 7)}${shown.image ? ` · ${shown.image}` : ""}`}>
                  {shown.status === "PENDING_APPROVAL" && <p className="mb-2 text-[13px] text-accent">Waiting for your decision in the Inbox.</p>}
                  {shown.error && <p className="mb-2 whitespace-pre-wrap text-[13px] text-danger">{shown.error}</p>}
                  <pre className="max-h-[50vh] overflow-auto whitespace-pre-wrap rounded border border-line bg-graphite px-2 py-1.5 font-mono text-[11px] text-graphite-fg">{log.data?.log || "No log yet."}</pre>
                </Card>
              )}
            </div>
          )}

          <SectionTitle count={d.releases.length}>Releases</SectionTitle>
          <div className="mb-6 grid gap-5 lg:grid-cols-[1fr_440px]">
            <div>
              {d.releases.length === 0 ? <p className="text-[13px] text-muted">No release yet.</p> : (
                <Table head={["Version", "Commit", "Status", "When"]}>
                  {d.releases.map((r) => (
                    <tr key={r.id}>
                      <td className="font-mono text-[12px]">v{r.version}</td>
                      <td className="font-mono text-[12px] text-muted">{r.commitSha.slice(0, 7)}</td>
                      <td><Badge tone={r.status === "RELEASED" ? "ok" : r.status === "PENDING_APPROVAL" ? "accent" : "danger"} dot>{r.status.toLowerCase().replace("_", " ")}</Badge></td>
                      <td className="text-muted">{relative(r.releasedAt ?? r.createdAt)}</td>
                    </tr>
                  ))}
                </Table>
              )}
            </div>
            {d.proposal && (
              <Card title="New release of main" subtitle={d.proposal.current ? `Current: v${d.proposal.current}` : "No version yet"}>
                <Segmented value={bump} onChange={setBump} items={[
                  { value: "patch", label: `v${d.proposal.next.patch}` },
                  { value: "minor", label: `v${d.proposal.next.minor}` },
                  { value: "major", label: `v${d.proposal.next.major}` },
                ]} />
                <p className="mt-3 text-[12px] text-muted">Notes, from the {d.proposal.tasks} task{d.proposal.tasks === 1 ? "" : "s"} merged since the last version:</p>
                <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap rounded border border-line bg-panel-2 px-2 py-1.5 font-mono text-[12px]">{d.proposal.notes || "- Maintenance release"}</pre>
                <Button className="mt-3" variant="signal" disabled={release.isPending} onClick={() => release.mutate()}>
                  Tag v{d.proposal.next[bump]}{d.remote ? " and push" : ""}
                </Button>
                <ErrorText error={release.error} />
              </Card>
            )}
          </div>

          <SectionTitle>Remote repository</SectionTitle>
          <div className="rounded border border-line bg-panel px-4 py-3 text-[13px]">
            {d.remote && !remoteForm ? (
              <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
                <span>{d.remote.provider.toLowerCase()}</span>
                <span className="font-mono text-[12px]">{d.remote.url}</span>
                <span className="text-muted">{d.remote.hasToken ? "token in the vault" : "no token"}</span>
                {d.remote.lastPushAt && <span className={d.remote.lastPushOk ? "text-ok" : "text-danger"}>last push {relative(d.remote.lastPushAt)}{d.remote.lastPushOk ? "" : " failed"}</span>}
                <span className="ml-auto flex gap-2">
                  <Button variant="ghost" size="sm" onClick={() => setRemoteForm({ provider: d.remote!.provider, url: d.remote!.url, token: "" })}>Edit</Button>
                  <Button variant="primary" size="sm" disabled={push.isPending} onClick={() => push.mutate()}>Push main, develop and tags</Button>
                </span>
                {d.remote.lastPushOk === false && d.remote.lastPushInfo && <pre className="w-full whitespace-pre-wrap font-mono text-[11px] text-danger">{d.remote.lastPushInfo}</pre>}
              </div>
            ) : remoteForm ? (
              <div className="grid gap-3 md:grid-cols-[140px_1fr_1fr_auto] md:items-end">
                <Field label="Provider">
                  <Select value={remoteForm.provider} onChange={(e) => setRemoteForm({ ...remoteForm, provider: e.target.value })}>
                    <option value="GITLAB">GitLab</option><option value="GITHUB">GitHub</option><option value="OTHER">Other</option>
                  </Select>
                </Field>
                <Field label="HTTPS URL"><Input value={remoteForm.url} placeholder="https://gitlab.com/owner/repo.git" onChange={(e) => setRemoteForm({ ...remoteForm, url: e.target.value })} /></Field>
                <Field label="Access token" hint={d.remote?.hasToken ? "Leave empty to keep the current one" : "Write access; stored encrypted, never shown again"}>
                  <Input type="password" value={remoteForm.token} onChange={(e) => setRemoteForm({ ...remoteForm, token: e.target.value })} />
                </Field>
                <div className="flex gap-2">
                  <Button variant="primary" disabled={!remoteForm.url || saveRemote.isPending} onClick={() => saveRemote.mutate()}>Save</Button>
                  <Button variant="ghost" onClick={() => setRemoteForm(null)}>Cancel</Button>
                </div>
              </div>
            ) : (
              <div className="flex items-center gap-4">
                <span className="text-muted">Push the project to GitHub or GitLab. Pushing always needs your click or a release you approved.</span>
                <Button className="ml-auto" variant="ghost" size="sm" onClick={() => setRemoteForm({ provider: "GITLAB", url: "", token: "" })}>Set the remote</Button>
              </div>
            )}
            <ErrorText error={saveRemote.error ?? push.error} />
          </div>
        </>
      )}
    </div>
  );
}

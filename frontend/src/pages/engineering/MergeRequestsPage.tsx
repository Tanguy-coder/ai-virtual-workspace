import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Check, GitMerge, MessageSquare } from "lucide-react";
import { useMemo, useState } from "react";
import { Link, useParams } from "react-router";
import { api } from "../../api/client";
import type { ReviewComment } from "../../api/types";
import { AgentAvatar } from "../../components/AgentAvatar";
import { DiffView } from "../../components/DiffView";
import { Badge, Button, Card, Dot, Empty, ErrorText, PageHeader, SkeletonRows, Table, Textarea } from "../../components/ui";
import { parseDiff } from "../../lib/diff";
import { GATES, MR_LABEL, MR_TONE, useMergeRequest, useMergeRequests } from "../../lib/engineering";
import { relative } from "../../lib/format";
import { byId, useAgents, useTasks } from "../../lib/queries";

export function MergeRequestsPage() {
  const { mrId } = useParams();
  return mrId ? <MergeRequestDetail mrId={mrId} /> : <MergeRequestList />;
}

function MergeRequestList() {
  const { workspaceId = "", projectId = "" } = useParams();
  const mrs = useMergeRequests(projectId);
  const agents = byId(useAgents(projectId).data);
  const waiting = (mrs.data ?? []).filter((m) => m.status === "AWAITING_APPROVAL").length;
  return (
    <div>
      <PageHeader title="Merge requests" subtitle={mrs.data?.length ? `${waiting} waiting for your approval` : "A task's branch is proposed for main once its tests pass."} />
      {mrs.isLoading && <SkeletonRows />}
      {mrs.data?.length === 0 && <Empty title="No merge request yet">An agent opens one when it submits a task and the platform's tests pass.</Empty>}
      {!!mrs.data?.length && (
        <Table head={["", "Title", "Author", "Reviewer", "Status", "Round", "Updated"]}>
          {mrs.data.map((m) => (
            <tr key={m.id}>
              <td className="tabular text-faint">!{m.number}</td>
              <td><Link className="font-medium hover:underline" to={`/w/${workspaceId}/p/${projectId}/merge-requests/${m.id}`}>{m.title}</Link></td>
              <td>{agents[m.authorAgentId]?.name}</td>
              <td className="text-muted">{m.reviewerAgentId ? agents[m.reviewerAgentId]?.name : "You"}</td>
              <td><Badge tone={MR_TONE[m.status]} dot>{MR_LABEL[m.status]}</Badge></td>
              <td className="tabular">{m.reviewRounds}</td>
              <td className="text-muted">{relative(m.updatedAt)}</td>
            </tr>
          ))}
        </Table>
      )}
    </div>
  );
}

function MergeRequestDetail({ mrId }: { mrId: string }) {
  const { workspaceId = "", projectId = "" } = useParams();
  const view = useMergeRequest(mrId);
  const agents = byId(useAgents(projectId).data);
  const tasks = byId(useTasks(projectId).data);
  const queryClient = useQueryClient();
  const diff = useQuery({ queryKey: ["mr", mrId, "diff"], queryFn: () => api.get<{ diff: string }>(`/api/v1/merge-requests/${mrId}/diff`) });
  const files = useMemo(() => parseDiff(diff.data?.diff ?? ""), [diff.data]);
  const [comment, setComment] = useState("");
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["mr", mrId] });
    queryClient.invalidateQueries({ queryKey: ["merge-requests", projectId] });
  };
  const approve = useMutation({
    mutationFn: (ok: boolean) => api.post(`/api/v1/workspaces/${workspaceId}/approvals/${view.data!.mergeRequest.approvalId}/decision`,
      { approve: ok, reason: ok ? null : comment }),
    onSuccess: () => { setComment(""); refresh(); },
  });
  const review = useMutation({
    mutationFn: (ok: boolean) => api.post(`/api/v1/merge-requests/${mrId}/review`, { approve: ok, comment: comment || null }),
    onSuccess: () => { setComment(""); refresh(); },
  });
  if (view.isLoading || !view.data) return <SkeletonRows rows={8} />;
  const { mergeRequest: mr, comments } = view.data;
  const task = tasks[mr.taskId];
  const byFile = (c: ReviewComment) => c.file ?? "";
  const general = comments.filter((c) => !c.file);
  const open = mr.status !== "MERGED" && mr.status !== "CLOSED";
  const humanReview = open && mr.status === "IN_REVIEW";

  return (
    <div className="grid gap-5 lg:grid-cols-[1fr_330px]">
      <div className="min-w-0">
        <Link to={`/w/${workspaceId}/p/${projectId}/merge-requests`} className="mb-3 inline-flex items-center gap-1 text-[12px] text-muted hover:text-fg"><ArrowLeft size={13} /> Merge requests</Link>
        <p className="font-mono text-[12px] text-faint">!{mr.number} · {mr.sourceBranch} → {mr.targetBranch} · opened {relative(mr.createdAt)}</p>
        <h1 className="text-[20px] font-semibold tracking-tight">{mr.title}</h1>
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <Badge tone={MR_TONE[mr.status]} dot>{MR_LABEL[mr.status]}</Badge>
          {agents[mr.authorAgentId] && <span className="inline-flex items-center gap-1.5 text-[12px]"><AgentAvatar agent={agents[mr.authorAgentId]} size={18} />{agents[mr.authorAgentId].name}</span>}
          <Badge>{files.length} files · +{files.reduce((a, f) => a + f.added, 0)} −{files.reduce((a, f) => a + f.removed, 0)}</Badge>
          <Badge>review round {mr.reviewRounds}</Badge>
          {task && <Link className="text-[12px] text-muted hover:underline" to={`/w/${workspaceId}/p/${projectId}/runs/${task.id}`}>T-{task.number} work</Link>}
        </div>
        {mr.summary && <p className="mt-3 whitespace-pre-wrap text-[13px] text-muted">{mr.summary}</p>}
        {general.map((c) => <Comment key={c.id} c={c} name={c.authorKind === "HUMAN" ? "You" : agents[c.authorId]?.name ?? "Agent"} />)}
        <div className="mt-4 space-y-3">
          {diff.isLoading && <SkeletonRows rows={6} />}
          {files.map((f) => {
            const fileComments = comments.filter((c) => byFile(c) === f.path);
            return (
              <section key={f.path} className="overflow-hidden rounded-md border border-line">
                <header className="flex items-center gap-2 border-b border-line bg-panel-2 px-3 py-1.5 font-mono text-[12px]">
                  {f.path}<span className="ml-auto"><span className="text-ok">+{f.added}</span> <span className="text-danger">−{f.removed}</span></span>
                </header>
                <DiffView file={f} after={(newNo) => {
                  const here = fileComments.filter((c) => c.line != null && c.line === newNo);
                  return here.length ? <div className="border-y border-line bg-panel-2/60 px-3 py-2 font-sans">{here.map((c) => <Comment key={c.id} c={c} name={c.authorKind === "HUMAN" ? "You" : agents[c.authorId]?.name ?? "Agent"} />)}</div> : null;
                }} />
                {fileComments.filter((c) => c.line == null || !f.lines.some((l) => l.newNo === c.line)).map((c) => (
                  <div key={c.id} className="border-t border-line px-3 py-2"><Comment c={c} name={c.authorKind === "HUMAN" ? "You" : agents[c.authorId]?.name ?? "Agent"} /></div>
                ))}
              </section>
            );
          })}
        </div>
      </div>

      <aside className="space-y-4">
        <Card title="Checks" action={<span className="text-[11px] text-faint">on the merge result</span>}>
          <ul className="divide-y divide-line">
            {GATES.map((g) => {
              const gate = mr.checks?.[g.key];
              return (
                <li key={g.key} className="flex items-center gap-2 py-1.5 text-[13px]">
                  <Dot tone={gate ? (gate.status === "ok" ? "ok" : "danger") : "muted"} />{g.label}
                  <span className="ml-auto text-[12px] text-faint">{gate?.detail ?? "pending"}</span>
                </li>
              );
            })}
            <li className="flex items-center gap-2 py-1.5 text-[13px] text-faint"><Dot tone="muted" />Security analysis<span className="ml-auto text-[12px]">increment 5</span></li>
          </ul>
        </Card>
        <Card title="Merge">
          {mr.status === "MERGED" && <p className="text-[13px]"><GitMerge size={14} className="mr-1 inline" />Merged as <span className="font-mono">{mr.mergedCommit?.slice(0, 7)}</span> {mr.mergedAt && relative(mr.mergedAt)}.</p>}
          {mr.status === "AWAITING_APPROVAL" && (
            <>
              <p className="text-[13px]">Every gate is green. Merging into main needs your approval.</p>
              <Button className="mt-3" variant="signal" disabled={!mr.approvalId || approve.isPending} onClick={() => approve.mutate(true)}><GitMerge size={14} /> Approve and merge</Button>
              <div className="mt-3 space-y-2 border-t border-line pt-3">
                <Textarea rows={2} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Why not? Sent to the author." />
                <Button variant="ghost" disabled={!comment.trim() || approve.isPending} onClick={() => approve.mutate(false)}>Decline</Button>
              </div>
            </>
          )}
          {mr.status === "IN_REVIEW" && <p className="text-[13px] text-muted">{mr.reviewerAgentId ? `${agents[mr.reviewerAgentId]?.name ?? "A teammate"} is reviewing.` : "No teammate can review this: the review is yours."}</p>}
          {mr.status === "CHANGES_REQUESTED" && <p className="text-[13px] text-muted">The author is working on the requested changes.</p>}
          {humanReview && (
            <form className="mt-3 space-y-2 border-t border-line pt-3" onSubmit={(e) => e.preventDefault()}>
              <Textarea rows={3} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Your review comment (required to request changes)" />
              <div className="flex flex-wrap gap-2">
                <Button variant="ghost" disabled={review.isPending} onClick={() => review.mutate(true)}><Check size={14} /> Approve and merge</Button>
                <Button variant="ghost" disabled={!comment.trim() || review.isPending} onClick={() => review.mutate(false)}><MessageSquare size={14} /> Request changes</Button>
              </div>
            </form>
          )}
          <ErrorText error={approve.error ?? review.error} />
        </Card>
        <Card title="Test runs">
          <ul className="divide-y divide-line">
            {view.data.testRuns.map((t) => (
              <li key={t.id} className="flex items-center gap-2 py-1.5 text-[13px]">
                <Dot tone={t.status === "PASSED" ? "ok" : "danger"} /><span className="font-mono">tr-{t.number}</span>
                <span className="text-muted">{t.trigger}</span>
                <span className="ml-auto text-[12px] text-faint">{t.passed ?? "?"} passed{t.failed ? `, ${t.failed} failed` : ""}</span>
              </li>
            ))}
          </ul>
        </Card>
      </aside>
    </div>
  );
}

function Comment({ c, name }: { c: ReviewComment; name: string }) {
  return (
    <div className="my-1.5 max-w-2xl rounded-md border border-line bg-panel px-3 py-2 text-[13px]">
      <p className="text-[12px]"><b className="font-semibold">{name}</b> <span className="text-faint">round {c.round} · {relative(c.createdAt)}{c.line ? ` · line ${c.line}` : ""}</span></p>
      <p className="mt-0.5 whitespace-pre-wrap">{c.body}</p>
    </div>
  );
}

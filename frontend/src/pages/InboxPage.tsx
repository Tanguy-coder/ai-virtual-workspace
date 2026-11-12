import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Lightbulb, ShieldQuestion, Users } from "lucide-react";
import { Link, useParams } from "react-router";
import { api } from "../api/client";
import type { Approval, Briefing, Project } from "../api/types";
import { Badge, Button, Card, Empty, IconChip, PageHeader } from "../components/ui";
import { dateTime, relative } from "../lib/format";

/** Everything waiting for the human, by impact: approvals first, then the Lead's questions and team proposals. */
export function InboxPage() {
  const { workspaceId = "" } = useParams();
  const queryClient = useQueryClient();
  const approvals = useQuery({
    queryKey: ["approvals", workspaceId],
    queryFn: () => api.get<Approval[]>(`/api/v1/workspaces/${workspaceId}/approvals`),
  });
  const waiting = useQuery({
    queryKey: ["waiting", workspaceId],
    queryFn: () => api.get<Briefing[]>(`/api/v1/workspaces/${workspaceId}/briefings/waiting`),
  });
  const projects = useQuery({
    queryKey: ["projects", workspaceId],
    queryFn: () => api.get<Project[]>(`/api/v1/workspaces/${workspaceId}/projects`),
  });
  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: string; approve: boolean }) =>
      api.post(`/api/v1/workspaces/${workspaceId}/approvals/${id}/decision`, { approve }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["approvals", workspaceId] }),
  });
  const name = (id: string) => projects.data?.find((p) => p.id === id)?.name ?? "Project";
  const empty = approvals.data?.length === 0 && waiting.data?.length === 0;

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader title="Inbox" subtitle="Decisions waiting for you" />
      {empty && (
        <Empty title="Nothing waits for you">
          Questions from your Lead, team proposals and sensitive actions (merges, deployments, deletions) appear here.
        </Empty>
      )}
      <div className="space-y-3">
        {approvals.data?.map((a) => (
          <Card key={a.id}>
            <div className="flex items-start gap-3">
              <IconChip tone="warn"><ShieldQuestion size={16} /></IconChip>
              <div className="min-w-0 flex-1">
                <p className="text-[13px] font-semibold">{a.summary}</p>
                <p className="mt-0.5 text-[12px] text-muted">
                  {a.projectId ? `${name(a.projectId)} · ` : ""}{a.action === "branch.merge_main" ? "asked by your Lead" : a.action === "merge_request.arbitrate" ? "the reviewer and the author disagree" : a.action} · requested {dateTime(a.createdAt)}
                </p>
                {a.action === "branch.merge_main" && <MainMergeDetails details={a.details} />}
                {a.action === "merge_request.arbitrate" && (
                  <div className="mt-2 text-[12px] text-muted">
                    <p className="whitespace-pre-line">{String(a.details.remarks ?? "")}</p>
                    <Link className="mt-1 inline-block text-fg underline" to={`/w/${workspaceId}/p/${a.projectId}/merge-requests/${a.targetId}`}>
                      Open !{String(a.details.number ?? "")} and its diff
                    </Link>
                  </div>
                )}
                <div className="mt-3 flex gap-2">
                  <Button onClick={() => decide.mutate({ id: a.id, approve: true })}>
                    {a.action === "merge_request.arbitrate" ? "Accept as is and merge" : "Approve"}</Button>
                  <Button variant="danger" onClick={() => decide.mutate({ id: a.id, approve: false })}>
                    {a.action === "merge_request.arbitrate" ? "Side with the reviewer" : "Reject"}</Button>
                </div>
              </div>
            </div>
          </Card>
        ))}
        {waiting.data?.map((b) => {
          const questions = b.status === "AWAITING_ANSWERS";
          return (
            <Link key={b.id} to={`/w/${workspaceId}/p/${b.projectId}/briefing`}
              className="group flex items-center gap-3 rounded-md border border-line bg-panel p-4 transition hover:border-line-strong">
              <IconChip tone="accent">{questions ? <Lightbulb size={16} /> : <Users size={16} />}</IconChip>
              <div className="min-w-0 flex-1">
                <p className="text-[13px] font-semibold">
                  {questions ? `Your Lead has ${b.questions.length} question${b.questions.length > 1 ? "s" : ""}` : "Your Lead proposes a team"}
                </p>
                <p className="mt-0.5 text-[12px] text-muted">{name(b.projectId)} · {relative(b.updatedAt)}</p>
              </div>
              <Badge tone="accent">{questions ? "answer" : "review"}</Badge>
              <ArrowRight size={15} className="text-faint transition group-hover:translate-x-0.5 group-hover:text-fg" />
            </Link>
          );
        })}
      </div>
    </div>
  );
}

/** What a merge of develop into main brings, as the Lead recorded it when asking. */
function MainMergeDetails({ details }: { details: Record<string, unknown> }) {
  const tasks = Array.isArray(details.tasks) ? (details.tasks as string[]) : [];
  return (
    <div className="mt-2 text-[12px] text-muted">
      {typeof details.note === "string" && <p className="mb-1">{details.note}</p>}
      {tasks.length > 0 && (
        <ul className="list-inside list-disc">
          {tasks.slice(0, 12).map((t) => <li key={t} className="truncate">{t}</li>)}
          {tasks.length > 12 && <li className="list-none text-faint">and {tasks.length - 12} more</li>}
        </ul>
      )}
      <p className="mt-1 font-mono text-faint">develop @ {String(details.commit ?? "").slice(0, 7)} → main</p>
    </div>
  );
}

import { Link, useParams } from "react-router";
import { Badge, Card, Empty, PageHeader, SkeletonRows, Table } from "../../components/ui";
import { MR_LABEL, MR_TONE, useBranches, useLog, useMergeRequests } from "../../lib/engineering";
import { relative } from "../../lib/format";

/** Branches (one per task) and the history of main (docs/10, view 4). */
export function BranchesPage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const branches = useBranches(projectId);
  const log = useLog(projectId);
  const mrs = useMergeRequests(projectId);
  const mrOf = (name: string) => mrs.data?.find((m) => m.sourceBranch === name);
  return (
    <div>
      <PageHeader title="Branches" subtitle="One branch per task, never a shared one. Conflicts are detected before any merge." />
      {branches.isLoading && <SkeletonRows />}
      {branches.data?.length === 0 && <Empty title="No repository yet">It is created when the first task starts.</Empty>}
      {!!branches.data?.length && (
        <Table head={["Branch", "Ahead", "Behind main", "Last commit", "Merge request", "Updated"]}>
          {branches.data.map((b) => {
            const mr = mrOf(b.name);
            return (
              <tr key={b.name}>
                <td><Link to={`/w/${workspaceId}/p/${projectId}/code?ref=${encodeURIComponent(b.name)}`} className="font-mono text-[12px] hover:underline">{b.name}</Link>{b.name === "main" && <Badge className="ml-2">protected</Badge>}</td>
                <td className="tabular">{b.name === "main" ? "" : b.ahead}</td>
                <td className="tabular">{b.name === "main" ? "" : b.behind}</td>
                <td className="max-w-xs truncate text-muted"><span className="mr-1.5 font-mono text-[12px] text-faint">{b.head}</span>{b.lastSubject}</td>
                <td>{mr && <Link to={`/w/${workspaceId}/p/${projectId}/merge-requests/${mr.id}`}><Badge tone={MR_TONE[mr.status]} dot>!{mr.number} {MR_LABEL[mr.status]}</Badge></Link>}</td>
                <td className="text-muted">{relative(b.lastDate)}</td>
              </tr>
            );
          })}
        </Table>
      )}
      {!!log.data?.length && (
        <Card className="mt-6" title="History of main">
          <ul className="divide-y divide-line">
            {log.data.map((c) => (
              <li key={c.sha} className="flex items-center gap-3 py-1.5 text-[13px]">
                <span className="font-mono text-[12px] text-faint">{c.sha}</span>
                <span className="min-w-0 flex-1 truncate">{c.subject}</span>
                <span className="text-[12px] text-muted">{c.author}</span>
                <span className="text-[12px] text-faint">{relative(c.date)}</span>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  );
}

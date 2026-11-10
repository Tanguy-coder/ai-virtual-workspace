import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router";
import { api } from "../../api/client";
import { Badge, Button, Card, Empty, PageHeader, SkeletonRows, Table } from "../../components/ui";
import { useTestRuns } from "../../lib/engineering";
import { relative } from "../../lib/format";
import { byId, useTasks } from "../../lib/queries";

/** Test runs executed by the platform in sandboxes (docs/10, view 5). Runs cost no tokens. */
export function TestingPage() {
  const { projectId = "" } = useParams();
  const runs = useTestRuns(projectId);
  const tasks = byId(useTasks(projectId).data);
  const [selected, setSelected] = useState<string | null>(null);
  const [full, setFull] = useState(false);
  const current = runs.data?.find((r) => r.id === selected) ?? runs.data?.find((r) => r.status !== "PASSED") ?? runs.data?.[0];
  const output = useQuery({
    queryKey: ["test-output", current?.id],
    queryFn: () => api.get<{ output: string }>(`/api/v1/test-runs/${current!.id}/output`),
    enabled: !!current && full,
  });
  return (
    <div>
      <PageHeader title="Testing" subtitle="Tests run by the platform in the task's sandbox, never reported by the agent. Agents read only the failures." />
      {runs.isLoading && <SkeletonRows />}
      {runs.data?.length === 0 && <Empty title="No test run yet">The platform runs the tests when an agent submits a task, and again before merging if main moved.</Empty>}
      {!!runs.data?.length && (
        <div className="grid gap-5 lg:grid-cols-[1fr_420px]">
          <Table head={["Run", "Task", "Branch", "Trigger", "Result", "Duration", "When"]}>
            {runs.data.map((r) => (
              <tr key={r.id} onClick={() => { setSelected(r.id); setFull(false); }} className={`cursor-pointer ${current?.id === r.id ? "bg-accent-tint" : "hover:bg-panel-2"}`}>
                <td className="font-mono text-[12px]">tr-{r.number}</td>
                <td className="tabular text-muted">{r.taskId ? `T-${tasks[r.taskId]?.number ?? ""}` : ""}</td>
                <td className="max-w-[200px] truncate font-mono text-[12px] text-muted">{r.branch}</td>
                <td className="text-muted">{r.trigger}</td>
                <td><Badge tone={r.status === "PASSED" ? "ok" : "danger"} dot>{r.status === "PASSED" ? `${r.passed ?? "?"} passed` : r.status === "ERROR" ? "error" : `${r.failed ?? "?"} failed${r.passed ? ` · ${r.passed} passed` : ""}`}</Badge></td>
                <td className="tabular font-mono text-[12px]">{(r.durationMs / 1000).toFixed(1)} s</td>
                <td className="text-muted">{relative(r.createdAt)}</td>
              </tr>
            ))}
          </Table>
          {current && (
            <Card title={`tr-${current.number} · ${current.command}`}>
              {current.status === "PASSED" ? <p className="text-[13px] text-ok">All tests passed.</p> : (
                <>
                  <p className="mb-2 text-[12px] text-muted">What the agent received (failures only):</p>
                  <pre className="max-h-80 overflow-auto whitespace-pre-wrap rounded border border-line bg-panel-2 px-2 py-1.5 font-mono text-[12px]">{current.failingExcerpt}</pre>
                </>
              )}
              <Button className="mt-3" variant="ghost" size="sm" onClick={() => setFull(!full)}>{full ? "Hide" : "Show"} the full output</Button>
              {full && <pre className="mt-2 max-h-[50vh] overflow-auto whitespace-pre-wrap rounded border border-line bg-graphite px-2 py-1.5 font-mono text-[11px] text-graphite-fg">{output.data?.output ?? "Loading…"}</pre>}
            </Card>
          )}
        </div>
      )}
    </div>
  );
}

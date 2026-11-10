import { Link, useParams } from "react-router";
import { AgentAvatar } from "../../components/AgentAvatar";
import { Badge, Empty, PageHeader, SkeletonRows, Table } from "../../components/ui";
import { RUN_TONE, useRuns } from "../../lib/engineering";
import { relative, tokens, usd } from "../../lib/format";
import { byId, useAgents, useTasks } from "../../lib/queries";

/** Every run of the project, newest first. A run opens the live view of that task's work. */
export function RunsPage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const runs = useRuns(projectId);
  const tasks = byId(useTasks(projectId).data);
  const agents = byId(useAgents(projectId).data);
  const live = (runs.data ?? []).filter((r) => r.status === "RUNNING").length;
  return (
    <div>
      <PageHeader title="Work" subtitle={runs.data?.length ? `${live} running · ${runs.data.length} runs` : "Agents work on their assigned tasks here, step by step."} />
      {runs.isLoading && <SkeletonRows />}
      {runs.data?.length === 0 && (
        <Empty title="No work yet">
          Work starts by itself when a task is assigned and its agent is free: the agent gets a branch and a sandbox, and every
          step it takes appears here.
        </Empty>
      )}
      {!!runs.data?.length && (
        <Table head={["Task", "Agent", "Status", "Branch", "Steps", "Tokens", "Cost", "Updated"]}>
          {runs.data.map((r) => {
            const t = tasks[r.taskId], a = agents[r.agentId];
            return (
              <tr key={r.id}>
                <td><Link className="font-medium hover:underline" to={`/w/${workspaceId}/p/${projectId}/runs/${r.taskId}`}><span className="tabular mr-1.5 text-faint">T-{t?.number}</span>{t?.title}</Link></td>
                <td>{a && <span className="inline-flex items-center gap-1.5"><AgentAvatar agent={a} size={20} />{a.name}</span>}</td>
                <td><Badge tone={RUN_TONE[r.status]} dot>{r.status.charAt(0) + r.status.slice(1).toLowerCase()}</Badge></td>
                <td className="font-mono text-[12px] text-muted">{r.branch}</td>
                <td className="tabular">{r.steps}</td>
                <td className="tabular font-mono text-[12px]">{tokens(r.inputTokens + r.outputTokens)} / {tokens(r.tokenBudget)}</td>
                <td className="tabular">{usd(r.costUsd)}</td>
                <td className="text-muted">{relative(r.updatedAt)}</td>
              </tr>
            );
          })}
        </Table>
      )}
    </div>
  );
}

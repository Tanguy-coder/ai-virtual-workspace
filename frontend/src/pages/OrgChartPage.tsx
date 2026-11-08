import { useQuery } from "@tanstack/react-query";
import { Crown } from "lucide-react";
import { Link, useParams } from "react-router";
import { api } from "../api/client";
import type { Agent, Department } from "../api/types";
import { useMe } from "../App";
import { AgentAvatar, DEPARTMENT_COLORS, DEPARTMENT_LABELS, StateDot } from "../components/AgentAvatar";
import { PageHeader } from "../components/ui";
import { useSelection } from "../lib/selection";

const ORDER: Department[] = ["ARCHITECTURE", "PRODUCT", "BACKEND", "FRONTEND", "QA", "SECURITY", "DEVOPS", "OTHER", "DIRECTION"];

/**
 * Reporting lines as they exist today: the human CTO leads the AI Lead, and every agent reports to the
 * Lead through its department. Department heads arrive with team management in increment 3.
 */
export function OrgChartPage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const me = useMe();
  const open = useSelection((s) => s.open);
  const agents = useQuery({ queryKey: ["agents", projectId], queryFn: () => api.get<Agent[]>(`/api/v1/projects/${projectId}/agents`) });
  const lead = agents.data?.find((a) => a.lead);
  const groups = ORDER.map((d) => ({ d, members: (agents.data ?? []).filter((a) => !a.lead && a.department === d) })).filter((g) => g.members.length > 0);

  return (
    <div>
      <PageHeader title="Org chart" subtitle="Who reports to whom. Change an agent's department from its profile." />
      <div className="flex flex-col items-center">
        <Node>
          <span className="flex h-9 w-9 items-center justify-center rounded-sm bg-ink text-ink-fg"><Crown size={16} /></span>
          <div>
            <p className="font-semibold">{me.data?.displayName ?? "You"}</p>
            <p className="text-[12px] text-muted">CTO · human, final say</p>
          </div>
        </Node>
        <Stem />
        {lead && (
          <button onClick={() => open(lead.id)}>
            <Node highlight>
              <AgentAvatar agent={lead} size={36} />
              <div className="text-left">
                <p className="font-semibold">{lead.name}</p>
                <p className="text-[12px] text-muted">{lead.title}</p>
                <StateDot agent={lead} />
              </div>
            </Node>
          </button>
        )}
        {groups.length > 0 && <Stem />}
        {groups.length === 0 ? (
          <p className="mt-6 text-[13px] text-muted">
            No team yet. <Link className="font-medium underline" to={`/w/${workspaceId}/p/${projectId}/recruitment`}>Recruit agents</Link>
          </p>
        ) : (
          <div className="w-full border-t border-line-strong pt-4">
            <p className="label mb-4 text-center">Reports to {lead?.name ?? "the Lead"}</p>
            <div className="grid gap-x-8 gap-y-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {groups.map(({ d, members }) => (
                <div key={d} className="border-t-2 pt-2" style={{ borderTopColor: DEPARTMENT_COLORS[d] }}>
                  <p className="mb-1 flex items-center justify-between text-[12px] font-semibold">
                    {DEPARTMENT_LABELS[d]} <span className="tabular font-mono text-[11px] font-normal text-faint">{members.length}</span>
                  </p>
                  <ul className="space-y-1">
                    {members.map((a) => (
                      <li key={a.id}>
                        <button onClick={() => open(a.id)} className="flex w-full items-center gap-2.5 border-b border-line py-1.5 text-left hover:bg-panel-2/70">
                          <AgentAvatar agent={a} size={24} />
                          <span className="min-w-0 flex-1">
                            <span className="block truncate text-[13px] font-medium">{a.name}</span>
                            <StateDot agent={a} />
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function Node({ children, highlight = false }: { children: React.ReactNode; highlight?: boolean }) {
  return (
    <div className={`flex min-w-64 items-center gap-3 rounded-md border bg-panel px-4 py-2.5 ${highlight ? "border-fg" : "border-line-strong"}`}>
      {children}
    </div>
  );
}

function Stem() {
  return <div className="h-6 w-px bg-line-strong" />;
}

import { Search, UserPlus } from "lucide-react";
import { useMemo, useState } from "react";
import { Link, useParams } from "react-router";
import type { Department } from "../api/types";
import { AgentAvatar, agentState, DEPARTMENT_COLORS, DEPARTMENT_LABELS, StateDot } from "../components/AgentAvatar";
import { Button, Empty, Input, PageHeader, Select, SkeletonRows, Table } from "../components/ui";
import { byId, useAgents, useTasks } from "../lib/queries";
import { useSelection } from "../lib/selection";

/** Agent directory: a dense table, filterable, opening the inspector on click. */
export function TeamPage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const agents = useAgents(projectId);
  const tasks = useTasks(projectId);
  const { agentId, open } = useSelection();
  const [q, setQ] = useState("");
  const [dept, setDept] = useState<Department | "">("");
  const [state, setState] = useState("");

  const list = useMemo(() => (agents.data ?? []).filter((a) =>
    (!dept || a.department === dept) &&
    (!state || agentState(a).label === state) &&
    (!q || `${a.name} ${a.title} ${a.skills.join(" ")}`.toLowerCase().includes(q.toLowerCase()))), [agents.data, dept, state, q]);
  const missions = useMemo(() => {
    const m: Record<string, { open: number; first?: string }> = {};
    for (const t of tasks.data ?? []) {
      if (!t.assigneeId || ["DONE", "CANCELLED"].includes(t.status)) continue;
      const e = (m[t.assigneeId] ??= { open: 0 });
      e.open++;
      e.first ??= `T-${t.number} ${t.title}`;
    }
    return m;
  }, [tasks.data]);
  const noModel = (agents.data ?? []).filter((a) => a.status !== "DISMISSED" && !a.model);
  const states = [...new Set((agents.data ?? []).map((a) => agentState(a).label))];
  const all = byId(agents.data);

  return (
    <div>
      <PageHeader title="Directory" subtitle={agents.data ? `${agents.data.length} agents in this project` : undefined}
        action={<Link to={`/w/${workspaceId}/p/${projectId}/recruitment`}><Button><UserPlus size={14} /> Recruit</Button></Link>} />

      {noModel.length > 0 && (
        <div className="mb-5 flex items-center gap-3 border-l-2 border-warn bg-warn/5 px-3 py-2 text-[13px]">
          <span className="flex-1"><b className="font-medium">{noModel.length} agent{noModel.length > 1 ? "s have" : " has"} no model.</b> <span className="text-muted">They cannot work until a provider and model are set.</span></span>
          <Link to={`/w/${workspaceId}/settings`} className="text-[12px] font-medium hover:underline">Add a provider</Link>
        </div>
      )}

      <div className="mb-3 flex flex-wrap gap-2">
        <div className="relative w-64">
          <Search size={13} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-faint" />
          <Input className="pl-8" placeholder="Name, role or skill" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className="w-44">
          <Select value={dept} onChange={(e) => setDept(e.target.value as Department | "")} aria-label="Department">
            <option value="">All departments</option>
            {(Object.keys(DEPARTMENT_LABELS) as Department[]).map((d) => <option key={d} value={d}>{DEPARTMENT_LABELS[d]}</option>)}
          </Select>
        </div>
        <div className="w-36">
          <Select value={state} onChange={(e) => setState(e.target.value)} aria-label="State">
            <option value="">Any state</option>
            {states.map((s) => <option key={s} value={s}>{s}</option>)}
          </Select>
        </div>
      </div>

      {agents.isPending ? <SkeletonRows rows={8} /> : list.length === 0 ? <Empty title="No agent matches" /> : (
        <Table head={["Agent", "Department", "State", "Mission", "Model", "Level"]}>
          {list.map((a) => {
            const m = missions[a.id];
            return (
              <tr key={a.id} onClick={() => open(a.id)}
                className={`cursor-pointer transition-colors hover:bg-panel-2/70 ${agentId === a.id ? "bg-accent-tint shadow-[inset_2px_0_0_var(--accent)]" : ""}`}>
                <td>
                  <span className="flex items-center gap-2.5 pl-2">
                    <AgentAvatar agent={a} size={24} />
                    <span className="min-w-0">
                      <span className="block truncate font-medium">{a.name}</span>
                      <span className="block truncate text-[12px] text-faint">{a.title}{a.lead && " · Lead"}</span>
                    </span>
                  </span>
                </td>
                <td className="whitespace-nowrap">
                  <span className="inline-flex items-center gap-2 text-muted"><span className="h-3 w-[3px] rounded-full" style={{ background: DEPARTMENT_COLORS[a.department] }} />{DEPARTMENT_LABELS[a.department]}</span>
                </td>
                <td className="whitespace-nowrap">
                  <StateDot agent={a} />
                  {a.activity === "BLOCKED" && a.blockedReason && <span className="block max-w-56 truncate text-[11px] text-danger">{a.blockedReason}</span>}
                </td>
                <td className="max-w-72">
                  {m ? <span className="block truncate">{m.first}{m.open > 1 && <span className="text-faint"> +{m.open - 1}</span>}</span> : <span className="text-faint">—</span>}
                </td>
                <td className="whitespace-nowrap font-mono text-[12px]">{a.model ?? <span className="text-warn">none</span>}</td>
                <td className="whitespace-nowrap pr-2 text-muted capitalize">{a.seniority.toLowerCase()}</td>
              </tr>
            );
          })}
        </Table>
      )}
      {agentId && !all[agentId] && null}
    </div>
  );
}

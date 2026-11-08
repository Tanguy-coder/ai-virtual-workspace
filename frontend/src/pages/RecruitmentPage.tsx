import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Search, X } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router";
import { api } from "../api/client";
import type { Agent, Department, Provider, RoleTemplate } from "../api/types";
import { AgentAvatar, DEPARTMENT_COLORS, DEPARTMENT_LABELS } from "../components/AgentAvatar";
import { Badge, Button, Card, Empty, ErrorText, Field, Input, PageHeader, Select, SkeletonRows, Table, Textarea } from "../components/ui";
import { useSelection } from "../lib/selection";

const FAMILIES: Record<string, string> = {
  MANAGEMENT: "Direction & management",
  BACKEND: "Backend",
  FRONTEND_MOBILE: "Frontend & mobile",
  QUALITY_SECURITY: "Quality & security",
  INFRASTRUCTURE: "Infrastructure",
  OTHER: "Other specialties",
};
const SENIORITY = ["JUNIOR", "MID", "SENIOR", "PRINCIPAL"] as const;
const DEFAULT_YEARS: Record<string, number> = { JUNIOR: 2, MID: 5, SENIOR: 10, PRINCIPAL: 15 };
const DEPARTMENTS = Object.keys(DEPARTMENT_LABELS) as Department[];

export function RecruitmentPage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const [params] = useSearchParams();
  const catalog = useQuery({ queryKey: ["catalog", workspaceId], queryFn: () => api.get<RoleTemplate[]>(`/api/v1/workspaces/${workspaceId}/role-templates`) });
  const [query, setQuery] = useState("");
  const [family, setFamily] = useState("");
  const [department, setDepartment] = useState<Department | "">((params.get("department") as Department) ?? "");
  const [selected, setSelected] = useState<RoleTemplate | null>(null);
  const [creating, setCreating] = useState(false);

  const roles = useMemo(() => (catalog.data ?? []).filter((t) =>
    (!family || t.family === family) &&
    (!department || t.department === department) &&
    (!query || `${t.title} ${t.skills.join(" ")}`.toLowerCase().includes(query.toLowerCase()))), [catalog.data, family, department, query]);

  const panelOpen = creating || !!selected;
  return (
    <div>
      <PageHeader title="Recruitment"
        subtitle="Hire from the catalog, or review the team your Lead proposes in Lead briefing."
        action={<Button variant="ghost" onClick={() => { setCreating(true); setSelected(null); }}><Plus size={14} /> New role</Button>} />
      <div className={`grid gap-8 ${panelOpen ? "lg:grid-cols-[minmax(0,1fr)_400px]" : ""}`}>
        <div className="min-w-0">
          <div className="mb-3 flex flex-wrap gap-2">
            <div className="relative w-64">
              <Search size={13} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-faint" />
              <Input className="pl-8" placeholder="Role or skill" value={query} onChange={(e) => setQuery(e.target.value)} />
            </div>
            <div className="w-52"><Select value={family} onChange={(e) => setFamily(e.target.value)} aria-label="Family">
              <option value="">All families</option>
              {Object.entries(FAMILIES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </Select></div>
            <div className="w-44"><Select value={department} onChange={(e) => setDepartment(e.target.value as Department | "")} aria-label="Department">
              <option value="">All departments</option>
              {DEPARTMENTS.map((d) => <option key={d} value={d}>{DEPARTMENT_LABELS[d]}</option>)}
            </Select></div>
            <span className="tabular ml-auto self-center font-mono text-[12px] text-faint">{roles.length} roles</span>
          </div>
          {catalog.isPending && <SkeletonRows rows={8} />}
          {roles.length === 0 && catalog.isSuccess && <Empty title="No role matches these filters" />}
          {roles.length > 0 && (
            <Table head={["Role", "Department", "Key skills", ""]}>
              {roles.map((t) => {
                const active = selected?.id === t.id;
                return (
                  <tr key={t.id} onClick={() => { setSelected(t); setCreating(false); }}
                    className={`cursor-pointer transition-colors hover:bg-panel-2/70 ${active ? "bg-accent-tint shadow-[inset_2px_0_0_var(--accent)]" : ""}`}>
                    <td className="whitespace-nowrap"><span className="pl-2 font-medium">{t.title}</span>{!t.builtin && <Badge className="ml-2">custom</Badge>}</td>
                    <td className="whitespace-nowrap">
                      <span className="inline-flex items-center gap-2 text-muted"><span className="h-3 w-[3px] rounded-full" style={{ background: DEPARTMENT_COLORS[t.department] }} />{DEPARTMENT_LABELS[t.department]}</span>
                    </td>
                    <td className="max-w-md truncate text-muted">{t.skills.join(" · ")}</td>
                    <td className="w-20 pr-2 text-right text-[12px] font-medium text-faint">{active ? "selected" : "configure"}</td>
                  </tr>
                );
              })}
            </Table>
          )}
        </div>
        {panelOpen && (
          <div className="slide-in lg:sticky lg:top-0 lg:self-start">
            {creating ? (
              <NewRole workspaceId={workspaceId} onDone={() => setCreating(false)} />
            ) : selected ? (
              <HireForm key={selected.id} template={selected} workspaceId={workspaceId} projectId={projectId} onClose={() => setSelected(null)} />
            ) : null}
          </div>
        )}
      </div>
    </div>
  );
}

function lines(text: string): string[] {
  return text.split(/\n|,/).map((s) => s.trim()).filter(Boolean);
}

function HireForm({ template, workspaceId, projectId, onClose }: { template: RoleTemplate; workspaceId: string; projectId: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const openAgent = useSelection((s) => s.open);
  const providers = useQuery({ queryKey: ["providers", workspaceId], queryFn: () => api.get<Provider[]>(`/api/v1/workspaces/${workspaceId}/providers`) });
  const [name, setName] = useState("");
  const [department, setDepartment] = useState<Department>(template.department);
  const [seniority, setSeniority] = useState<(typeof SENIORITY)[number]>("SENIOR");
  const [years, setYears] = useState(String(DEFAULT_YEARS.SENIOR));
  const [skills, setSkills] = useState(template.skills.join(", "));
  const [responsibilities, setResponsibilities] = useState(template.responsibilities.join("\n"));
  const [providerId, setProviderId] = useState("");
  const [model, setModel] = useState("claude-opus-5-5");

  const hire = useMutation({
    mutationFn: () => api.post<Agent>(`/api/v1/projects/${projectId}/agents`, {
      roleTemplateId: template.id,
      name: name.trim() || null,
      department,
      seniority,
      simulatedYears: Number(years) || 0,
      skills: lines(skills),
      responsibilities: lines(responsibilities),
      providerId: providerId || null,
      model: providerId ? model : null,
    }),
    onSuccess: (agent) => {
      queryClient.invalidateQueries({ queryKey: ["agents", projectId] });
      navigate(`/w/${workspaceId}/p/${projectId}/office`);
      openAgent(agent.id);
    },
  });

  const preview = { name: name || template.title, department, lead: false };
  return (
    <Card title="Configure the position" subtitle={template.title}
      action={<button onClick={onClose} className="rounded-sm p-1 text-faint hover:bg-panel-2 hover:text-fg" aria-label="Close"><X size={15} /></button>}>
      <div className="space-y-4">
        <div className="flex items-center gap-3 border-b border-line pb-3">
          <AgentAvatar agent={preview} size={44} />
          <div className="min-w-0">
            <p className="truncate font-semibold">{preview.name}</p>
            <p className="text-[12px] text-muted">{template.title} · {DEPARTMENT_LABELS[department]}</p>
          </div>
        </div>
        <Field label="Agent name" hint="Leave empty for an automatic name such as “Java Engineer 02”.">
          <Input maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Department">
            <Select value={department} onChange={(e) => setDepartment(e.target.value as Department)}>
              {DEPARTMENTS.map((d) => <option key={d} value={d}>{DEPARTMENT_LABELS[d]}</option>)}
            </Select>
          </Field>
          <Field label="Level">
            <Select value={seniority} onChange={(e) => { const s = e.target.value as (typeof SENIORITY)[number]; setSeniority(s); setYears(String(DEFAULT_YEARS[s])); }}>
              {SENIORITY.map((s) => <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</option>)}
            </Select>
          </Field>
        </div>
        <Field label="Simulated experience (years)" hint="A profile element for role-play, not a claim about the AI model.">
          <Input type="number" min={0} max={40} value={years} onChange={(e) => setYears(e.target.value)} />
        </Field>
        <Field label="Skills" hint="Comma-separated."><Textarea rows={2} value={skills} onChange={(e) => setSkills(e.target.value)} /></Field>
        <Field label="Responsibilities" hint="One per line."><Textarea rows={4} value={responsibilities} onChange={(e) => setResponsibilities(e.target.value)} /></Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Provider">
            <Select value={providerId} onChange={(e) => setProviderId(e.target.value)}>
              <option value="">Workspace default</option>
              {providers.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </Select>
          </Field>
          <Field label="Model"><Input value={model} disabled={!providerId} onChange={(e) => setModel(e.target.value)} /></Field>
        </div>
        <ErrorText error={hire.error} />
        <Button variant="signal" className="w-full" disabled={hire.isPending || lines(skills).length === 0} onClick={() => hire.mutate()}>
          Hire {name.trim() || template.title}
        </Button>
      </div>
    </Card>
  );
}

function NewRole({ workspaceId, onDone }: { workspaceId: string; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState("");
  const [family, setFamily] = useState("BACKEND");
  const [department, setDepartment] = useState<Department>("BACKEND");
  const [skills, setSkills] = useState("");
  const [responsibilities, setResponsibilities] = useState("");
  const create = useMutation({
    mutationFn: () => api.post(`/api/v1/workspaces/${workspaceId}/role-templates`, {
      title, family, department, skills: lines(skills), responsibilities: lines(responsibilities),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["catalog", workspaceId] });
      onDone();
    },
  });
  return (
    <Card title="New role" subtitle="Added to this workspace's catalog">
      <div className="space-y-4">
        <Field label="Title"><Input maxLength={80} value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Rust Engineer" /></Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Family">
            <Select value={family} onChange={(e) => setFamily(e.target.value)}>
              {Object.entries(FAMILIES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </Select>
          </Field>
          <Field label="Department">
            <Select value={department} onChange={(e) => setDepartment(e.target.value as Department)}>
              {DEPARTMENTS.map((d) => <option key={d} value={d}>{DEPARTMENT_LABELS[d]}</option>)}
            </Select>
          </Field>
        </div>
        <Field label="Skills" hint="Comma-separated."><Textarea rows={2} value={skills} onChange={(e) => setSkills(e.target.value)} /></Field>
        <Field label="Responsibilities" hint="One per line."><Textarea rows={3} value={responsibilities} onChange={(e) => setResponsibilities(e.target.value)} /></Field>
        <ErrorText error={create.error} />
        <div className="flex gap-2">
          <Button disabled={!title.trim() || create.isPending} onClick={() => create.mutate()}>Add to catalog</Button>
          <Button variant="subtle" onClick={onDone}>Cancel</Button>
        </div>
      </div>
    </Card>
  );
}

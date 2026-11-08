import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api } from "../api/client";
import { ArrowUpRight } from "lucide-react";
import type { Agent, BudgetView, Project } from "../api/types";
import { AgentAvatar } from "../components/AgentAvatar";
import { Button, Card, Empty, ErrorText, Field, Input, PageHeader, SkeletonRows, Table, Textarea } from "../components/ui";
import { dateTime, usd } from "../lib/format";

export function ProjectsPage() {
  const { workspaceId = "" } = useParams();
  const projects = useQuery({
    queryKey: ["projects", workspaceId],
    queryFn: () => api.get<Project[]>(`/api/v1/workspaces/${workspaceId}/projects`),
  });
  const [creating, setCreating] = useState(false);

  return (
    <div>
      <PageHeader
        title="Projects"
        subtitle="Each project gets its own AI Engineering Lead."
        action={!creating && <Button onClick={() => setCreating(true)}>New project</Button>}
      />
      {creating && <NewProject workspaceId={workspaceId} onCancel={() => setCreating(false)} />}
      {projects.isPending && <SkeletonRows rows={4} />}
      {projects.data?.length === 0 && !creating && (
        <Empty title="No project yet">Create a project and import its specification to brief your Lead.</Empty>
      )}
      {!!projects.data?.length && (
        <Table head={["Project", "Team", "At work", "Spend", "Updated", ""]}>
          {projects.data.map((p) => <ProjectRow key={p.id} project={p} workspaceId={workspaceId} />)}
        </Table>
      )}
    </div>
  );
}

function ProjectRow({ project, workspaceId }: { project: Project; workspaceId: string }) {
  const navigate = useNavigate();
  const agents = useQuery({ queryKey: ["agents", project.id], queryFn: () => api.get<Agent[]>(`/api/v1/projects/${project.id}/agents`) });
  const budget = useQuery({ queryKey: ["budget", project.id], queryFn: () => api.get<BudgetView>(`/api/v1/projects/${project.id}/budget`) });
  const team = agents.data ?? [];
  const working = team.filter((a) => a.status === "ACTIVE" && !["AVAILABLE", "BLOCKED"].includes(a.activity)).length;
  const blocked = team.filter((a) => a.status === "ACTIVE" && a.activity === "BLOCKED").length;
  const go = () => navigate(`/w/${workspaceId}/p/${project.id}/dashboard`);
  return (
    <tr onClick={go} className="group cursor-pointer hover:bg-panel-2/70">
      <td className="max-w-md">
        <Link to={`/w/${workspaceId}/p/${project.id}/dashboard`} className="block pl-2 font-medium" onClick={(e) => e.stopPropagation()}>{project.name}</Link>
        <span className="block truncate pl-2 text-[12px] text-faint">{project.description || "No description"}</span>
      </td>
      <td>
        <span className="flex items-center">
          {team.slice(0, 6).map((a, i) => (
            <span key={a.id} style={{ marginLeft: i ? 3 : 0 }}><AgentAvatar agent={a} size={20} /></span>
          ))}
          <span className="tabular ml-2 font-mono text-[12px] text-faint">{team.length}</span>
        </span>
      </td>
      <td className="whitespace-nowrap text-muted">{working}{blocked > 0 && <span className="text-danger"> · {blocked} blocked</span>}</td>
      <td className="tabular whitespace-nowrap font-mono text-[12px]">{budget.data ? usd(budget.data.spend.totalUsd) : "—"}</td>
      <td className="whitespace-nowrap text-[12px] text-faint">{dateTime(project.updatedAt)}</td>
      <td className="w-8 pr-2 text-faint"><ArrowUpRight size={14} className="opacity-0 group-hover:opacity-100" /></td>
    </tr>
  );
}

function NewProject({ workspaceId, onCancel }: { workspaceId: string; onCancel: () => void }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [spec, setSpec] = useState("");
  const [file, setFile] = useState<File | null>(null);

  const create = useMutation({
    mutationFn: async () => {
      const project = await api.post<Project>(`/api/v1/workspaces/${workspaceId}/projects`, { name, description });
      if (file) {
        const form = new FormData();
        form.append("file", file);
        await api.post(`/api/v1/projects/${project.id}/documents`, form);
      } else if (spec.trim()) {
        await api.post(`/api/v1/projects/${project.id}/documents`, { title: "Specification", kind: "SPECIFICATION", content: spec });
      }
      return project;
    },
    onSuccess: (project) => {
      queryClient.invalidateQueries({ queryKey: ["projects", workspaceId] });
      navigate(`/w/${workspaceId}/p/${project.id}/office`);
    },
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate();
  }

  return (
    <Card title="New project" className="mb-6 max-w-2xl">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Name">
          <Input required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Short description">
          <Input maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        <Field label="Specification" hint="Paste Markdown, or upload a .md / .txt file (2 MB max). PDF and Word are not supported yet.">
          <Textarea rows={8} value={spec} onChange={(e) => setSpec(e.target.value)} disabled={!!file}
            placeholder="I want to build a banking platform managing customers, accounts, wallets and transactions…" />
        </Field>
        <input type="file" accept=".md,.markdown,.txt" onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          className="text-[13px] text-muted file:mr-3 file:rounded-sm file:border file:border-line-strong file:bg-panel file:px-3 file:py-1 file:text-fg" />
        <ErrorText error={create.error} />
        <div className="flex gap-2">
          <Button type="submit" disabled={create.isPending}>Create project</Button>
          <Button type="button" variant="ghost" onClick={onCancel}>Cancel</Button>
        </div>
      </form>
    </Card>
  );
}

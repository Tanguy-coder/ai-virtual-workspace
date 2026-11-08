import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useParams, useSearchParams } from "react-router";
import { api } from "../api/client";
import type { BudgetView, Price, Project, Provider, ProviderKind, TestResult } from "../api/types";
import { Badge, Button, Card, ErrorText, Field, Input, PageHeader } from "../components/ui";
import { usd } from "../lib/format";

export function SettingsPage() {
  const { workspaceId = "" } = useParams();
  const [params] = useSearchParams();
  return (
    <div className="mx-auto max-w-4xl space-y-5">
      <PageHeader title="Settings" />
      {params.get("welcome") && (
        <div className="rounded-lg border border-accent/40 bg-accent/10 p-4 text-[13px]">
          Workspace created. Add an AI provider below so your agents can work, then create your first project.
        </div>
      )}
      <Providers workspaceId={workspaceId} />
      <Budgets workspaceId={workspaceId} />
      <Prices workspaceId={workspaceId} />
    </div>
  );
}

function Providers({ workspaceId }: { workspaceId: string }) {
  const queryClient = useQueryClient();
  const providers = useQuery({ queryKey: ["providers", workspaceId], queryFn: () => api.get<Provider[]>(`/api/v1/workspaces/${workspaceId}/providers`) });
  const [kind, setKind] = useState<ProviderKind>("ANTHROPIC");
  const [name, setName] = useState("Anthropic");
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const create = useMutation({
    mutationFn: () => api.post(`/api/v1/workspaces/${workspaceId}/providers`, { kind, name, baseUrl: baseUrl || null, apiKey: apiKey || null }),
    onSuccess: () => {
      setApiKey("");
      queryClient.invalidateQueries({ queryKey: ["providers", workspaceId] });
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => api.del(`/api/v1/workspaces/${workspaceId}/providers/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["providers", workspaceId] }),
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate();
  }

  return (
    <Card title="AI providers">
      <p className="mb-4 text-[13px] text-muted">
        Keys are encrypted at rest and never shown again. Your keys, your costs: calls are billed by the provider.
      </p>
      <ul className="mb-5 space-y-2">
        {providers.data?.map((p) => <ProviderRow key={p.id} provider={p} workspaceId={workspaceId} onDelete={() => remove.mutate(p.id)} />)}
        {providers.data?.length === 0 && <li className="text-[13px] text-warn">No provider yet: agents cannot answer until you add one.</li>}
      </ul>
      <ErrorText error={remove.error} />
      <form onSubmit={submit} className="grid gap-3 sm:grid-cols-2">
        <Field label="Type">
          <select className="h-9 w-full rounded-md border border-line bg-panel-2 px-2 text-[13px]" value={kind}
            onChange={(e) => { const k = e.target.value as ProviderKind; setKind(k); setName(k === "ANTHROPIC" ? "Anthropic" : "Local models"); }}>
            <option value="ANTHROPIC">Anthropic (Claude)</option>
            <option value="OPENAI_COMPATIBLE">OpenAI-compatible API (Ollama, vLLM…)</option>
          </select>
        </Field>
        <Field label="Name"><Input required value={name} onChange={(e) => setName(e.target.value)} /></Field>
        {kind === "OPENAI_COMPATIBLE" && (
          <Field label="Base URL" hint="e.g. http://localhost:11434/v1"><Input required value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} /></Field>
        )}
        <Field label={kind === "ANTHROPIC" ? "API key" : "API key (optional)"}>
          <Input type="password" autoComplete="off" required={kind === "ANTHROPIC"} value={apiKey} onChange={(e) => setApiKey(e.target.value)} />
        </Field>
        <div className="sm:col-span-2">
          <ErrorText error={create.error} />
          <Button type="submit" disabled={create.isPending}>Add provider</Button>
        </div>
      </form>
    </Card>
  );
}

function ProviderRow({ provider, workspaceId, onDelete }: { provider: Provider; workspaceId: string; onDelete: () => void }) {
  const [model, setModel] = useState(provider.kind === "ANTHROPIC" ? "claude-opus-5-5" : "");
  const test = useMutation({
    mutationFn: () => api.post<TestResult>(`/api/v1/workspaces/${workspaceId}/providers/${provider.id}/test`, { model }),
  });
  const r = test.data;
  return (
    <li className="rounded-md border border-line p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{provider.name}</span>
        <Badge>{provider.kind === "ANTHROPIC" ? "Anthropic" : "OpenAI-compatible"}</Badge>
        {provider.baseUrl && <span className="font-mono text-[12px] text-muted">{provider.baseUrl}</span>}
        <span className="ml-auto flex items-center gap-2">
          <Input className="h-8 w-48" value={model} onChange={(e) => setModel(e.target.value)} placeholder="model id" aria-label="Model to test" />
          <Button variant="ghost" disabled={!model || test.isPending} onClick={() => test.mutate()}>Test</Button>
          <Button variant="danger" onClick={onDelete}>Remove</Button>
        </span>
      </div>
      {test.isPending && <p className="mt-2 text-[12px] text-muted">Calling {model}… (this is a real, billed call)</p>}
      {r && (r.ok
        ? <p className="mt-2 text-[12px] text-ok">Replied "{r.reply}" · {r.servedModel} · {r.inputTokens}/{r.outputTokens} tokens · {r.latencyMs} ms · {r.costUsd ? usd(r.costUsd, 6) : "unpriced"}</p>
        : <p className="mt-2 text-[12px] text-danger">{r.error ?? "The model declined the request"}</p>)}
      <ErrorText error={test.error} />
    </li>
  );
}

function Budgets({ workspaceId }: { workspaceId: string }) {
  const projects = useQuery({ queryKey: ["projects", workspaceId], queryFn: () => api.get<Project[]>(`/api/v1/workspaces/${workspaceId}/projects`) });
  return (
    <Card title="Project budgets">
      <p className="mb-3 text-[13px] text-muted">
        Calls are refused once a limit is reached. An event is recorded at 80% and 100%. Days are UTC.
      </p>
      {projects.data?.length === 0 && <p className="text-[13px] text-muted">No project yet.</p>}
      <div className="space-y-2">{projects.data?.map((p) => <BudgetRow key={p.id} project={p} />)}</div>
    </Card>
  );
}

function BudgetRow({ project }: { project: Project }) {
  const queryClient = useQueryClient();
  const budget = useQuery({ queryKey: ["budget", project.id], queryFn: () => api.get<BudgetView>(`/api/v1/projects/${project.id}/budget`) });
  const [daily, setDaily] = useState<string | null>(null);
  const [total, setTotal] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => api.put(`/api/v1/projects/${project.id}/budget`, {
      dailyLimitUsd: (daily ?? String(budget.data?.dailyLimitUsd ?? "")) || null,
      totalLimitUsd: (total ?? String(budget.data?.totalLimitUsd ?? "")) || null,
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["budget", project.id] }),
  });
  const b = budget.data;
  return (
    <div className="grid items-end gap-2 rounded-md border border-line p-3 sm:grid-cols-[1fr_120px_120px_auto]">
      <div>
        <p className="font-medium">{project.name}</p>
        {b && <p className="text-[12px] text-muted">today {usd(b.spend.todayUsd)} · total {usd(b.spend.totalUsd)}</p>}
      </div>
      <Field label="Daily $"><Input inputMode="decimal" value={daily ?? String(b?.dailyLimitUsd ?? "")} onChange={(e) => setDaily(e.target.value)} placeholder="none" /></Field>
      <Field label="Total $"><Input inputMode="decimal" value={total ?? String(b?.totalLimitUsd ?? "")} onChange={(e) => setTotal(e.target.value)} placeholder="none" /></Field>
      <Button variant="ghost" onClick={() => save.mutate()} disabled={save.isPending}>Save</Button>
      <div className="sm:col-span-4"><ErrorText error={save.error} /></div>
    </div>
  );
}

function Prices({ workspaceId }: { workspaceId: string }) {
  const queryClient = useQueryClient();
  const prices = useQuery({ queryKey: ["prices", workspaceId], queryFn: () => api.get<Price[]>(`/api/v1/workspaces/${workspaceId}/model-prices`) });
  const [form, setForm] = useState({ providerKind: "OPENAI_COMPATIBLE" as ProviderKind, model: "", in: "", out: "" });
  const save = useMutation({
    mutationFn: () => api.put(`/api/v1/workspaces/${workspaceId}/model-prices`, {
      providerKind: form.providerKind, model: form.model, inputUsdPerMtok: form.in, outputUsdPerMtok: form.out,
    }),
    onSuccess: () => {
      setForm({ ...form, model: "", in: "", out: "" });
      queryClient.invalidateQueries({ queryKey: ["prices", workspaceId] });
    },
  });
  return (
    <Card title="Model prices (USD per million tokens)">
      <table className="mb-4 w-full text-[13px]">
        <thead className="text-left text-[12px] text-muted">
          <tr><th className="py-1 font-medium">Model</th><th className="font-medium">Input</th><th className="font-medium">Output</th><th /></tr>
        </thead>
        <tbody>
          {prices.data?.map((p) => (
            <tr key={p.providerKind + p.model} className="border-t border-line">
              <td className="py-1.5 font-mono">{p.model}</td>
              <td className="font-mono">${p.inputUsdPerMtok}</td>
              <td className="font-mono">${p.outputUsdPerMtok}</td>
              <td>{p.builtin ? <Badge>list price</Badge> : <Badge tone="active">custom</Badge>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="grid items-end gap-2 sm:grid-cols-[160px_1fr_90px_90px_auto]">
        <Field label="Provider type">
          <select className="h-9 w-full rounded-md border border-line bg-panel-2 px-2 text-[13px]" value={form.providerKind}
            onChange={(e) => setForm({ ...form, providerKind: e.target.value as ProviderKind })}>
            <option value="OPENAI_COMPATIBLE">OpenAI-compatible</option>
            <option value="ANTHROPIC">Anthropic</option>
          </select>
        </Field>
        <Field label="Model"><Input value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} /></Field>
        <Field label="Input $"><Input inputMode="decimal" value={form.in} onChange={(e) => setForm({ ...form, in: e.target.value })} /></Field>
        <Field label="Output $"><Input inputMode="decimal" value={form.out} onChange={(e) => setForm({ ...form, out: e.target.value })} /></Field>
        <Button variant="ghost" disabled={!form.model || !form.in || !form.out || save.isPending} onClick={() => save.mutate()}>Set price</Button>
      </div>
      <ErrorText error={save.error} />
    </Card>
  );
}

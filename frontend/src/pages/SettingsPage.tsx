import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useParams, useSearchParams } from "react-router";
import { api } from "../api/client";
import type { BudgetView, Price, Project, Provider, ProviderKind, TestResult } from "../api/types";
import { Badge, Button, Card, ErrorText, Field, Input, PageHeader } from "../components/ui";
import { usd } from "../lib/format";
import { usePresets, useProviderModels } from "../lib/providers";

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
  const presets = usePresets();
  const [presetId, setPresetId] = useState("anthropic");
  const preset = presets.data?.find((p) => p.id === presetId);
  const [name, setName] = useState("Anthropic");
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const create = useMutation({
    mutationFn: () => api.post(`/api/v1/workspaces/${workspaceId}/providers`, { preset: presetId, name, baseUrl: baseUrl || null, apiKey: apiKey || null }),
    onSuccess: () => {
      setApiKey("");
      queryClient.invalidateQueries({ queryKey: ["providers", workspaceId] });
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => api.del(`/api/v1/workspaces/${workspaceId}/providers/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["providers", workspaceId] }),
  });

  function pick(id: string) {
    const p = presets.data?.find((x) => x.id === id);
    setPresetId(id);
    setName(p ? p.label.replace(/ \(.*\)$/, "") : "");
    setBaseUrl(p?.baseUrl ?? "");
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate();
  }

  return (
    <Card title="AI providers">
      <p className="mb-4 text-[13px] text-muted">
        Use any provider: Anthropic, OpenAI, Google Gemini, Mistral, DeepSeek, xAI, Groq, OpenRouter, or local models
        (Ollama, LM Studio). Keys are encrypted at rest and never shown again. Your keys, your costs: the provider bills the calls.
      </p>
      <ul className="mb-5 space-y-2">
        {providers.data?.map((p) => <ProviderRow key={p.id} provider={p} workspaceId={workspaceId} onDelete={() => remove.mutate(p.id)} />)}
        {providers.data?.length === 0 && <li className="text-[13px] text-warn">No provider yet: agents cannot answer until you add one.</li>}
      </ul>
      <ErrorText error={remove.error} />
      <form onSubmit={submit} className="grid gap-3 sm:grid-cols-2">
        <Field label="Provider">
          <select className="h-9 w-full rounded-md border border-line bg-panel-2 px-2 text-[13px]" value={presetId} onChange={(e) => pick(e.target.value)}>
            {presets.data?.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
          </select>
        </Field>
        <Field label="Name"><Input required value={name} onChange={(e) => setName(e.target.value)} /></Field>
        {preset?.kind === "OPENAI_COMPATIBLE" && (
          <Field label="Base URL" hint={preset.local ? "The server must be reachable from the backend" : "OpenAI-compatible endpoint"}>
            <Input required value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://…/v1" />
          </Field>
        )}
        <Field label={preset?.keyRequired ? "API key" : "API key (optional)"}
          hint={preset?.keyUrl ? <a className="underline" href={preset.keyUrl} target="_blank" rel="noreferrer">Get a key</a> : undefined}>
          <Input type="password" autoComplete="off" required={!!preset?.keyRequired} value={apiKey} onChange={(e) => setApiKey(e.target.value)} />
        </Field>
        <div className="sm:col-span-2">
          <p className="mb-2 text-[12px] text-muted">After adding it, pick its default model from the list the provider reports.</p>
          <ErrorText error={create.error} />
          <Button type="submit" disabled={create.isPending || !preset}>Add provider</Button>
        </div>
      </form>
    </Card>
  );
}

function ProviderRow({ provider, workspaceId, onDelete }: { provider: Provider; workspaceId: string; onDelete: () => void }) {
  const queryClient = useQueryClient();
  const presets = usePresets();
  const label = presets.data?.find((p) => p.id === provider.preset)?.label ?? (provider.kind === "ANTHROPIC" ? "Anthropic" : "OpenAI-compatible");
  const models = useProviderModels(workspaceId, provider.id);
  const [defaultModel, setDefaultModel] = useState(provider.defaultModel ?? "");
  const [economy, setEconomy] = useState(provider.economyModel ?? "");
  const [model, setModel] = useState(provider.defaultModel ?? "");
  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["providers", workspaceId] });
    queryClient.invalidateQueries({ queryKey: ["agents"] });
  };
  const saveModels = useMutation({
    mutationFn: () => api.put<Provider>(`/api/v1/workspaces/${workspaceId}/providers/${provider.id}/models`,
      { defaultModel: defaultModel || null, economyModel: economy || null }),
    onSuccess: invalidate,
  });
  const makeDefault = useMutation({
    mutationFn: () => api.put<Provider>(`/api/v1/workspaces/${workspaceId}/providers/${provider.id}/default`, {}),
    onSuccess: invalidate,
  });
  const test = useMutation({
    mutationFn: () => api.post<TestResult>(`/api/v1/workspaces/${workspaceId}/providers/${provider.id}/test`, { model }),
  });
  const r = test.data;
  const listId = `models-${provider.id}`;
  const dirty = defaultModel !== (provider.defaultModel ?? "") || economy !== (provider.economyModel ?? "");
  return (
    <li className="rounded-md border border-line p-3">
      <datalist id={listId}>{models.data?.map((m) => <option key={m} value={m} />)}</datalist>
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{provider.name}</span>
        <Badge>{label}</Badge>
        {provider.isDefault ? <Badge tone="ok">Default for new agents</Badge>
          : <Button variant="subtle" size="sm" disabled={!provider.defaultModel || makeDefault.isPending} onClick={() => makeDefault.mutate()}
              title={provider.defaultModel ? undefined : "Choose a default model first"}>Make default</Button>}
        {provider.baseUrl && <span className="font-mono text-[12px] text-muted">{provider.baseUrl}</span>}
        <Button className="ml-auto" variant="danger" size="sm" onClick={onDelete}>Remove</Button>
      </div>
      <form className="mt-3 grid gap-2 border-t border-line pt-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end"
        onSubmit={(e) => { e.preventDefault(); saveModels.mutate(); }}>
        <Field label="Default model" hint="Bound to new agents when this provider is the default">
          <Input list={listId} value={defaultModel} onChange={(e) => setDefaultModel(e.target.value)} placeholder={models.isLoading ? "Loading models…" : "model id"} />
        </Field>
        <Field label="Economy model" hint="Light calls such as meeting minutes; empty: agents' own model">
          <Input list={listId} value={economy} onChange={(e) => setEconomy(e.target.value)} placeholder="optional" />
        </Field>
        <Button variant="ghost" type="submit" disabled={!dirty || saveModels.isPending}>Save models</Button>
      </form>
      <p className="mt-1 text-[12px] text-muted">
        {models.data ? `${models.data.length} models reported by the provider.` : models.isError ? `Model list unavailable: ${(models.error as Error).message}` : ""}
      </p>
      <ErrorText error={saveModels.error ?? makeDefault.error} />
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <div className="w-64"><Input className="h-8" list={listId} value={model} onChange={(e) => setModel(e.target.value)} placeholder="model to test" aria-label="Model to test" /></div>
        <Button variant="ghost" size="sm" disabled={!model || test.isPending} onClick={() => test.mutate()}>Test</Button>
        {test.isPending && <span className="text-[12px] text-muted">Calling {model}… (a real, billed call)</span>}
      </div>
      {r && (r.ok
        ? <p className="mt-2 text-[12px] text-ok">Replied "{r.reply}" · {r.servedModel} · {r.inputTokens}/{r.outputTokens} tokens · {r.latencyMs} ms · {r.costUsd ? usd(r.costUsd, 6) : "unpriced: add its price below"}</p>
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
        Calls are refused once a limit is reached. An event is recorded at 80% and 100%. Days are UTC. Tokens per task is
        what each new task run may spend; the Lead sizes its tasks to fit it, so a small model can carry a big project.
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
  const [perTask, setPerTask] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => api.put(`/api/v1/projects/${project.id}/budget`, {
      dailyLimitUsd: (daily ?? String(budget.data?.dailyLimitUsd ?? "")) || null,
      totalLimitUsd: (total ?? String(budget.data?.totalLimitUsd ?? "")) || null,
      taskTokenBudget: Number((perTask ?? String(budget.data?.taskTokenBudget ?? "")).replace(/\D/g, "")) || null,
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["budget", project.id] }),
  });
  const b = budget.data;
  return (
    <div className="grid items-end gap-2 rounded-md border border-line p-3 sm:grid-cols-[1fr_110px_110px_150px_auto]">
      <div>
        <p className="font-medium">{project.name}</p>
        {b && <p className="text-[12px] text-muted">today {usd(b.spend.todayUsd)} · total {usd(b.spend.totalUsd)}</p>}
      </div>
      <Field label="Daily $"><Input inputMode="decimal" value={daily ?? String(b?.dailyLimitUsd ?? "")} onChange={(e) => setDaily(e.target.value)} placeholder="none" /></Field>
      <Field label="Total $"><Input inputMode="decimal" value={total ?? String(b?.totalLimitUsd ?? "")} onChange={(e) => setTotal(e.target.value)} placeholder="none" /></Field>
      <Field label="Tokens per task"><Input inputMode="numeric" value={perTask ?? String(b?.taskTokenBudget ?? "")} onChange={(e) => setPerTask(e.target.value)} placeholder={b?.defaultTaskTokenBudget ? `${b.defaultTaskTokenBudget} (default)` : "default"} max={b?.maxTaskTokenBudget} />{b && <span className="text-[11px] text-faint">at most {b.maxTaskTokenBudget}</span>}</Field>
      <Button variant="ghost" onClick={() => save.mutate()} disabled={save.isPending}>Save</Button>
      <div className="sm:col-span-5"><ErrorText error={save.error} /></div>
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

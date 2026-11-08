import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BookOpen, History, Pencil, Plus, Search, X } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { api } from "../api/client";
import type { Agent, MemoryEntry } from "../api/types";
import { AgentAvatar } from "../components/AgentAvatar";
import { Markdown } from "../components/Markdown";
import { Badge, Button, Card, Empty, ErrorText, Field, Input, PageHeader, Select, Textarea, type Tone } from "../components/ui";
import { relative } from "../lib/format";
import { byId, useAgents } from "../lib/queries";

const KINDS: Record<string, { label: string; tone: Tone }> = {
  ARCHITECTURE: { label: "Architecture", tone: "accent" },
  DECISION: { label: "Decision", tone: "ok" },
  REQUIREMENT: { label: "Requirement", tone: "active" },
  RISK: { label: "Risk", tone: "danger" },
  CONTRACT: { label: "Contract", tone: "warn" },
  CONVENTION: { label: "Convention", tone: "muted" },
  GLOSSARY: { label: "Glossary", tone: "muted" },
  NOTE: { label: "Note", tone: "muted" },
};

/** Shared project memory: versioned, sourced entries that every agent receives in its context. */
export function MemoryPage() {
  const { projectId = "" } = useParams();
  const [q, setQ] = useState("");
  const [kind, setKind] = useState("");
  const [editing, setEditing] = useState<MemoryEntry | "new" | null>(null);
  const memory = useQuery({
    queryKey: ["memory", projectId, q, kind],
    queryFn: () => api.get<MemoryEntry[]>(`/api/v1/projects/${projectId}/memory?q=${encodeURIComponent(q)}&kind=${kind}`),
  });
  const agents = byId(useAgents(projectId).data);

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader title="Shared memory"
        subtitle="Decisions, architecture, requirements and conventions. Every agent gets the most relevant entries in its context."
        action={<Button onClick={() => setEditing("new")}><Plus size={14} /> Add entry</Button>} />
      {editing && <Editor projectId={projectId} entry={editing === "new" ? null : editing} onDone={() => setEditing(null)} />}
      <div className="mb-4 flex flex-wrap gap-2">
        <div className="relative min-w-56 flex-1">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" />
          <Input className="pl-8" placeholder="Search memory (any word)" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className="w-44">
          <Select value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Kind">
            <option value="">All kinds</option>
            {Object.entries(KINDS).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
          </Select>
        </div>
      </div>
      {memory.isSuccess && memory.data.length === 0 && (
        <Empty icon={<BookOpen size={18} />} title={q || kind ? "Nothing matches" : "Memory is empty"}>
          {q || kind ? "Try other words." : "Approving the Lead's architecture and publishing meeting minutes fill it automatically."}
        </Empty>
      )}
      <div className="space-y-3">
        {memory.data?.map((e) => <EntryCard key={e.id} entry={e} projectId={projectId} author={e.authorKind === "AGENT" ? agents[e.authorId ?? ""] : undefined} onEdit={() => setEditing(e)} />)}
      </div>
    </div>
  );
}

function EntryCard({ entry, projectId, author, onEdit }: { entry: MemoryEntry; projectId: string; author?: Agent; onEdit: () => void }) {
  const [expanded, setExpanded] = useState(entry.kind === "ARCHITECTURE" ? false : entry.content.length < 600);
  const [history, setHistory] = useState(false);
  const versions = useQuery({
    queryKey: ["memory-history", entry.entryKey],
    queryFn: () => api.get<MemoryEntry[]>(`/api/v1/projects/${projectId}/memory/${entry.entryKey}/history`),
    enabled: history,
  });
  const k = KINDS[entry.kind] ?? KINDS.NOTE;
  const source = { BRIEFING: "Lead briefing", MEETING: "meeting minutes", HUMAN: "added by you" }[entry.sourceKind] ?? entry.sourceKind.toLowerCase();
  return (
    <article className="rounded-md border border-line bg-panel p-4">
      <header className="flex flex-wrap items-center gap-2">
        <Badge tone={k.tone}>{k.label}</Badge>
        <h3 className="text-[14px] font-semibold tracking-tight">{entry.title}</h3>
        {entry.version > 1 && <Badge>v{entry.version}</Badge>}
        <span className="ml-auto flex items-center gap-1.5 text-[11px] text-faint">
          {author && <AgentAvatar agent={author} size={16} />}
          {author?.name ?? (entry.authorKind === "HUMAN" ? "You" : "Platform")} · {source} · {relative(entry.createdAt)}
        </span>
      </header>
      <div className={`relative mt-3 ${expanded ? "" : "max-h-32 overflow-hidden"}`}>
        <Markdown text={entry.content} />
        {!expanded && <div className="absolute inset-x-0 bottom-0 h-12 bg-gradient-to-t from-[var(--panel-solid)] to-transparent" />}
      </div>
      <footer className="mt-2 flex gap-1">
        <Button size="sm" variant="subtle" onClick={() => setExpanded(!expanded)}>{expanded ? "Collapse" : "Read all"}</Button>
        <Button size="sm" variant="subtle" onClick={onEdit}><Pencil size={12} /> New version</Button>
        {entry.version > 1 && <Button size="sm" variant="subtle" onClick={() => setHistory(!history)}><History size={12} /> History</Button>}
      </footer>
      {history && versions.data && (
        <ol className="mt-3 space-y-2 border-t border-line pt-3">
          {versions.data.slice(1).map((v) => (
            <li key={v.id} className="rounded-lg bg-panel-2 p-3 text-[12px]">
              <p className="mb-1 text-faint">v{v.version} · {relative(v.createdAt)}</p>
              <p className="line-clamp-4 whitespace-pre-wrap text-muted">{v.content}</p>
            </li>
          ))}
        </ol>
      )}
    </article>
  );
}

function Editor({ projectId, entry, onDone }: { projectId: string; entry: MemoryEntry | null; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [kind, setKind] = useState(entry?.kind ?? "DECISION");
  const [title, setTitle] = useState(entry?.title ?? "");
  const [content, setContent] = useState(entry?.content ?? "");
  const save = useMutation({
    mutationFn: () => entry
      ? api.put(`/api/v1/projects/${projectId}/memory/${entry.entryKey}`, { kind, title, content })
      : api.post(`/api/v1/projects/${projectId}/memory`, { kind, title, content }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ["memory", projectId] }); onDone(); },
  });
  return (
    <Card className="mb-5" title={entry ? `New version of “${entry.title}”` : "New memory entry"} subtitle="Versioned; earlier versions stay readable."
      action={<button onClick={onDone} className="rounded-lg p-1 text-muted hover:bg-panel-2" aria-label="Close"><X size={16} /></button>}>
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-[180px_1fr]">
          <Field label="Kind">
            <Select value={kind} onChange={(e) => setKind(e.target.value)}>
              {Object.entries(KINDS).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
            </Select>
          </Field>
          <Field label="Title"><Input maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} /></Field>
        </div>
        <Field label="Content" hint="Markdown."><Textarea rows={8} value={content} onChange={(e) => setContent(e.target.value)} /></Field>
        <ErrorText error={save.error} />
        <Button disabled={!title.trim() || !content.trim() || save.isPending} onClick={() => save.mutate()}>Save</Button>
      </div>
    </Card>
  );
}

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router";
import { api } from "../api/client";
import type { ProjectDocument } from "../api/types";
import { Badge, Button, Card, Empty, ErrorText, Field, Input, PageHeader, Textarea } from "../components/ui";
import { bytes, dateTime } from "../lib/format";

export function DocumentsPage() {
  const { projectId = "" } = useParams();
  const queryClient = useQueryClient();
  const docs = useQuery({
    queryKey: ["documents", projectId],
    queryFn: () => api.get<ProjectDocument[]>(`/api/v1/projects/${projectId}/documents`),
  });
  const [openId, setOpenId] = useState<string | null>(null);
  const open = useQuery({
    queryKey: ["document", openId],
    queryFn: () => api.get<ProjectDocument>(`/api/v1/projects/${projectId}/documents/${openId}`),
    enabled: !!openId,
  });
  const [adding, setAdding] = useState(false);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const add = useMutation({
    mutationFn: async () => {
      if (file) {
        const form = new FormData();
        form.append("file", file);
        return api.post(`/api/v1/projects/${projectId}/documents`, form);
      }
      return api.post(`/api/v1/projects/${projectId}/documents`, { title, kind: "SPECIFICATION", content });
    },
    onSuccess: () => {
      setAdding(false);
      setTitle("");
      setContent("");
      setFile(null);
      queryClient.invalidateQueries({ queryKey: ["documents", projectId] });
    },
  });

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader title="Documents" subtitle="Specifications given to the agents as project context"
        action={!adding && <Button onClick={() => setAdding(true)}>Add document</Button>} />
      {adding && (
        <Card title="Add a specification" className="mb-5">
          <div className="space-y-3">
            <Field label="Title"><Input value={title} onChange={(e) => setTitle(e.target.value)} disabled={!!file} /></Field>
            <Field label="Content (Markdown)"><Textarea rows={10} value={content} onChange={(e) => setContent(e.target.value)} disabled={!!file} /></Field>
            <input type="file" accept=".md,.markdown,.txt" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="text-[13px] text-muted" />
            <ErrorText error={add.error} />
            <div className="flex gap-2">
              <Button onClick={() => add.mutate()} disabled={add.isPending || (!file && (!title || !content))}>Save</Button>
              <Button variant="ghost" onClick={() => setAdding(false)}>Cancel</Button>
            </div>
          </div>
        </Card>
      )}
      {docs.data?.length === 0 && !adding && <Empty title="No document yet">Import the specification so your Lead can analyze it.</Empty>}
      <div className="space-y-2">
        {docs.data?.map((d) => (
          <div key={d.id} className="rounded-lg border border-line bg-panel">
            <button className="flex w-full items-center gap-3 px-4 py-3 text-left" onClick={() => setOpenId(openId === d.id ? null : d.id)}>
              <span className="flex-1 font-medium">{d.title}</span>
              <Badge>{d.kind.toLowerCase()}</Badge>
              <span className="text-[12px] text-muted">{bytes(d.sizeBytes)} · {dateTime(d.createdAt)}</span>
            </button>
            {openId === d.id && (
              <pre className="max-h-[60vh] overflow-auto whitespace-pre-wrap border-t border-line px-4 py-3 font-mono text-[12px] leading-relaxed">
                {open.data?.content ?? "Loading…"}
              </pre>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

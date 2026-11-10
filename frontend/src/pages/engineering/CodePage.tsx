import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useParams, useSearchParams } from "react-router";
import { api } from "../../api/client";
import type { FileChange } from "../../api/types";
import { DiffView, SourceView } from "../../components/DiffView";
import { Empty, PageHeader, Segmented, Select, SkeletonRows } from "../../components/ui";
import { parseDiff } from "../../lib/diff";
import { useBranches, useTree } from "../../lib/engineering";
import { bytes } from "../../lib/format";

/**
 * The repository (docs/10, view 2): files of a branch, or the changes of a task branch against main.
 * Read-only for now: your own edits go through a branch of yours (to come in this view).
 */
export function CodePage() {
  const { projectId = "" } = useParams();
  const [params, setParams] = useSearchParams();
  const branches = useBranches(projectId);
  const ref = params.get("ref") ?? "main";
  const [mode, setMode] = useState<"changes" | "files">(ref === "main" ? "files" : "changes");
  const [path, setPath] = useState<string | null>(params.get("path"));
  const tree = useTree(projectId, ref);
  const compare = useQuery({
    queryKey: ["repo", projectId, "compare", ref, path],
    queryFn: () => api.get<{ files: FileChange[]; diff: string }>(`/api/v1/projects/${projectId}/repo/compare?ref=${encodeURIComponent(ref)}${path ? `&path=${encodeURIComponent(path)}` : ""}`),
    enabled: ref !== "main",
  });
  const file = useQuery({
    queryKey: ["repo", projectId, "file", ref, path],
    queryFn: () => api.get<{ content: string }>(`/api/v1/projects/${projectId}/repo/file?ref=${encodeURIComponent(ref)}&path=${encodeURIComponent(path!)}`),
    enabled: !!path && mode === "files",
    retry: false,
  });
  const diffFile = useMemo(() => parseDiff(compare.data?.diff ?? "")[0], [compare.data]);
  const changed = new Map((compare.data?.files ?? []).map((f) => [f.path, f]));
  const list = mode === "changes" ? (compare.data?.files ?? []).map((f) => ({ path: f.path, size: 0 })) : tree.data ?? [];

  if (branches.isSuccess && branches.data.length === 0) {
    return (
      <div>
        <PageHeader title="Code" />
        <Empty title="No repository yet">The project's repository is created when the first task starts.</Empty>
      </div>
    );
  }
  return (
    <div>
      <PageHeader title="Code" subtitle="Read the project as it is on main, or what a task changed on its branch." action={
        <>
          <div className="w-80">
            <Select value={ref} aria-label="Branch" onChange={(e) => { setParams({ ref: e.target.value }); setPath(null); setMode(e.target.value === "main" ? "files" : "changes"); }}>
              {branches.data?.map((b) => <option key={b.name} value={b.name}>{b.name}{b.ahead ? ` · ${b.ahead} ahead` : ""}</option>)}
            </Select>
          </div>
          {ref !== "main" && <Segmented value={mode} onChange={(m) => { setMode(m); setPath(null); }} items={[{ value: "changes", label: "Changes vs main" }, { value: "files", label: "All files" }]} />}
        </>
      } />
      <div className="grid min-h-[60vh] gap-0 overflow-hidden rounded-md border border-line lg:grid-cols-[280px_1fr]">
        <nav className="border-b border-line bg-panel-2/50 p-2 lg:border-b-0 lg:border-r" aria-label="Files">
          {(tree.isLoading || compare.isLoading) && <SkeletonRows rows={6} />}
          <ul className="font-mono text-[12px]">
            {list.map((e) => {
              const c = changed.get(e.path);
              return (
                <li key={e.path}>
                  <button onClick={() => setPath(e.path)} className={`flex w-full items-center gap-2 rounded px-2 py-1 text-left hover:bg-panel-2 ${path === e.path ? "bg-panel-2 text-fg" : "text-muted"}`}>
                    <span className="min-w-0 flex-1 truncate">{e.path}</span>
                    {c && <span className={`text-[10px] font-semibold ${c.status === "A" ? "text-ok" : c.status === "D" ? "text-danger" : "text-warn"}`}>{c.status}</span>}
                    {mode === "files" && e.size > 0 && <span className="text-[10px] text-faint">{bytes(e.size)}</span>}
                  </button>
                </li>
              );
            })}
            {list.length === 0 && !tree.isLoading && !compare.isLoading && <li className="px-2 py-1 text-faint">{mode === "changes" ? "No change on this branch yet." : "Empty."}</li>}
          </ul>
        </nav>
        <div className="min-w-0 bg-panel">
          {!path && <p className="p-4 text-[13px] text-muted">Choose a file.</p>}
          {path && <p className="border-b border-line bg-panel-2 px-3 py-1.5 font-mono text-[12px]">{path}</p>}
          {path && mode === "files" && file.data && <SourceView content={file.data.content} />}
          {path && mode === "files" && file.isError && <p className="p-4 text-[13px] text-muted">This file does not exist on {ref}.</p>}
          {path && mode === "changes" && diffFile && <DiffView file={diffFile} />}
        </div>
      </div>
    </div>
  );
}

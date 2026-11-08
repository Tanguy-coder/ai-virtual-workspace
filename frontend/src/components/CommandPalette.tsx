import { CornerDownLeft, KanbanSquare, Search, User } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { useAgents, useTasks } from "../lib/queries";
import { useSelection } from "../lib/selection";
import { NAV, SPACES } from "./Shell";

export function useCommandPalette() {
  const [isOpen, setOpen] = useState(false);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen((o) => !o);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
  return { isOpen, open: () => setOpen(true), close: () => setOpen(false) };
}

interface Item { id: string; label: string; hint: string; kind: "page" | "agent" | "task"; run: () => void }

/** Ctrl/⌘ K: go to any page, agent or task of the current project. */
export function CommandPalette({ state, workspaceId, projectId }: {
  state: ReturnType<typeof useCommandPalette>; workspaceId: string; projectId?: string;
}) {
  const navigate = useNavigate();
  const agents = useAgents(projectId ?? "");
  const tasks = useTasks(projectId ?? "");
  const [q, setQ] = useState("");
  const [index, setIndex] = useState(0);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (state.isOpen) { setQ(""); setIndex(0); setTimeout(() => input.current?.focus(), 0); }
  }, [state.isOpen]);

  const items = useMemo<Item[]>(() => {
    const base = `/w/${workspaceId}`;
    const list: Item[] = [
      { id: "projects", label: "Projects", hint: "Workspace", kind: "page", run: () => navigate(`${base}/projects`) },
      { id: "inbox", label: "Inbox", hint: "Workspace", kind: "page", run: () => navigate(`${base}/inbox`) },
      { id: "settings", label: "Settings", hint: "Workspace", kind: "page", run: () => navigate(`${base}/settings`) },
    ];
    if (projectId) {
      for (const s of SPACES) for (const n of NAV[s.id]) {
        list.push({ id: `p-${n.to}`, label: n.label, hint: s.label, kind: "page", run: () => navigate(`${base}/p/${projectId}/${n.to}`) });
      }
      for (const a of agents.data ?? []) {
        list.push({ id: a.id, label: a.name, hint: a.title, kind: "agent", run: () => useSelection.getState().open(a.id) });
      }
      for (const t of tasks.data ?? []) {
        list.push({ id: t.id, label: `T-${t.number} ${t.title}`, hint: t.status.toLowerCase().replace("_", " "), kind: "task", run: () => useSelection.getState().openTask(t.id) });
      }
    }
    const needle = q.trim().toLowerCase();
    return needle ? list.filter((i) => `${i.label} ${i.hint}`.toLowerCase().includes(needle)).slice(0, 12) : list.slice(0, 12);
  }, [q, workspaceId, projectId, agents.data, tasks.data, navigate]);

  if (!state.isOpen) return null;
  const choose = (i: Item | undefined) => { if (!i) return; state.close(); i.run(); };

  return (
    <div className="fixed inset-0 z-[60] flex items-start justify-center bg-[rgba(24,25,28,0.28)] px-4 pt-[14vh]" onMouseDown={state.close}>
      <div className="slide-in w-full max-w-[560px] overflow-hidden rounded-lg border border-line bg-panel shadow-panel" onMouseDown={(e) => e.stopPropagation()}
        role="dialog" aria-label="Go to">
        <div className="flex items-center gap-2.5 border-b border-line px-4">
          <Search size={15} className="text-faint" />
          <input ref={input} value={q} placeholder="Go to a page, an agent or a task…"
            className="h-12 flex-1 bg-transparent text-[14px] outline-none placeholder:text-faint"
            onChange={(e) => { setQ(e.target.value); setIndex(0); }}
            onKeyDown={(e) => {
              if (e.key === "Escape") state.close();
              if (e.key === "ArrowDown") { e.preventDefault(); setIndex((i) => Math.min(i + 1, items.length - 1)); }
              if (e.key === "ArrowUp") { e.preventDefault(); setIndex((i) => Math.max(i - 1, 0)); }
              if (e.key === "Enter") choose(items[index]);
            }} />
          <kbd className="font-mono text-[11px] text-faint">Esc</kbd>
        </div>
        <ul className="max-h-[360px] overflow-y-auto p-1">
          {items.length === 0 && <li className="px-3 py-6 text-[13px] text-faint">Nothing matches “{q}”.</li>}
          {items.map((i, k) => (
            <li key={i.id}>
              <button onMouseEnter={() => setIndex(k)} onClick={() => choose(i)}
                className={`flex h-9 w-full items-center gap-3 rounded-sm px-3 text-left text-[13px] ${k === index ? "bg-panel-2" : ""}`}>
                <span className="w-4 text-faint">{i.kind === "agent" ? <User size={14} /> : i.kind === "task" ? <KanbanSquare size={14} /> : <span className="block h-1.5 w-1.5 rotate-45 bg-line-strong" />}</span>
                <span className="flex-1 truncate">{i.label}</span>
                <span className="truncate text-[12px] text-faint">{i.hint}</span>
                {k === index && <CornerDownLeft size={13} className="text-faint" />}
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

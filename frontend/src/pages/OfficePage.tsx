import { useQuery } from "@tanstack/react-query";
import { Maximize2, RotateCcw, RotateCw, ZoomIn, ZoomOut } from "lucide-react";
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import { api } from "../api/client";
import type { Agent, Meeting } from "../api/types";
import { AgentAvatar, DEPARTMENT_LABELS, StateDot } from "../components/AgentAvatar";
import { Button, Segmented } from "../components/ui";
import { useSelection } from "../lib/selection";
import { FloorPlan, useOfficeCamera } from "../office3d/FloorPlan";
import type { Level, OfficeCanvasHandle } from "../office3d/OfficeCanvas";
import { ROOM_BY_ID, ROOMS, type RoomId } from "../office3d/plan";
import { represent } from "../office3d/represent";
import { useRealtime } from "../realtime/realtime";

const OfficeCanvas = lazy(() => import("../office3d/OfficeCanvas").then((m) => ({ default: m.OfficeCanvas })));

function useMedia(query: string) {
  const [match, setMatch] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const m = window.matchMedia(query);
    const on = () => setMatch(m.matches);
    m.addEventListener("change", on);
    return () => m.removeEventListener("change", on);
  }, [query]);
  return match;
}

function useDarkTheme() {
  const read = () => document.documentElement.dataset.theme === "dark";
  const [dark, setDark] = useState(read);
  useEffect(() => {
    const o = new MutationObserver(() => setDark(read()));
    o.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    return () => o.disconnect();
  }, []);
  return dark;
}

export function OfficePage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const { agentId, open, close } = useSelection();
  const arrivals = useRealtime((s) => s.arrivals);
  const links = useRealtime((s) => s.links);
  const [mode, setMode] = useState<"floor" | "list">("floor");
  const [room, setRoom] = useState<RoomId | null>(null);
  const canvas = useRef<OfficeCanvasHandle>(null);
  const small = !useMedia("(min-width: 768px)");
  const wide = useMedia("(min-width: 1024px)");
  const reducedMotion = useMedia("(prefers-reduced-motion: reduce)");
  const dark = useDarkTheme();

  const agents = useQuery({ queryKey: ["agents", projectId], queryFn: () => api.get<Agent[]>(`/api/v1/projects/${projectId}/agents`) });
  const meetings = useQuery({ queryKey: ["meetings", projectId], queryFn: () => api.get<Meeting[]>(`/api/v1/projects/${projectId}/meetings`) });

  const view = useMemo(() => represent(agents.data ?? [], meetings.data ?? []), [agents.data, meetings.data]);
  const byId = useMemo(() => new Map((agents.data ?? []).map((a) => [a.id, a])), [agents.data]);
  const names = useMemo(() => new Map((agents.data ?? []).map((a) => [a.id, a.name])), [agents.data]);
  const arrivalSet = useMemo(() => new Set(arrivals), [arrivals]);

  // Walk between places only for changes received after the page has its data; the initial load just places people.
  const loaded = agents.isSuccess && meetings.isSuccess;
  const [walkOnChange, setWalkOnChange] = useState(false);
  useEffect(() => {
    if (!loaded) return;
    const t = setTimeout(() => setWalkOnChange(true), 1200);
    return () => clearTimeout(t);
  }, [loaded]);

  const selected = agentId ? view.figures.find((f) => f.agentId === agentId) : undefined;
  const level: Level = selected ? { kind: "agent", agentId: selected.agentId, room: selected.spot.room } : room ? { kind: "room", room } : { kind: "company" };

  const goRoom = useCallback((id: RoomId) => {
    close();
    setRoom(id);
  }, [close]);
  const overview = () => {
    close();
    setRoom(null);
  };

  // Keyboard: 1–9 rooms, Q/E quarter turns, Esc one level up (the drawer closes itself on Esc).
  const agentOpen = useRef(agentId);
  agentOpen.current = agentId;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (t && (t.closest("input, textarea, select, [contenteditable=true]") || e.metaKey || e.ctrlKey || e.altKey)) return;
      if (e.key === "Escape") {
        if (agentOpen.current) return;
        setRoom(null);
        return;
      }
      const r = ROOMS.find((x) => x.key === e.key);
      if (r) goRoom(r.id);
      if (e.key === "q" || e.key === "Q") canvas.current?.rotate(1);
      if (e.key === "e" || e.key === "E") canvas.current?.rotate(-1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [goRoom]);

  const onTarget = useCallback((t: { x: number; z: number; span: number }) => useOfficeCamera.setState(t), []);
  const linkArcs = useMemo(() => links.map((l) => ({ id: l.id, from: l.from, to: l.to, at: l.at })), [links]);

  const crumbRoom = level.kind === "company" ? null : level.room;
  const total = view.figures.length;
  const thinking = view.figures.filter((f) => f.lantern === "thinking").length;

  return (
    <div className="absolute inset-0 overflow-hidden">
      {mode === "floor" && !small && (
        <div className="absolute inset-0">
          <Suspense fallback={<p className="p-6 text-[13px] text-muted">Loading the office…</p>}>
            <OfficeCanvas ref={canvas} view={view} agents={byId} level={level} arrivals={arrivalSet} links={linkArcs}
              walkOnChange={walkOnChange} reducedMotion={reducedMotion} drawerWidth={selected && wide ? 520 : 0} dark={dark}
              onSelect={open} onRoom={goRoom} onTarget={onTarget} />
          </Suspense>
        </div>
      )}
      {mode === "floor" && small && (
        <div className="absolute inset-x-0 bottom-0 top-24 overflow-auto px-3 pb-20">
          <FloorPlan view={view} focus={room} selectedId={agentId} onRoom={goRoom} onAgent={open} names={names} className="w-full" />
          <p className="mt-2 text-[12px] text-faint">2D plan of the same floor. Open the office on a larger screen for the 3D view.</p>
        </div>
      )}
      {mode === "list" && (
        <div className="absolute inset-x-0 bottom-0 top-28 overflow-y-auto px-5 pb-6">
          <table className="w-full max-w-4xl text-[13px]">
            <caption className="sr-only">Agents and their current state</caption>
            <thead className="text-left text-[12px] text-muted">
              <tr><th className="py-2 font-medium">Agent</th><th className="font-medium">Department</th><th className="font-medium">Where</th><th className="font-medium">State</th><th className="font-medium">Model</th></tr>
            </thead>
            <tbody>
              {agents.data?.filter((a) => a.status !== "DISMISSED").map((a) => {
                const f = view.figures.find((x) => x.agentId === a.id);
                return (
                  <tr key={a.id} className="border-t border-line">
                    <td className="py-2">
                      <button onClick={() => open(a.id)} className="flex items-center gap-2.5 text-left hover:underline">
                        <AgentAvatar agent={a} size={28} />
                        <span><span className="block font-medium">{a.name}</span><span className="text-[12px] text-muted">{a.title}</span></span>
                      </button>
                    </td>
                    <td className="text-muted">{DEPARTMENT_LABELS[a.department]}</td>
                    <td className="text-muted">{f ? ROOM_BY_ID[f.spot.room].name : "—"}</td>
                    <td><StateDot agent={a} /></td>
                    <td className="font-mono text-[12px] text-muted">{a.model ?? "—"}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Title and breadcrumb */}
      <div className="pointer-events-none absolute left-3 top-3 flex flex-col gap-2 md:left-5 md:top-4">
        <div className="pointer-events-auto rounded-md bg-graphite px-3.5 py-2.5 text-graphite-fg">
          <p className="flex items-center gap-2 text-[13px] font-semibold"><span className="h-1.5 w-1.5 rounded-full bg-accent" />Virtual Office</p>
          <p className="font-mono text-[11px] text-graphite-muted">{total} agents · {thinking} thinking · every move is a recorded event</p>
        </div>
        {mode === "floor" && (
          <nav aria-label="Office level" className="pointer-events-auto flex items-center gap-1.5 text-[12px] text-muted">
            {level.kind === "company" ? <b className="font-medium text-fg">Company</b> : <button onClick={overview} className="rounded border border-line-strong bg-panel px-2 py-px text-fg hover:border-fg">Company</button>}
            {crumbRoom && <>
              <span>›</span>
              {level.kind === "agent" ? <button onClick={() => goRoom(crumbRoom)} className="rounded border border-line-strong bg-panel px-2 py-px text-fg hover:border-fg">{ROOM_BY_ID[crumbRoom].name}</button>
                : <b className="font-medium text-fg">{ROOM_BY_ID[crumbRoom].name}</b>}
            </>}
            {level.kind === "agent" && <><span>›</span><b className="font-medium text-fg">{byId.get(level.agentId)?.name}</b></>}
            {level.kind === "room" && ROOM_BY_ID[level.room].department && (
              <Link to={`/w/${workspaceId}/p/${projectId}/recruitment?department=${ROOM_BY_ID[level.room].department}`}
                className="ml-1 rounded border border-line-strong bg-panel px-2 py-px text-fg hover:border-fg">Hire into this team</Link>
            )}
          </nav>
        )}
      </div>

      {/* Tools */}
      <div className="absolute right-3 top-3 flex flex-wrap justify-end gap-1.5 transition-[right] md:right-5 md:top-4" style={{ right: selected && wide ? 540 : undefined }}>
        <Segmented value={mode} onChange={setMode} items={[{ value: "floor", label: "Floor" }, { value: "list", label: "List" }]} />
        {mode === "floor" && !small && !(selected && wide) && <>
          <select aria-label="Go to a department" value={room ?? ""} onChange={(e) => (e.target.value ? goRoom(e.target.value as RoomId) : overview())}
            className="h-8 rounded border border-line-strong bg-panel px-2 text-[12px] text-fg">
            <option value="">Departments…</option>
            {ROOMS.filter((r) => r.id !== "lounge").map((r) => <option key={r.id} value={r.id}>{r.key ? `${r.key} · ` : ""}{r.name}</option>)}
          </select>
          <Button variant="ghost" size="sm" aria-label="Whole floor" title="Whole floor (Esc)" onClick={overview}><Maximize2 size={14} /></Button>
          <Button variant="ghost" size="sm" aria-label="Rotate left" title="Rotate (Q)" onClick={() => canvas.current?.rotate(1)}><RotateCcw size={14} /></Button>
          <Button variant="ghost" size="sm" aria-label="Rotate right" title="Rotate (E)" onClick={() => canvas.current?.rotate(-1)}><RotateCw size={14} /></Button>
          <Button variant="ghost" size="sm" aria-label="Zoom in" onClick={() => canvas.current?.zoom(1.25)}><ZoomIn size={14} /></Button>
          <Button variant="ghost" size="sm" aria-label="Zoom out" onClick={() => canvas.current?.zoom(1 / 1.25)}><ZoomOut size={14} /></Button>
        </>}
      </div>

      {mode === "floor" && !small && <>
        <div className="absolute bottom-4 left-5 w-[236px] rounded-md border border-line-strong bg-panel p-2.5">
          <p className="mb-2 flex justify-between text-[11px] font-semibold uppercase tracking-[0.08em] text-faint"><span>Floor plan</span><span className="font-normal normal-case tracking-normal">1–9 · Q/E · Esc</span></p>
          <FloorPlan view={view} focus={crumbRoom} selectedId={agentId} onRoom={goRoom} showViewport className="w-full" />
        </div>
        <RightNow view={view} agents={byId} onAgent={open} rightInset={selected && wide ? 540 : 20} />
      </>}
    </div>
  );
}

/** What is happening now, straight from the representation (no scenario, no sample data). */
function RightNow({ view, agents, onAgent, rightInset }: { view: ReturnType<typeof represent>; agents: Map<string, Agent>; onAgent(id: string): void; rightInset: number }) {
  const items: { key: string; tone: string; dia?: boolean; text: string; agent?: string }[] = [];
  for (const m of view.meetings) {
    const speaker = m.speakerId ? agents.get(m.speakerId)?.name : null;
    items.push({ key: m.meeting.id, tone: "bg-warn", text: `${m.meeting.title} in ${ROOM_BY_ID[m.room].name}${speaker ? ` · ${speaker} speaking` : m.meeting.status === "SYNTHESIZING" ? " · writing minutes" : ""}` });
  }
  for (const f of view.figures) {
    const a = agents.get(f.agentId);
    if (!a) continue;
    if (f.lantern === "blocked") items.push({ key: f.agentId, tone: "bg-danger", dia: true, text: `${a.name} · ${f.label}`, agent: f.agentId });
    else if (f.lantern === "thinking") items.push({ key: f.agentId, tone: "bg-active", text: `${a.name} thinking`, agent: f.agentId });
  }
  return (
    <div className="absolute bottom-4 left-[272px] flex items-center gap-4 overflow-hidden rounded-md border border-line-strong bg-panel px-3.5 py-2.5" style={{ right: rightInset }}>
      <p className="shrink-0 text-[11px] font-semibold uppercase tracking-[0.08em] text-faint">Right now</p>
      {items.length === 0 && <p className="truncate text-[12px] text-muted">Nothing running. Agents light up here when a model call, a meeting or a blocker is recorded.</p>}
      {items.slice(0, 8).map((it, i) => (
        <span key={it.key} className="flex shrink-0 items-center gap-4">
          {i > 0 && <span className="h-4 w-px bg-line" />}
          <button disabled={!it.agent} onClick={() => it.agent && onAgent(it.agent)} className="flex items-center gap-1.5 whitespace-nowrap text-[12px] enabled:hover:underline">
            <i className={`inline-block h-[7px] w-[7px] ${it.tone} ${it.dia ? "rotate-45" : "rounded-full"}`} />{it.text}
          </button>
        </span>
      ))}
    </div>
  );
}

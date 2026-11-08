import { useQuery } from "@tanstack/react-query";
import { Maximize2, ZoomIn, ZoomOut } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router";
import { api } from "../api/client";
import type { Agent, Meeting } from "../api/types";
import { AgentAvatar, agentState, DEPARTMENT_LABELS, StateDot } from "../components/AgentAvatar";
import { Button, Segmented } from "../components/ui";
import { useSelection } from "../lib/selection";
import { OfficeScene } from "../office/OfficeScene";
import { useRealtime } from "../realtime/realtime";

export function OfficePage() {
  const { workspaceId = "", projectId = "" } = useParams();
  const navigate = useNavigate();
  const host = useRef<HTMLDivElement>(null);
  const scene = useRef<OfficeScene | null>(null);
  const { agentId, open } = useSelection();
  const arrivals = useRealtime((s) => s.arrivals);
  const [view, setView] = useState<"floor" | "list">("floor");
  const [hover, setHover] = useState<{ id: string; x: number; y: number } | null>(null);
  const agents = useQuery({ queryKey: ["agents", projectId], queryFn: () => api.get<Agent[]>(`/api/v1/projects/${projectId}/agents`) });
  const meetings = useQuery({ queryKey: ["meetings", projectId], queryFn: () => api.get<Meeting[]>(`/api/v1/projects/${projectId}/meetings`) });
  const links = useRealtime((s) => s.links);
  const shown = useRef(new Set<string>());
  const open$ = (meetings.data ?? []).filter((m) => m.status === "IN_PROGRESS" || m.status === "SYNTHESIZING");
  const openKey = open$.map((m) => m.id + m.participantIds.join()).join("|");

  useEffect(() => {
    if (!host.current) return;
    const s = new OfficeScene(host.current, {
      onSelect: (id) => open(id),
      onHover: (id, p) => setHover(id && p ? { id, ...p } : null),
      onHire: (department) => navigate(`/w/${workspaceId}/p/${projectId}/recruitment?department=${department}`),
    });
    scene.current = s;
    void s.mount();
    const observer = new MutationObserver(() => s.retheme());
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    return () => {
      observer.disconnect();
      s.destroy();
      scene.current = null;
    };
  }, [open, navigate, workspaceId, projectId]);

  useEffect(() => {
    scene.current?.setAgents(agents.data ?? [], new Set(arrivals));
  }, [agents.data, arrivals]);

  useEffect(() => {
    scene.current?.setMeetings(open$);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openKey]);

  useEffect(() => {
    for (const l of links) {
      if (shown.current.has(l.id) || Date.now() - l.at > 4000) continue;
      shown.current.add(l.id);
      scene.current?.link(l.from, l.to, l.type);
    }
  }, [links]);

  useEffect(() => {
    scene.current?.select(agentId);
  }, [agentId]);

  const hovered = agents.data?.find((a) => a.id === hover?.id);
  return (
    <div className="absolute inset-0">
      <div ref={host} className={`absolute inset-0 ${view === "list" ? "invisible" : ""}`} aria-hidden={view === "list"} />
      {view === "list" && (
        <div className="absolute inset-x-0 bottom-0 top-28 overflow-y-auto px-5 pb-6">
          <table className="w-full max-w-4xl text-[13px]">
            <caption className="sr-only">Agents and their current state</caption>
            <thead className="text-left text-[12px] text-muted">
              <tr><th className="py-2 font-medium">Agent</th><th className="font-medium">Department</th><th className="font-medium">State</th><th className="font-medium">Model</th></tr>
            </thead>
            <tbody>
              {agents.data?.map((a) => (
                <tr key={a.id} className="border-t border-line">
                  <td className="py-2">
                    <button onClick={() => open(a.id)} className="flex items-center gap-2.5 text-left hover:underline">
                      <AgentAvatar agent={a} size={28} />
                      <span><span className="block font-medium">{a.name}</span><span className="text-[12px] text-muted">{a.title}</span></span>
                    </button>
                  </td>
                  <td className="text-muted">{DEPARTMENT_LABELS[a.department]}</td>
                  <td><StateDot agent={a} /></td>
                  <td className="font-mono text-[12px] text-muted">{a.model ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="absolute left-5 top-4 rounded-md bg-graphite px-3.5 py-2.5 text-graphite-fg">
        <p className="flex items-center gap-2 text-[13px] font-semibold"><span className="h-1.5 w-1.5 rounded-full bg-accent" />Virtual Office</p>
        <p className="font-mono text-[11px] text-graphite-muted">
          {(agents.data ?? []).length} agents · {(agents.data ?? []).filter((a) => a.status === "ACTIVE" && !["AVAILABLE", "BLOCKED"].includes(a.activity)).length} at work · every move is a recorded event
        </p>
      </div>
      <div className="absolute right-5 top-4 flex gap-1.5">
        <Segmented value={view} onChange={setView} items={[{ value: "floor", label: "Floor" }, { value: "list", label: "List" }]} />
        {view === "floor" && <>
        <Button variant="ghost" size="sm" aria-label="Zoom in" onClick={() => scene.current?.zoom(1.2)}><ZoomIn size={14} /></Button>
        <Button variant="ghost" size="sm" aria-label="Zoom out" onClick={() => scene.current?.zoom(1 / 1.2)}><ZoomOut size={14} /></Button>
        <Button variant="ghost" size="sm" aria-label="Fit" onClick={() => scene.current?.fit()}><Maximize2 size={14} /></Button>
        </>}
      </div>
      {view === "floor" && (
        <div className="absolute bottom-4 left-5 flex flex-wrap items-center gap-4 rounded-md border border-line bg-panel px-4 py-2 text-[12px] text-muted backdrop-blur">
          <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-ok" /> Available · screen off</span>
          <span className="flex items-center gap-1.5"><span className="aiw-pulse h-2 w-2 rounded-full bg-active" /> Thinking · bubble and lit screen</span>
          <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-warn" /> In meeting · seated in a room</span>
          <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-danger" /> Blocked · red badge</span>
          <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-faint" /> Paused · faded</span>
          <span className="flex items-center gap-1.5"><span className="h-0.5 w-4 rounded-full bg-ok" /> Message between agents</span>
          <span className="text-faint">Scroll to zoom · drag to move · click an agent</span>
        </div>
      )}
      {hover && hovered && (
        <div className="pointer-events-none absolute z-10 -translate-x-1/2 -translate-y-full rounded-lg border border-line-strong bg-panel-solid px-3 py-2 text-[12px] shadow-xl"
          style={{ left: hover.x, top: hover.y }}>
          <p className="font-semibold">{hovered.name}</p>
          <p className="text-muted">{hovered.title} · {agentState(hovered).label}</p>
        </div>
      )}
    </div>
  );
}

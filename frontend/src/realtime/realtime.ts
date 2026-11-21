import { Client } from "@stomp/stompjs";
import type { QueryClient } from "@tanstack/react-query";
import { create } from "zustand";
import { api } from "../api/client";
import type { EventRecord } from "../api/types";

type Status = "connecting" | "live" | "reconnecting" | "offline";

interface RealtimeState {
  status: Status;
  lastSeq: number;
  events: EventRecord[];
  /** Agents whose agent.hired event arrived live (not replayed); the office animates their entrance. */
  arrivals: string[];
  /** Messages between two agents received live; the office draws a line between their desks for a few seconds. */
  links: { id: string; from: string; to: string; type: string; at: number }[];
}

const MAX_EVENTS = 500;

export const useRealtime = create<RealtimeState>(() => ({ status: "offline", lastSeq: -1, events: [], arrivals: [], links: [] }));

/** Query keys refreshed when an event of the given type arrives. */
function keysFor(e: EventRecord): unknown[][] {
  const keys: unknown[][] = [["events", e.workspaceId]];
  if (e.projectId) keys.push(["dashboard", e.projectId], ["budget", e.projectId]);
  if (e.type.startsWith("agent.") && e.projectId) keys.push(["agents", e.projectId], ["agent", e.subject.id]);
  if (e.type.startsWith("message.")) keys.push(["messages", e.subject.id]);
  if (e.type.startsWith("document.") && e.projectId) keys.push(["documents", e.projectId]);
  if (e.type.startsWith("project.")) keys.push(["projects", e.workspaceId]);
  if (e.type.startsWith("provider.")) keys.push(["providers", e.workspaceId], ["agents"]);
  if (e.type.startsWith("approval.")) keys.push(["approvals", e.workspaceId], ["workspace-dashboard", e.workspaceId]);
  const p = e.projectId;
  if (p && (e.type.startsWith("briefing.") || e.type.startsWith("hiring_proposal."))) {
    keys.push(["briefings", p], ["briefing"], ["waiting", e.workspaceId]);
  }
  if (p && (e.type.startsWith("task.") || e.type === "plan.created")) keys.push(["tasks", p], ["task", e.subject.id], ["history", e.subject.id]);
  if (p && e.type.startsWith("meeting.")) keys.push(["meetings", p], ["meeting", e.subject.id]);
  if (p && e.type.startsWith("realignment.")) keys.push(["meetings", p], ["meeting", e.subject.id], ["runs", p], ["run"], ["tasks", p]);
  if (p && e.type === "message.sent") {
    keys.push(["thread", e.payload.threadId], ["conversations", p]);
    if (e.payload.threadKind === "MEETING") keys.push(["meeting", e.payload.subjectId]);
    if (e.payload.threadKind === "BRIEFING") keys.push(["briefing"]);
  }
  if (p && e.type === "memory.recorded") keys.push(["memory", p]);
  if (p && e.type.startsWith("job.")) keys.push(["jobs", p], ["agents", p]);
  if (e.type === "agent.step_completed") keys.push(["steps", e.subject.id]);
  if (p && e.type.startsWith("task_run.")) {
    keys.push(["runs", p], ["run", e.payload.taskId], ["agents", p]);
    if (e.type !== "task_run.step") keys.push(["tasks", p], ["branches", p]);
  }
  if (p && e.type.startsWith("branch.")) keys.push(["branches", p], ["repo", p]);
  if (p && e.type === "test_run.completed") keys.push(["test-runs", p], ["mr"]);
  if (p && (e.type.startsWith("security_scan.") || e.type.startsWith("finding."))) keys.push(["security", p], ["tasks", p]);
  if (p && e.type.startsWith("merge_request.")) {
    keys.push(["merge-requests", p], ["mr", e.subject.id], ["tasks", p], ["branches", p], ["run", e.payload.taskId]);
    if (e.type === "merge_request.merged") keys.push(["repo", p]);
  }
  return keys;
}

function accept(events: EventRecord[], queryClient: QueryClient, live: boolean) {
  const state = useRealtime.getState();
  const fresh = events.filter((e) => e.seq > state.lastSeq).sort((a, b) => a.seq - b.seq);
  if (fresh.length === 0) return;
  const hired = live ? fresh.filter((e) => e.type === "agent.hired").map((e) => e.subject.id) : [];
  const links = live
    ? fresh.filter((e) => e.type === "message.sent" && e.payload.fromAgentId && e.payload.toAgentId).map((e) => ({
        id: e.id, from: String(e.payload.fromAgentId), to: String(e.payload.toAgentId), type: String(e.payload.type), at: Date.now(),
      }))
    : [];
  useRealtime.setState({
    links: links.length ? [...state.links, ...links].slice(-30) : state.links,
    lastSeq: fresh[fresh.length - 1].seq,
    events: [...state.events, ...fresh].slice(-MAX_EVENTS),
    arrivals: hired.length ? [...state.arrivals, ...hired].slice(-50) : state.arrivals,
  });
  const seen = new Set<string>();
  for (const e of fresh) {
    for (const key of keysFor(e)) {
      const id = JSON.stringify(key);
      if (seen.has(id)) continue;
      seen.add(id);
      queryClient.invalidateQueries({ queryKey: key });
    }
  }
}

/**
 * Subscribes to the workspace topic. On every (re)connection it first replays the events recorded
 * since the last seen seq through REST, so nothing is missed while the socket was down.
 */
export function connectRealtime(workspaceId: string, queryClient: QueryClient): () => void {
  useRealtime.setState({ status: "connecting", lastSeq: -1, events: [], arrivals: [], links: [] });
  const scheme = window.location.protocol === "https:" ? "wss" : "ws";
  const client = new Client({
    brokerURL: `${scheme}://${window.location.host}/ws`,
    reconnectDelay: 2000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
  });

  client.onConnect = async () => {
    client.subscribe(`/topic/workspace.${workspaceId}`, (frame) => {
      accept([JSON.parse(frame.body) as EventRecord], queryClient, useRealtime.getState().status === "live");
    });
    const { lastSeq } = useRealtime.getState();
    const missed = await api.get<EventRecord[]>(
      `/api/v1/workspaces/${workspaceId}/events?afterSeq=${lastSeq}&limit=500`,
    );
    accept(missed, queryClient, false);
    if (lastSeq >= 0) queryClient.invalidateQueries();
    useRealtime.setState({ status: "live" });
  };
  client.onWebSocketClose = () => useRealtime.setState({ status: "reconnecting" });
  client.onStompError = () => useRealtime.setState({ status: "reconnecting" });
  client.activate();

  return () => {
    void client.deactivate();
    useRealtime.setState({ status: "offline" });
  };
}

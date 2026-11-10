import type { Agent } from "../api/types";
import { assignSeats, placeMeetings, type MeetingPlacement, type OpenMeeting, type RoomId, type Seat, type Spot } from "./plan";

/**
 * Representation engine: recorded state → what the Virtual Office shows.
 * Pure and deterministic, so no posture, screen content or light can exist without a recorded state behind it.
 */

export type Posture =
  | "type" // seated, a model call is running for this agent
  | "idle" // seated, nothing running
  | "crossed" // seated, arms crossed: blocked
  | "table" // seated at a meeting table, listening
  | "talk" // seated at a meeting table, taking their turn
  | "present" // standing at the wall screen, taking their turn
  | "stand"; // standing in a meeting room

/** Desk screen content. "generating" only while a model call runs; "blocked" shows the recorded reason. */
export type Screen = "off" | "generating" | "working" | "blocked";

/** State light above the head. */
export type Lantern = "thinking" | "meeting" | "blocked" | null;

export interface Figure {
  agentId: string;
  /** Room of the agent's own desk. */
  home: RoomId;
  spot: Spot;
  posture: Posture;
  lantern: Lantern;
  faded: boolean;
  /** Short state label shown on the name tag. */
  label: string;
  tone: "active" | "warn" | "danger" | "ok" | "muted";
}

export interface DeskView {
  seat: Seat;
  screen: Screen;
  /** The occupant is sitting at this desk right now. */
  occupied: boolean;
  blockedReason: string | null;
}

export interface OfficeView {
  figures: Figure[];
  desks: DeskView[];
  meetings: MeetingPlacement[];
}

type AgentLike = Pick<Agent, "id" | "department" | "lead" | "createdAt" | "status" | "activity" | "blockedReason">;

export function represent(agents: AgentLike[], meetings: OpenMeeting[]): OfficeView {
  const present = agents.filter((a) => a.status !== "DISMISSED");
  const seats = assignSeats(present);
  const placements = placeMeetings(meetings.filter((m) => m.status === "IN_PROGRESS" || m.status === "SYNTHESIZING"));
  const inRoom = new Map<string, { p: MeetingPlacement; spot: MeetingPlacement["spots"] extends Map<string, infer S> ? S : never }>();
  for (const p of placements) for (const [id, spot] of p.spots) inRoom.set(id, { p, spot });

  const figures: Figure[] = [];
  const desks: DeskView[] = [];
  for (const a of present) {
    const seat = seats.get(a.id)!;
    const paused = a.status === "PAUSED";
    const thinking = !paused && a.activity === "THINKING";
    // Working on a task: a run is open (tools, tests, commits) even between model calls.
    const working = !paused && a.activity === "WORKING";
    const blocked = !paused && a.activity === "BLOCKED";
    const meeting = paused ? undefined : inRoom.get(a.id);

    let posture: Posture;
    let spot: Spot = seat;
    if (meeting) {
      spot = meeting.spot;
      const speaking = meeting.p.speakerId === a.id;
      posture = meeting.spot.role === "seated" ? (speaking ? "talk" : "table") : meeting.spot.role === "presenter" && speaking ? "present" : "stand";
    } else if (seat.overflow) {
      posture = "stand";
    } else {
      posture = blocked ? "crossed" : thinking || working ? "type" : "idle";
    }

    const lantern: Lantern = paused ? null : blocked ? "blocked" : thinking || working ? "thinking" : meeting || a.activity === "IN_MEETING" ? "meeting" : null;
    figures.push({ agentId: a.id, home: seat.room, spot, posture, lantern, faded: paused, ...stateLabel(a, !!meeting, meeting?.p.speakerId === a.id) });

    if (!seat.overflow) {
      const atDesk = !meeting;
      desks.push({
        seat,
        occupied: atDesk,
        screen: !atDesk || paused ? "off" : thinking ? "generating" : working ? "working" : blocked ? "blocked" : "off",
        blockedReason: blocked ? a.blockedReason : null,
      });
    }
  }
  return { figures, desks, meetings: placements };
}

function stateLabel(a: AgentLike, inMeeting: boolean, speaking: boolean): Pick<Figure, "label" | "tone"> {
  if (a.status === "PAUSED") return { label: "Paused", tone: "muted" };
  if (a.activity === "BLOCKED") return { label: a.blockedReason ? `Blocked · ${a.blockedReason}` : "Blocked", tone: "danger" };
  if (inMeeting) return { label: speaking ? "In meeting · speaking" : "In meeting", tone: "warn" };
  if (a.activity === "THINKING") return { label: "Thinking · model call running", tone: "active" };
  if (a.activity === "WORKING") return { label: "Working on a task · tools and tests", tone: "active" };
  if (a.activity === "IN_MEETING") return { label: "In meeting", tone: "warn" };
  if (a.activity === "AVAILABLE") return { label: "Available", tone: "ok" };
  return { label: a.activity.toLowerCase(), tone: "muted" };
}

/** Per-room summary for the room signs, from the same view. */
export function roomSummary(view: OfficeView, roomId: RoomId): { count: number; thinking: number; away: number; blocked: number } {
  const home = view.figures.filter((f) => f.home === roomId);
  return {
    count: home.length,
    thinking: home.filter((f) => f.lantern === "thinking").length,
    away: home.filter((f) => f.spot.room !== roomId).length,
    blocked: home.filter((f) => f.lantern === "blocked").length,
  };
}

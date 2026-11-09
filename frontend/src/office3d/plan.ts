import type { Agent, Department } from "../api/types";

/**
 * Floor plan of the Virtual Office in metres (docs/07-virtual-office-proposition.md).
 * Plan coordinates: x to the east (0..W), z to the south (0..D). The 3D world centres the floor at the origin.
 */
export const W = 40;
export const D = 32;
export const OX = -W / 2;
export const OZ = -D / 2;
/** Corridors between the three rows of rooms. */
export const CORRIDOR_NORTH = 10.5;
export const CORRIDOR_SOUTH = 22.5;
/** The open collaboration space links both corridors. */
export const CROSSING_X = 30.5;
/** Where new hires come in (the reception desk in the lounge). */
export const RECEPTION = { x: 30.5, z: 29.2 };

export type RoomId =
  | "cto" | "arch" | "product" | "roomA" | "roomB" | "backend" | "frontend" | "specialists"
  | "collab" | "qa" | "security" | "devops" | "lounge";

export type FloorKind = "carpet" | "wood" | "dark";

/** A desk in room-local coordinates: the chair sits at (x, z), the person faces ry (0 = south). */
export interface DeskSpot { x: number; z: number; ry: number; wide?: boolean }

export interface Room {
  id: RoomId;
  name: string;
  x0: number; z0: number; x1: number; z1: number;
  /** Department whose agents sit here, if any. */
  department?: Department;
  kind: "department" | "meeting" | "office" | "common";
  floor: FloorKind;
  glass?: boolean;
  /** No walls: an open area of the floor. */
  open?: boolean;
  /** Keyboard shortcut to fly to the room. */
  key?: string;
  /** Hex colour of the room (department colour, used on floor tint, signs and lanyards). */
  color: string;
  desks: DeskSpot[];
}

const PI = Math.PI;

export const ROOMS: Room[] = [
  { id: "cto", name: "CTO Office", x0: 0, z0: 0, x1: 7, z1: 9, department: "DIRECTION", kind: "office", floor: "wood", glass: true, key: "1", color: "#6E6A86",
    desks: [{ x: 5.0, z: 3.6, ry: PI, wide: true }] },
  { id: "arch", name: "Software Architecture", x0: 7, z0: 0, x1: 16, z1: 9, department: "ARCHITECTURE", kind: "department", floor: "carpet", key: "2", color: "#4D7A99",
    desks: [{ x: 2.2, z: 2.9, ry: PI }, { x: 4.5, z: 2.9, ry: PI }, { x: 6.8, z: 2.9, ry: PI }, { x: 6.8, z: 6.4, ry: PI }] },
  { id: "product", name: "Product Management", x0: 16, z0: 0, x1: 24, z1: 9, department: "PRODUCT", kind: "department", floor: "carpet", key: "3", color: "#9A6A7E",
    desks: [{ x: 2.2, z: 2.9, ry: PI }, { x: 5.6, z: 2.9, ry: PI }, { x: 2.2, z: 5.8, ry: PI / 2 }, { x: 5.6, z: 5.8, ry: -PI / 2 }] },
  { id: "roomA", name: "Meeting Room A", x0: 24, z0: 0, x1: 33, z1: 9, kind: "meeting", floor: "dark", glass: true, color: "#B7832A", desks: [] },
  { id: "roomB", name: "Meeting Room B", x0: 33, z0: 0, x1: 40, z1: 9, kind: "meeting", floor: "dark", glass: true, color: "#B7832A", desks: [] },
  { id: "backend", name: "Backend Engineering", x0: 0, z0: 12, x1: 13, z1: 21, department: "BACKEND", kind: "department", floor: "carpet", key: "4", color: "#4E6FA8",
    desks: [
      ...[1.6, 4.0, 6.4, 8.8].map((x) => ({ x, z: 3.1, ry: PI, wide: x === 1.6 })),
      ...[1.6, 4.0, 6.4, 8.8].map((x) => ({ x, z: 6.8, ry: 0 })),
    ] },
  { id: "frontend", name: "Frontend Engineering", x0: 13, z0: 12, x1: 22, z1: 21, department: "FRONTEND", kind: "department", floor: "carpet", key: "5", color: "#4F8C7A",
    desks: [...[1.8, 4.4, 7.0].map((x) => ({ x, z: 3.1, ry: PI })), ...[1.8, 4.4, 7.0].map((x) => ({ x, z: 6.6, ry: 0 }))] },
  { id: "specialists", name: "Specialists", x0: 22, z0: 12, x1: 29, z1: 21, department: "OTHER", kind: "department", floor: "carpet", key: "6", color: "#5E8A9C",
    desks: [...[1.8, 4.6].map((x) => ({ x, z: 3.1, ry: PI })), ...[1.8, 4.6].map((x) => ({ x, z: 6.6, ry: 0 }))] },
  { id: "collab", name: "Collaboration Space", x0: 29, z0: 12, x1: 40, z1: 21, kind: "common", floor: "wood", open: true, color: "#7A7F86", desks: [] },
  { id: "qa", name: "QA & Testing", x0: 0, z0: 24, x1: 9, z1: 32, department: "QA", kind: "department", floor: "carpet", key: "7", color: "#A08A4A",
    desks: [{ x: 2.0, z: 3.0, ry: PI }, { x: 2.0, z: 5.9, ry: PI }, { x: 5.0, z: 5.9, ry: PI }, { x: 7.4, z: 3.6, ry: -PI / 2 }] },
  { id: "security", name: "Cybersecurity", x0: 9, z0: 24, x1: 17, z1: 32, department: "SECURITY", kind: "department", floor: "dark", glass: true, key: "8", color: "#9C5A52",
    desks: [{ x: 2.2, z: 2.6, ry: PI }, { x: 5.4, z: 2.6, ry: PI }, { x: 2.2, z: 5.4, ry: PI }, { x: 5.4, z: 5.4, ry: PI }] },
  { id: "devops", name: "DevOps & Infrastructure", x0: 17, z0: 24, x1: 27, z1: 32, department: "DEVOPS", kind: "department", floor: "carpet", key: "9", color: "#A8744A",
    desks: [{ x: 6.8, z: 2.8, ry: PI, wide: true }, { x: 2.4, z: 5.6, ry: PI }, { x: 4.6, z: 5.6, ry: PI }, { x: 6.8, z: 5.6, ry: PI }] },
  { id: "lounge", name: "Reception & Lounge", x0: 27, z0: 24, x1: 40, z1: 32, kind: "common", floor: "wood", open: true, color: "#7A7F86", desks: [] },
];

/** The human CTO's own desk (never occupied by an agent). */
export const CTO_DESK: DeskSpot = { x: 2.3, z: 3.6, ry: PI, wide: true };

export const ROOM_BY_ID = Object.fromEntries(ROOMS.map((r) => [r.id, r])) as Record<RoomId, Room>;

export function roomOfDepartment(d: Department): Room {
  return ROOMS.find((r) => r.department === d) ?? ROOM_BY_ID.specialists;
}

export function roomAt(x: number, z: number): Room | undefined {
  return ROOMS.find((r) => x >= r.x0 && x <= r.x1 && z >= r.z0 && z <= r.z1);
}

export function roomCenter(r: Room) {
  return { x: (r.x0 + r.x1) / 2, z: (r.z0 + r.z1) / 2 };
}

/** Rooms of the north row open on their south wall, the others on their north wall, 1.0–2.3 m from the west corner. */
export function doorOf(r: Room): { x: number; z: number; corridor: number } {
  const x = r.x0 + 1.65;
  if (r.z0 === 0) return { x, z: r.z1, corridor: CORRIDOR_NORTH };
  if (r.z0 === 12) return { x, z: r.z0, corridor: CORRIDOR_NORTH };
  return { x, z: r.z0, corridor: CORRIDOR_SOUTH };
}

/** A place on the floor, in plan coordinates. */
export interface Spot { x: number; z: number; ry: number; room: RoomId }

/** Placement of one agent's desk. */
export interface Seat extends Spot { agentId: string; deskIndex: number; overflow: boolean; wide: boolean }

/**
 * Stable seating: a department's agents take its desks in hiring order, the Lead first.
 * Agents beyond capacity get a standing spot along the room's front so nobody is hidden.
 */
export function assignSeats(agents: Pick<Agent, "id" | "department" | "lead" | "createdAt">[]): Map<string, Seat> {
  const seats = new Map<string, Seat>();
  const byRoom = new Map<RoomId, typeof agents>();
  for (const a of agents) {
    const r = roomOfDepartment(a.department);
    byRoom.set(r.id, [...(byRoom.get(r.id) ?? []), a]);
  }
  for (const [roomId, list] of byRoom) {
    const r = ROOM_BY_ID[roomId];
    const ordered = [...list].sort((a, b) => Number(b.lead) - Number(a.lead) || a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id));
    ordered.forEach((a, i) => {
      const d = r.desks[i];
      if (d) {
        seats.set(a.id, { agentId: a.id, room: roomId, x: r.x0 + d.x, z: r.z0 + d.z, ry: d.ry, deskIndex: i, overflow: false, wide: !!d.wide });
      } else {
        const k = i - r.desks.length;
        const cols = Math.max(1, Math.floor((r.x1 - r.x0 - 1) / 0.9));
        const front = r.z0 === 0 ? r.z1 - 0.8 : r.z1 - 0.8;
        seats.set(a.id, {
          agentId: a.id, room: roomId, x: r.x0 + 3 + (k % cols) * 0.9, z: front - Math.floor(k / cols) * 0.9, ry: PI,
          deskIndex: -1, overflow: true, wide: false,
        });
      }
    });
  }
  return seats;
}

export interface OpenMeeting {
  id: string; title: string; startedAt: string; participantIds: string[]; organizerId: string;
  status: string; rounds: number; currentRound: number; turnIndex: number;
}

export interface MeetingPlacement {
  room: RoomId;
  meeting: OpenMeeting;
  /** Agent currently taking their turn, if the meeting is in progress. */
  speakerId: string | null;
  spots: Map<string, Spot & { role: "presenter" | "seated" | "standing" }>;
}

/** Table geometry of the two meeting rooms, room-local. */
export const MEETING_TABLES: Record<"roomA" | "roomB", { cx: number; cz: number; len: number; wide: number; seatsX: number[]; side: number; presenter: { x: number; z: number } }> = {
  roomA: { cx: 4.5, cz: 4.6, len: 4.2, wide: 1.5, seatsX: [-1.3, 0, 1.3], side: 1.05, presenter: { x: 7.35, z: 4.4 } },
  roomB: { cx: 3.3, cz: 4.6, len: 2.6, wide: 1.3, seatsX: [-0.7, 0.7], side: 0.95, presenter: { x: 5.5, z: 4.4 } },
};

export function meetingChairs(roomId: "roomA" | "roomB"): Spot[] {
  const t = MEETING_TABLES[roomId];
  const r = ROOM_BY_ID[roomId];
  const out: Spot[] = [];
  for (const side of [-1, 1]) for (const dx of t.seatsX) out.push({ x: r.x0 + t.cx + dx, z: r.z0 + t.cz + side * t.side, ry: side < 0 ? 0 : PI, room: roomId });
  return out;
}

/**
 * Open meetings take the two meeting rooms in start order. The organizer stands at the wall screen,
 * the other participants take the chairs, then stand along the glass wall. A participant already
 * placed in an earlier meeting stays there; meetings beyond two keep their participants at their desks.
 */
export function placeMeetings(meetings: OpenMeeting[]): MeetingPlacement[] {
  const rooms: ("roomA" | "roomB")[] = ["roomA", "roomB"];
  const placed = new Set<string>();
  return [...meetings].sort((a, b) => a.startedAt.localeCompare(b.startedAt) || a.id.localeCompare(b.id)).slice(0, rooms.length).map((m, i) => {
    const roomId = rooms[i];
    const r = ROOM_BY_ID[roomId];
    const t = MEETING_TABLES[roomId];
    const chairs = meetingChairs(roomId);
    const spots: MeetingPlacement["spots"] = new Map();
    const people = m.participantIds.filter((id) => !placed.has(id));
    people.forEach((id) => placed.add(id));
    let c = 0, s = 0;
    for (const id of people) {
      if (id === m.organizerId) {
        spots.set(id, { x: r.x0 + t.presenter.x, z: r.z0 + t.presenter.z, ry: -PI / 2, room: roomId, role: "presenter" });
      } else if (c < chairs.length) {
        spots.set(id, { ...chairs[c++], role: "seated" });
      } else {
        spots.set(id, { x: r.x0 + 1 + (s % 6) * 0.8, z: r.z1 - 0.9 - Math.floor(s / 6) * 0.8, ry: PI, room: roomId, role: "standing" });
        s++;
      }
    }
    const speakerId = m.status === "IN_PROGRESS" ? m.participantIds[m.turnIndex] ?? null : null;
    return { room: roomId, meeting: m, speakerId, spots };
  });
}

/**
 * Walking route between two floor spots, through the doors and corridors (plan coordinates).
 * The open collaboration space links the north and south corridors.
 */
export function route(from: { x: number; z: number }, to: { x: number; z: number }): { x: number; z: number }[] {
  const ra = roomAt(from.x, from.z), rb = roomAt(to.x, to.z);
  const pts: { x: number; z: number }[] = [{ x: from.x, z: from.z }];
  if (ra && rb && ra.id === rb.id) return [...pts, { x: to.x, z: to.z }];
  const exit = (r: Room | undefined, p: { x: number; z: number }) => {
    if (!r) return { corridor: Math.abs(p.z - CORRIDOR_NORTH) < Math.abs(p.z - CORRIDOR_SOUTH) ? CORRIDOR_NORTH : CORRIDOR_SOUTH, via: [] as { x: number; z: number }[] };
    if (r.open) {
      // The collaboration space touches both corridors; the lounge opens onto the south one.
      const corridor = r.id === "lounge" ? CORRIDOR_SOUTH : Math.abs(p.z - CORRIDOR_NORTH) < Math.abs(p.z - CORRIDOR_SOUTH) ? CORRIDOR_NORTH : CORRIDOR_SOUTH;
      return { corridor, via: [{ x: p.x, z: corridor }] };
    }
    const d = doorOf(r);
    const inside = r.z0 === 0 ? d.z - 0.6 : d.z + 0.6;
    return { corridor: d.corridor, via: [{ x: d.x, z: inside }, { x: d.x, z: d.corridor }] };
  };
  const a = exit(ra, from), b = exit(rb, to);
  pts.push(...a.via);
  const last = pts[pts.length - 1];
  if (a.corridor !== b.corridor) {
    pts.push({ x: CROSSING_X, z: a.corridor }, { x: CROSSING_X, z: b.corridor });
  } else if (b.via.length === 0) {
    pts.push({ x: last.x, z: b.corridor });
  }
  pts.push(...[...b.via].reverse(), { x: to.x, z: to.z });
  // Drop consecutive duplicates.
  return pts.filter((p, i) => i === 0 || Math.hypot(p.x - pts[i - 1].x, p.z - pts[i - 1].z) > 1e-6);
}

export function routeLength(pts: { x: number; z: number }[]) {
  let l = 0;
  for (let i = 1; i < pts.length; i++) l += Math.hypot(pts[i].x - pts[i - 1].x, pts[i].z - pts[i - 1].z);
  return l;
}

/** Point at distance t along a route, with the heading of the current segment. */
export function along(pts: { x: number; z: number }[], t: number): { x: number; z: number; ry: number } {
  let rest = t;
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1], b = pts[i];
    const l = Math.hypot(b.x - a.x, b.z - a.z);
    if (rest <= l || i === pts.length - 1) {
      const ry = Math.atan2(b.x - a.x, b.z - a.z);
      if (l === 0 || rest >= l) return { x: b.x, z: b.z, ry };
      const k = rest / l;
      return { x: a.x + (b.x - a.x) * k, z: a.z + (b.z - a.z) * k, ry };
    }
    rest -= l;
  }
  const p = pts[pts.length - 1];
  return { x: p.x, z: p.z, ry: 0 };
}

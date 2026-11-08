import type { Agent, Department } from "../api/types";

/** Floor plan in tile units (docs/03-ux-ui.md §4.2). */
export interface Rect { x: number; y: number; w: number; h: number }

export interface Zone extends Rect {
  id: Department | "MEETING_A" | "MEETING_B" | "ENTRANCE";
  label: string;
  kind: "department" | "meeting" | "entrance";
}

export const TILE_W = 64;
export const TILE_H = 32;

export const ZONES: Zone[] = [
  { id: "DIRECTION", label: "CTO Office", kind: "department", x: 0, y: 0, w: 6, h: 5 },
  { id: "ARCHITECTURE", label: "Architecture", kind: "department", x: 7, y: 0, w: 6, h: 5 },
  { id: "PRODUCT", label: "Product Management", kind: "department", x: 14, y: 0, w: 6, h: 5 },
  { id: "MEETING_A", label: "Meeting Room A", kind: "meeting", x: 21, y: 0, w: 5, h: 5 },
  { id: "BACKEND", label: "Backend", kind: "department", x: 0, y: 6, w: 10, h: 5 },
  { id: "FRONTEND", label: "Frontend", kind: "department", x: 11, y: 6, w: 9, h: 5 },
  { id: "MEETING_B", label: "Meeting Room B", kind: "meeting", x: 21, y: 6, w: 5, h: 5 },
  { id: "QA", label: "QA & Testing", kind: "department", x: 0, y: 12, w: 6, h: 5 },
  { id: "SECURITY", label: "Security", kind: "department", x: 7, y: 12, w: 6, h: 5 },
  { id: "DEVOPS", label: "DevOps", kind: "department", x: 14, y: 12, w: 6, h: 5 },
  { id: "OTHER", label: "Specialists", kind: "department", x: 21, y: 12, w: 5, h: 5 },
  { id: "ENTRANCE", label: "Entrance · HR", kind: "entrance", x: 11, y: 18, w: 4, h: 2 },
];

export const FLOOR: Rect = { x: -1, y: -1, w: 28, h: 22 };

export function zone(id: Zone["id"]): Zone {
  return ZONES.find((z) => z.id === id)!;
}

/** Desk positions (tile coordinates of the desk's front) inside a department zone. */
export function deskSlots(z: Rect): { x: number; y: number }[] {
  const slots: { x: number; y: number }[] = [];
  for (let row = 0; row < Math.floor((z.h - 1) / 2); row++) {
    for (let col = 0; col < Math.floor((z.w - 1) / 2); col++) {
      slots.push({ x: z.x + 1.5 + col * 2, y: z.y + 1.6 + row * 2 });
    }
  }
  return slots;
}

export interface Seat { agentId: string; department: Department; x: number; y: number; overflow: boolean }

/**
 * Stable seating: agents of a department take that department's desks in hiring order.
 * The Lead always sits at the CTO Office's first desk. Agents beyond capacity stand in the
 * aisle below the zone (overflow), so nobody is hidden.
 */
export function assignSeats(agents: Pick<Agent, "id" | "department" | "lead" | "createdAt">[]): Map<string, Seat> {
  const seats = new Map<string, Seat>();
  const byDept = new Map<Department, typeof agents>();
  for (const a of agents) byDept.set(a.department, [...(byDept.get(a.department) ?? []), a]);
  for (const [dept, list] of byDept) {
    const ordered = [...list].sort((a, b) => Number(b.lead) - Number(a.lead) || a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id));
    const z = zone(dept);
    const slots = deskSlots(z);
    ordered.forEach((a, i) => {
      if (i < slots.length) {
        seats.set(a.id, { agentId: a.id, department: dept, ...slots[i], overflow: false });
      } else {
        const k = i - slots.length;
        seats.set(a.id, { agentId: a.id, department: dept, x: z.x + 0.6 + (k % z.w), y: z.y + z.h + 0.3, overflow: true });
      }
    });
  }
  return seats;
}

/** Isometric projection of a tile coordinate to world pixels. */
export function iso(x: number, y: number): { x: number; y: number } {
  return { x: (x - y) * (TILE_W / 2), y: (x + y) * (TILE_H / 2) };
}

export function rectCorners(r: Rect) {
  return [iso(r.x, r.y), iso(r.x + r.w, r.y), iso(r.x + r.w, r.y + r.h), iso(r.x, r.y + r.h)];
}

export interface OpenMeeting { id: string; title: string; startedAt: string; participantIds: string[] }
export interface RoomAssignment { roomId: "MEETING_A" | "MEETING_B"; meeting: OpenMeeting; seats: Map<string, { x: number; y: number }> }

/** Chairs around the room's table, in tile coordinates (same convention as desk slots). */
export function meetingSeats(z: Rect, n: number): { x: number; y: number }[] {
  const cx = z.x + z.w / 2 - 0.45, cy = z.y + z.h / 2 - 0.45;
  const r = n <= 6 ? 1.55 : 1.85;
  return Array.from({ length: n }, (_, i) => {
    const a = (i / n) * Math.PI * 2 - Math.PI * 0.75;
    return { x: cx + Math.cos(a) * r, y: cy + Math.sin(a) * r };
  });
}

/**
 * Open meetings take the two meeting rooms in start order. A participant sits in the first room
 * whose meeting includes them; meetings beyond two keep their participants at their desks.
 */
export function assignRooms(meetings: OpenMeeting[]): RoomAssignment[] {
  const rooms: RoomAssignment["roomId"][] = ["MEETING_A", "MEETING_B"];
  const seated = new Set<string>();
  return [...meetings].sort((a, b) => a.startedAt.localeCompare(b.startedAt)).slice(0, rooms.length).map((m, i) => {
    const people = m.participantIds.filter((id) => !seated.has(id));
    people.forEach((id) => seated.add(id));
    const chairs = meetingSeats(zone(rooms[i]), people.length);
    return { roomId: rooms[i], meeting: m, seats: new Map(people.map((id, k) => [id, chairs[k]])) };
  });
}

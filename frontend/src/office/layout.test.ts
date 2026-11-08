import { describe, expect, it } from "vitest";
import { assignRooms, assignSeats, deskSlots, iso, zone, ZONES } from "./layout";

const agent = (id: string, department: string, createdAt: string, lead = false) =>
  ({ id, department, createdAt, lead }) as never;

describe("office layout", () => {
  it("projects tiles isometrically", () => {
    expect(iso(0, 0)).toEqual({ x: 0, y: 0 });
    expect(iso(1, 0)).toEqual({ x: 32, y: 16 });
    expect(iso(0, 1)).toEqual({ x: -32, y: 16 });
  });

  it("zones do not overlap", () => {
    for (const a of ZONES) for (const b of ZONES) {
      if (a === b) continue;
      const overlap = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
      expect(overlap, `${a.id} overlaps ${b.id}`).toBe(false);
    }
  });

  it("desks stay inside their zone", () => {
    for (const z of ZONES.filter((z) => z.kind === "department")) {
      const slots = deskSlots(z);
      expect(slots.length).toBeGreaterThanOrEqual(2);
      for (const s of slots) {
        expect(s.x).toBeGreaterThan(z.x);
        expect(s.x).toBeLessThan(z.x + z.w);
        expect(s.y).toBeGreaterThan(z.y);
        expect(s.y).toBeLessThan(z.y + z.h);
      }
    }
  });

  it("seats the Lead first and others in hiring order, stable across calls", () => {
    const agents = [
      agent("b", "DIRECTION", "2026-01-02"),
      agent("lead", "DIRECTION", "2026-01-03", true),
      agent("c", "BACKEND", "2026-01-01"),
    ];
    const seats = assignSeats(agents);
    const first = deskSlots(zone("DIRECTION"))[0];
    expect(seats.get("lead")).toMatchObject(first);
    expect(assignSeats([...agents].reverse())).toEqual(seats);
  });

  it("never hides agents beyond capacity", () => {
    const capacity = deskSlots(zone("QA")).length;
    const many = Array.from({ length: capacity + 3 }, (_, i) => agent("a" + i, "QA", `2026-01-${String(i + 1).padStart(2, "0")}`));
    const seats = assignSeats(many);
    expect(seats.size).toBe(capacity + 3);
    expect([...seats.values()].filter((s) => s.overflow)).toHaveLength(3);
  });

  it("seats open meetings in the two rooms, in start order, each participant once", () => {
    const rooms = assignRooms([
      { id: "m2", title: "Later", startedAt: "2026-01-01T10:00:00Z", participantIds: ["a", "c"] },
      { id: "m1", title: "Kickoff", startedAt: "2026-01-01T09:00:00Z", participantIds: ["a", "b"] },
      { id: "m3", title: "Third", startedAt: "2026-01-01T11:00:00Z", participantIds: ["d"] },
    ]);
    expect(rooms.map((r) => [r.roomId, r.meeting.id])).toEqual([["MEETING_A", "m1"], ["MEETING_B", "m2"]]);
    expect([...rooms[1].seats.keys()]).toEqual(["c"]);
    const room = zone("MEETING_A");
    for (const s of rooms[0].seats.values()) {
      expect(s.x).toBeGreaterThan(room.x);
      expect(s.x).toBeLessThan(room.x + room.w);
    }
  });
});

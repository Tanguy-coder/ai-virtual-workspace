import { describe, expect, it } from "vitest";
import { along, assignSeats, placeMeetings, RECEPTION, roomAt, ROOM_BY_ID, ROOMS, route, routeLength, type OpenMeeting } from "./plan";
import { represent, roomSummary } from "./represent";

const agent = (id: string, department: string, extra: Record<string, unknown> = {}) => ({
  id, department, lead: false, createdAt: `2026-11-08T10:00:0${id.length % 10}Z`, status: "ACTIVE", activity: "AVAILABLE", blockedReason: null, ...extra,
}) as never;

const meeting = (id: string, participantIds: string[], extra: Partial<OpenMeeting> = {}): OpenMeeting => ({
  id, title: "Kickoff", startedAt: "2026-11-08T10:00:00Z", participantIds, organizerId: participantIds[0], status: "IN_PROGRESS",
  rounds: 2, currentRound: 1, turnIndex: 1, ...extra,
});

describe("floor plan", () => {
  it("keeps rooms inside the floor and without overlap", () => {
    for (const r of ROOMS) {
      expect(r.x0).toBeGreaterThanOrEqual(0);
      expect(r.x1).toBeLessThanOrEqual(40);
      for (const o of ROOMS) {
        if (o === r) continue;
        const overlap = Math.min(r.x1, o.x1) - Math.max(r.x0, o.x0) > 0 && Math.min(r.z1, o.z1) - Math.max(r.z0, o.z0) > 0;
        expect(overlap, `${r.id} overlaps ${o.id}`).toBe(false);
      }
    }
  });

  it("puts every desk inside its room", () => {
    for (const r of ROOMS) for (const d of r.desks) expect(roomAt(r.x0 + d.x, r.z0 + d.z)?.id).toBe(r.id);
  });

  it("seats the Lead first, then by hiring order, and stands the overflow in the room", () => {
    const seats = assignSeats([
      agent("b", "BACKEND", { createdAt: "2026-11-08T10:00:02Z" }),
      agent("a", "BACKEND", { createdAt: "2026-11-08T10:00:01Z" }),
      ...Array.from({ length: 8 }, (_, i) => agent(`x${i}`, "BACKEND", { createdAt: `2026-11-08T11:00:0${i}Z` })),
      agent("lead", "DIRECTION", { lead: true }),
    ]);
    expect(seats.get("a")!.deskIndex).toBe(0);
    expect(seats.get("b")!.deskIndex).toBe(1);
    expect(seats.get("lead")!.room).toBe("cto");
    const overflow = [...seats.values()].filter((s) => s.overflow);
    expect(overflow).toHaveLength(2);
    for (const s of overflow) expect(roomAt(s.x, s.z)?.id).toBe("backend");
  });

  it("places the organizer at the screen, others at the table, a second meeting in room B", () => {
    const p = placeMeetings([meeting("m2", ["l2", "c"], { startedAt: "2026-11-08T11:00:00Z" }), meeting("m1", ["lead", "a", "b"])]);
    expect(p.map((x) => x.room)).toEqual(["roomA", "roomB"]);
    expect(p[0].spots.get("lead")!.role).toBe("presenter");
    expect(p[0].spots.get("a")!.role).toBe("seated");
    expect(p[0].speakerId).toBe("a");
    for (const [, s] of p[0].spots) expect(roomAt(s.x, s.z)?.id).toBe("roomA");
  });

  it("routes from the reception to a north-row desk through the corridors", () => {
    const desk = { x: ROOM_BY_ID.arch.x0 + 2.2, z: 2.9 };
    const pts = route(RECEPTION, desk);
    expect(pts[0]).toEqual(RECEPTION);
    expect(pts.at(-1)).toEqual(desk);
    expect(pts.some((p) => p.z === 22.5)).toBe(true);
    expect(pts.some((p) => p.z === 10.5)).toBe(true);
    expect(routeLength(pts)).toBeGreaterThan(Math.hypot(desk.x - RECEPTION.x, desk.z - RECEPTION.z));
    expect(along(pts, routeLength(pts))).toMatchObject(desk);
  });
});

describe("representation engine", () => {
  it("shows an available agent seated, screen off, no light", () => {
    const v = represent([agent("a", "BACKEND")], []);
    expect(v.figures[0]).toMatchObject({ posture: "idle", lantern: null, faded: false });
    expect(v.desks[0]).toMatchObject({ screen: "off", occupied: true });
  });

  it("only lights a screen while a model call runs", () => {
    const v = represent([agent("a", "BACKEND", { activity: "THINKING" })], []);
    expect(v.figures[0]).toMatchObject({ posture: "type", lantern: "thinking" });
    expect(v.desks[0].screen).toBe("generating");
  });

  it("shows a blocked agent with arms crossed and the recorded reason", () => {
    const v = represent([agent("a", "QA", { activity: "BLOCKED", blockedReason: "No test database" })], []);
    expect(v.figures[0]).toMatchObject({ posture: "crossed", lantern: "blocked", label: "Blocked · No test database" });
    expect(v.desks[0]).toMatchObject({ screen: "blocked", blockedReason: "No test database" });
  });

  it("moves meeting participants to the room and leaves their desks empty", () => {
    const v = represent([agent("lead", "DIRECTION", { lead: true }), agent("a", "BACKEND")], [meeting("m", ["lead", "a"])]);
    const a = v.figures.find((f) => f.agentId === "a")!;
    expect(a.spot.room).toBe("roomA");
    expect(a.posture).toBe("talk");
    expect(v.figures.find((f) => f.agentId === "lead")!.posture).toBe("stand");
    expect(v.desks.every((d) => !d.occupied && d.screen === "off")).toBe(true);
    expect(roomSummary(v, "backend")).toMatchObject({ count: 1, away: 1 });
  });

  it("ignores ended meetings, fades paused agents and hides dismissed ones", () => {
    const v = represent(
      [agent("a", "BACKEND", { status: "PAUSED", activity: "THINKING" }), agent("b", "BACKEND", { status: "DISMISSED" })],
      [meeting("m", ["a"], { status: "ENDED" })],
    );
    expect(v.figures).toHaveLength(1);
    expect(v.figures[0]).toMatchObject({ posture: "idle", faded: true, lantern: null });
    expect(v.desks[0].screen).toBe("off");
    expect(v.meetings).toHaveLength(0);
  });
});

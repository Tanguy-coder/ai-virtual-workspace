import { Application, Container, FederatedPointerEvent, Graphics, Text } from "pixi.js";
import type { Agent, Department } from "../api/types";
import { DEPARTMENT_COLORS } from "../components/AgentAvatar";
import { assignRooms, assignSeats, deskSlots, FLOOR, iso, rectCorners, zone, ZONES, type OpenMeeting, type RoomAssignment, type Seat, type Zone } from "./layout";

export interface SceneCallbacks {
  onSelect(agentId: string): void;
  onHover(agentId: string | null, screen: { x: number; y: number } | null): void;
  onHire(department: Department): void;
}

interface Palette {
  floor: number;
  floorLine: number;
  text: number;
  muted: number;
  desk: [number, number, number];
  screenOff: number;
  accent: number;
  bubble: number;
  dark: boolean;
}

const WALK_MS = 1200;
const LINK_MS = 4000;

function cssColor(name: string): number {
  const probe = document.createElement("span");
  probe.style.color = `var(${name})`;
  document.body.appendChild(probe);
  const rgb = getComputedStyle(probe).color.match(/\d+/g)!.map(Number);
  probe.remove();
  return (rgb[0] << 16) + (rgb[1] << 8) + rgb[2];
}

function palette(): Palette {
  const dark = document.documentElement.dataset.theme !== "light";
  return dark
    ? { floor: 0x12141c, floorLine: 0x1d2030, text: cssColor("--fg"), muted: cssColor("--muted"), desk: [0x3a3f52, 0x2a2e3d, 0x22252f], screenOff: 0x1a1d27, accent: cssColor("--accent-2"), bubble: 0xf4f4f8, dark }
    : { floor: 0xeceef4, floorLine: 0xdfe2ea, text: cssColor("--fg"), muted: cssColor("--muted"), desk: [0xd9cbb3, 0xc4b498, 0xb3a385], screenOff: 0x2b2f3a, accent: cssColor("--accent"), bubble: 0xffffff, dark };
}

function hex(color: string): number {
  return parseInt(color.slice(1), 16);
}

function label(text: string, size: number, fill: number, weight: "400" | "600" | "700" = "600", spacing = 0): Text {
  const t = new Text({
    text,
    style: { fontFamily: "Inter Variable, system-ui, sans-serif", fontSize: size, fill, fontWeight: weight, letterSpacing: spacing },
    resolution: 3,
  });
  return t;
}

/** One agent on the floor. Its look is a pure function of the agent's recorded state. */
class AgentSprite {
  readonly root = new Container();
  private readonly body = new Container();
  private readonly ring = new Graphics();
  private readonly status = new Container();
  private readonly nameTag = new Container();
  private readonly screen: Graphics;
  private agent: Agent;
  private walkFrom: { x: number; y: number } | null = null;
  private walkStart = 0;
  private target = { x: 0, y: 0 };
  private phase = Math.random() * Math.PI * 2;
  selected = false;
  hovered = false;

  constructor(agent: Agent, private readonly pal: Palette, screen: Graphics, cb: SceneCallbacks) {
    this.agent = agent;
    this.screen = screen;
    this.root.eventMode = "static";
    this.root.cursor = "pointer";
    this.root.scale.set(1.35);
    this.root.addChild(this.ring, this.body, this.status, this.nameTag);
    this.root.on("pointertap", (e: FederatedPointerEvent) => {
      e.stopPropagation();
      cb.onSelect(this.agent.id);
    });
    this.root.on("pointerover", () => {
      this.hovered = true;
      const p = this.root.getGlobalPosition();
      cb.onHover(this.agent.id, { x: p.x, y: p.y - 46 * this.root.worldTransform.a });
      this.refreshTag();
    });
    this.root.on("pointerout", () => {
      this.hovered = false;
      cb.onHover(null, null);
      this.refreshTag();
    });
    this.drawBody();
    this.update(agent);
  }

  private drawBody() {
    const color = hex(DEPARTMENT_COLORS[this.agent.department]);
    const g = new Graphics();
    g.ellipse(0, 0, 13, 5.5).fill({ color: 0x000000, alpha: this.pal.dark ? 0.45 : 0.18 });
    // torso
    g.roundRect(-9, -26, 18, 22, 7).fill(color);
    g.roundRect(-9, -26, 18, 9, 5).fill({ color: 0xffffff, alpha: 0.14 });
    // head and hair
    g.circle(0, -33, 7.5).fill(0xf0d2b4);
    g.arc(0, -34, 7.8, Math.PI * 1.05, Math.PI * 1.95).fill(this.pal.dark ? 0x2a2433 : 0x3b2f2a);
    this.body.addChild(g);
    const badge = new Graphics().circle(8, -14, 5.5).fill(this.pal.dark ? 0x0e1016 : 0xffffff).stroke({ width: 1.2, color, alpha: 0.9 });
    const glyph = label(this.agent.lead ? "★" : "", 7, color, "700");
    glyph.anchor.set(0.5);
    glyph.position.set(8, -14);
    this.body.addChild(badge, glyph);
  }

  /** Moves the agent to a tile spot: entrance walk for live hires, a walk when its place changes, else a jump. */
  setTarget(spot: { x: number; y: number }, mode: "arrive" | "move" | "place", reducedMotion: boolean) {
    const p = iso(spot.x + 0.45, spot.y + 0.45);
    const changed = p.x !== this.target.x || p.y !== this.target.y;
    this.target = p;
    if (mode === "arrive" && !reducedMotion) {
      const door = zone("ENTRANCE");
      this.walkFrom = iso(door.x + door.w / 2, door.y + 0.4);
      this.walkStart = performance.now();
      this.root.position.set(this.walkFrom.x, this.walkFrom.y);
    } else if (mode === "move" && changed && !reducedMotion) {
      this.walkFrom = { x: this.root.x, y: this.root.y };
      this.walkStart = performance.now();
    } else if (!this.walkFrom || changed) {
      this.walkFrom = null;
      this.root.position.set(p.x, p.y);
    }
    this.root.zIndex = this.root.y;
  }

  away = false;

  update(agent: Agent) {
    this.agent = agent;
    this.status.removeChildren().forEach((c) => c.destroy());
    const thinking = agent.status === "ACTIVE" && agent.activity === "THINKING";
    const paused = agent.status === "PAUSED";
    const blocked = agent.status === "ACTIVE" && agent.activity === "BLOCKED";
    this.body.alpha = paused ? 0.45 : 1;
    if (blocked) {
      const pill = new Graphics().roundRect(-10, -62, 20, 17, 8.5).fill(0xef4444).stroke({ width: 1.5, color: 0xffffff, alpha: 0.85 });
      const t = label("!", 11, 0xffffff, "700");
      t.anchor.set(0.5);
      t.position.set(0, -53.5);
      this.status.addChild(pill, t);
    } else if (thinking) {
      const bubble = new Graphics();
      bubble.roundRect(-15, -63, 30, 17, 8.5).fill(this.pal.bubble).stroke({ width: 1, color: this.pal.accent, alpha: 0.6 });
      bubble.circle(-4, -44, 2.2).fill(this.pal.bubble);
      this.status.addChild(bubble);
      for (let i = 0; i < 3; i++) {
        const dot = new Graphics().circle(-7 + i * 7, -54.5, 2.1).fill(this.pal.accent);
        dot.label = "dot";
        this.status.addChild(dot);
      }
    } else if (paused) {
      const pill = new Graphics().roundRect(-11, -60, 22, 14, 7).fill({ color: 0x6b7085 });
      const t = label("II", 8, 0xffffff, "700");
      t.anchor.set(0.5);
      t.position.set(0, -53);
      this.status.addChild(pill, t);
    }
    this.screen.clear();
    this.drawScreen(thinking && !this.away ? 1 : 0);
    this.refreshTag();
  }

  private drawScreen(glow: number) {
    const s = this.screen;
    s.clear();
    s.poly([-7, -15, 7, -22, 7, -12, -7, -5]).fill(glow > 0 ? this.pal.accent : this.pal.screenOff);
    if (glow > 0) s.alpha = 0.55 + 0.45 * glow;
    else s.alpha = 1;
  }

  refreshTag() {
    this.nameTag.removeChildren().forEach((c) => c.destroy());
    this.ring.clear();
    if (this.selected) {
      this.ring.ellipse(0, 0, 18, 8).stroke({ width: 2, color: this.pal.accent }).fill({ color: this.pal.accent, alpha: 0.15 });
    }
    const t = label(this.agent.name, 9.5, this.pal.text, "600");
    t.anchor.set(0.5, 0);
    const w = t.width + 12;
    const bg = new Graphics().roundRect(-w / 2, 8, w, 15, 7.5).fill({ color: this.pal.dark ? 0x0b0c12 : 0xffffff, alpha: this.pal.dark ? 0.82 : 0.92 });
    t.position.set(0, 9.5);
    this.nameTag.addChild(bg, t);
    this.nameTag.visible = this.selected || this.hovered || this.showNames;
  }

  showNames = true;

  tick(now: number, reducedMotion: boolean) {
    if (this.walkFrom) {
      const t = Math.min((now - this.walkStart) / WALK_MS, 1);
      const e = 1 - Math.pow(1 - t, 3);
      this.root.position.set(this.walkFrom.x + (this.target.x - this.walkFrom.x) * e, this.walkFrom.y + (this.target.y - this.walkFrom.y) * e);
      this.body.y = reducedMotion ? 0 : -Math.abs(Math.sin(t * Math.PI * 6)) * 2.5;
      this.root.zIndex = this.root.y;
      if (t >= 1) {
        this.walkFrom = null;
        this.body.y = 0;
      }
    }
    if (reducedMotion) return;
    const thinking = this.agent.status === "ACTIVE" && this.agent.activity === "THINKING";
    if (thinking) {
      const dots = this.status.children.filter((c) => c.label === "dot");
      dots.forEach((d, i) => (d.alpha = 0.35 + 0.65 * Math.max(0, Math.sin(now / 220 - i * 0.9))));
      if (!this.away) this.screen.alpha = 0.6 + 0.4 * Math.abs(Math.sin(now / 500 + this.phase));
    }
  }
}

/**
 * Imperative PixiJS scene for the Virtual Office. React owns the data; the scene only renders it.
 * Nothing moves unless an agent's recorded state changes or a live agent.hired event arrives.
 */
export class OfficeScene {
  private app = new Application();
  private world = new Container();
  private floorLayer = new Container();
  private deskLayer = new Container();
  private actors = new Container();
  private sprites = new Map<string, AgentSprite>();
  private screens = new Map<string, Graphics>();
  private pal!: Palette;
  private ready = false;
  private destroyed = false;
  private reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  private selectedId: string | null = null;
  private showNames = false;
  private agents: Agent[] = [];
  private rooms: RoomAssignment[] = [];
  private meetings: OpenMeeting[] = [];
  private linkLayer = new Graphics();
  private links: { from: string; to: string; color: number; at: number }[] = [];

  constructor(private host: HTMLElement, private cb: SceneCallbacks) {}

  async mount() {
    await this.app.init({ resizeTo: this.host, antialias: true, backgroundAlpha: 0, resolution: window.devicePixelRatio || 1, autoDensity: true });
    if (this.destroyed) {
      this.app.destroy(true);
      return;
    }
    this.host.appendChild(this.app.canvas);
    this.app.stage.addChild(this.world);
    this.world.addChild(this.floorLayer, this.deskLayer, this.actors, this.linkLayer);
    this.actors.sortableChildren = true;
    this.deskLayer.sortableChildren = true;
    this.pal = palette();
    this.drawFloor();
    this.enablePanZoom();
    this.fit();
    this.app.ticker.add(() => {
      const now = performance.now();
      this.sprites.forEach((s) => s.tick(now, this.reducedMotion));
      this.drawLinks(now);
    });
    this.ready = true;
    this.setAgents(this.agents, new Set());
  }

  destroy() {
    this.destroyed = true;
    if (this.ready) this.app.destroy(true, { children: true });
  }

  /** Re-reads theme colors and redraws everything. */
  retheme() {
    if (!this.ready) return;
    this.pal = palette();
    this.floorLayer.removeChildren();
    this.deskLayer.removeChildren();
    this.actors.removeChildren();
    this.linkLayer.clear();
    this.sprites.clear();
    this.screens.clear();
    this.drawFloor();
    this.world.addChild(this.linkLayer);
    this.setAgents(this.agents, new Set());
  }

  fit() {
    const corners = rectCorners(FLOOR);
    const minX = Math.min(...corners.map((c) => c.x)), maxX = Math.max(...corners.map((c) => c.x));
    const minY = Math.min(...corners.map((c) => c.y)), maxY = Math.max(...corners.map((c) => c.y));
    const w = this.host.clientWidth, h = this.host.clientHeight;
    const scale = Math.min(w / (maxX - minX + 40), h / (maxY - minY + 60), 1.6);
    this.world.scale.set(scale);
    this.world.position.set(w / 2 - ((minX + maxX) / 2) * scale, h / 2 - ((minY + maxY) / 2) * scale + 10);
    this.syncNames();
  }

  /** Names always show at department zoom and closer; at floor zoom, only on hover or selection (UX §4.3). */
  private syncNames() {
    const show = this.world.scale.x >= 1.15;
    if (show === this.showNames) return;
    this.showNames = show;
    this.sprites.forEach((sp) => {
      sp.showNames = show;
      sp.refreshTag();
    });
  }

  zoom(factor: number) {
    const w = this.host.clientWidth / 2, h = this.host.clientHeight / 2;
    this.zoomAt(factor, w, h);
  }

  select(agentId: string | null) {
    this.selectedId = agentId;
    this.sprites.forEach((s, id) => {
      s.selected = id === agentId;
      s.refreshTag();
    });
  }

  /** Open meetings: their participants walk to a meeting room and back to their desk when it ends. */
  setMeetings(meetings: OpenMeeting[]) {
    this.meetings = meetings;
    this.setAgents(this.agents, new Set());
  }

  /** Draws a line between two agents' positions for a few seconds; called for each live agent-to-agent message. */
  link(from: string, to: string, type: string) {
    if (this.reducedMotion) return;
    const colors: Record<string, number> = { ASSIGNMENT: 0x34d399, QUESTION: this.pal?.accent ?? 0x8b7cf6, PROBLEM: 0xf87171, DEPENDENCY: 0xfbbf24, REVIEW_REQUEST: 0x38bdf8 };
    this.links.push({ from, to, color: colors[type] ?? (this.pal?.accent ?? 0x8b7cf6), at: performance.now() });
  }

  private drawLinks(now: number) {
    const g = this.linkLayer;
    g.clear();
    this.links = this.links.filter((l) => now - l.at < LINK_MS);
    for (const l of this.links) {
      const a = this.sprites.get(l.from)?.root, b = this.sprites.get(l.to)?.root;
      if (!a || !b) continue;
      const t = (now - l.at) / LINK_MS;
      const alpha = t < 0.15 ? t / 0.15 : t > 0.8 ? (1 - t) / 0.2 : 1;
      const ax = a.x, ay = a.y - 40, bx = b.x, by = b.y - 40;
      const cx = (ax + bx) / 2, cy = Math.min(ay, by) - 60 - Math.abs(ax - bx) * 0.15;
      g.moveTo(ax, ay).quadraticCurveTo(cx, cy, bx, by).stroke({ width: 2.5, color: l.color, alpha: 0.75 * alpha });
      const k = Math.min(1, t * 1.6);
      const px = (1 - k) * (1 - k) * ax + 2 * (1 - k) * k * cx + k * k * bx;
      const py = (1 - k) * (1 - k) * ay + 2 * (1 - k) * k * cy + k * k * by;
      g.circle(px, py, 4.5).fill({ color: l.color, alpha });
      g.circle(bx, by, 7 * alpha).stroke({ width: 2, color: l.color, alpha: 0.5 * alpha });
    }
  }

  /** Syncs sprites with the agent list. {@code arriving}: ids hired live, which walk in from the entrance. */
  setAgents(agents: Agent[], arriving: Set<string>) {
    this.agents = agents;
    if (!this.ready) return;
    const visible = agents.filter((a) => a.status !== "DISMISSED");
    const seats = assignSeats(visible);
    this.rooms = assignRooms(this.meetings);
    const inRoom = new Map<string, { x: number; y: number }>();
    this.rooms.forEach((r) => r.seats.forEach((spot, id) => inRoom.set(id, spot)));
    for (const [id, sprite] of this.sprites) {
      if (!seats.has(id)) {
        this.actors.removeChild(sprite.root);
        sprite.root.destroy({ children: true });
        this.sprites.delete(id);
      }
    }
    this.drawDesks(seats);
    for (const a of visible) {
      const seat = seats.get(a.id)!;
      let sprite = this.sprites.get(a.id);
      const isNew = !sprite;
      if (!sprite) {
        sprite = new AgentSprite(a, this.pal, this.screens.get(a.id) ?? new Graphics(), this.cb);
        sprite.showNames = this.showNames;
        this.sprites.set(a.id, sprite);
        this.actors.addChild(sprite.root);
      } else {
        sprite.update(a);
      }
      sprite.selected = a.id === this.selectedId;
      const meetingSpot = inRoom.get(a.id);
      sprite.away = !!meetingSpot;
      sprite.update(a);
      sprite.setTarget(meetingSpot ?? seat, isNew ? (arriving.has(a.id) ? "arrive" : "place") : "move", this.reducedMotion);
      sprite.refreshTag();
    }
    this.drawZoneLabels(visible);
  }

  private drawFloor() {
    const p = this.pal;
    const base = new Graphics();
    const corners = rectCorners(FLOOR).flatMap((c) => [c.x, c.y]);
    // slab thickness
    const [a, b, c, d] = rectCorners(FLOOR);
    base.poly([d.x, d.y, c.x, c.y, c.x, c.y + 14, d.x, d.y + 14]).fill(p.dark ? 0x0c0d13 : 0xd6d9e2);
    base.poly([c.x, c.y, b.x, b.y, b.x, b.y + 14, c.x, c.y + 14]).fill(p.dark ? 0x0a0b10 : 0xcdd0da);
    base.poly(corners).fill(p.floor);
    void a;
    for (let x = FLOOR.x; x <= FLOOR.x + FLOOR.w; x++) {
      const s = iso(x, FLOOR.y), e = iso(x, FLOOR.y + FLOOR.h);
      base.moveTo(s.x, s.y).lineTo(e.x, e.y);
    }
    for (let y = FLOOR.y; y <= FLOOR.y + FLOOR.h; y++) {
      const s = iso(FLOOR.x, y), e = iso(FLOOR.x + FLOOR.w, y);
      base.moveTo(s.x, s.y).lineTo(e.x, e.y);
    }
    base.stroke({ width: 1, color: p.floorLine, alpha: 0.7 });
    this.floorLayer.addChild(base);

    for (const z of ZONES) this.drawZone(z);
  }

  private drawZone(z: Zone) {
    const p = this.pal;
    const g = new Graphics();
    const pts = rectCorners(z).flatMap((c) => [c.x, c.y]);
    const color = z.kind === "department" ? hex(DEPARTMENT_COLORS[z.id as Department]) : z.kind === "meeting" ? p.accent : p.muted;
    g.poly(pts).fill({ color, alpha: p.dark ? 0.07 : 0.1 }).stroke({ width: 1.5, color, alpha: p.dark ? 0.45 : 0.55 });
    // low walls on the two back edges
    const [tl, tr, , bl] = rectCorners(z);
    g.poly([tl.x, tl.y, tr.x, tr.y, tr.x, tr.y - 10, tl.x, tl.y - 10]).fill({ color, alpha: p.dark ? 0.16 : 0.2 });
    g.poly([bl.x, bl.y, tl.x, tl.y, tl.x, tl.y - 10, bl.x, bl.y - 10]).fill({ color, alpha: p.dark ? 0.1 : 0.14 });
    this.floorLayer.addChild(g);

    if (z.kind === "meeting") this.drawMeetingRoom(z);
    if (z.kind === "entrance") {
      const door = new Graphics();
      const c = iso(z.x + z.w / 2, z.y + z.h / 2);
      door.roundRect(c.x - 18, c.y - 30, 36, 30, 4).fill({ color: p.muted, alpha: 0.25 }).stroke({ width: 1, color: p.muted, alpha: 0.6 });
      this.floorLayer.addChild(door);
    }
  }

  private drawMeetingRoom(z: Zone) {
    const p = this.pal;
    const c = iso(z.x + z.w / 2, z.y + z.h / 2);
    const g = new Graphics();
    for (let i = 0; i < 6; i++) {
      const ang = (i / 6) * Math.PI * 2;
      g.ellipse(c.x + Math.cos(ang) * 46, c.y + Math.sin(ang) * 23, 6, 3.5).fill({ color: p.desk[1] });
    }
    g.ellipse(c.x, c.y + 6, 36, 17).fill({ color: 0x000000, alpha: 0.18 });
    g.ellipse(c.x, c.y, 36, 17).fill(p.desk[0]).stroke({ width: 1, color: p.desk[2] });
    this.floorLayer.addChild(g);
  }

  private drawDesks(seats: Map<string, Seat>) {
    // Keep agents' screen graphics alive: they are reused by their sprites.
    this.deskLayer.children.forEach((d) => d.children.forEach((c) => [...this.screens.values()].includes(c as Graphics) && d.removeChild(c)));
    this.deskLayer.removeChildren().forEach((c) => c.destroy({ children: true }));
    const occupied = new Set([...seats.values()].filter((s) => !s.overflow).map((s) => `${s.x},${s.y}`));
    for (const z of ZONES.filter((z) => z.kind === "department")) {
      for (const slot of deskSlots(z)) {
        const taken = occupied.has(`${slot.x},${slot.y}`);
        const seat = [...seats.values()].find((s) => s.x === slot.x && s.y === slot.y);
        this.deskLayer.addChild(this.desk(slot.x, slot.y, taken, seat?.agentId));
      }
    }
  }

  private desk(x: number, y: number, taken: boolean, agentId?: string): Container {
    const p = this.pal;
    const c = new Container();
    const o = iso(x, y);
    c.position.set(o.x, o.y);
    c.zIndex = o.y;
    c.alpha = taken ? 1 : 0.55;
    const top = new Graphics();
    const hw = 26, hh = 13, th = 10;
    top.poly([-hw, 0, 0, -hh, hw, 0, 0, hh]).fill(p.desk[0]);
    top.poly([-hw, 0, 0, hh, 0, hh + th, -hw, th]).fill(p.desk[1]);
    top.poly([0, hh, hw, 0, hw, th, 0, hh + th]).fill(p.desk[2]);
    c.addChild(top);
    // monitor stand + screen (screen state is driven by the seated agent's activity)
    const stand = new Graphics().rect(-1.5, -12, 3, 8).fill(0x2b2f3a);
    const frame = new Graphics().poly([-8, -15, 8, -23, 8, -11, -8, -3]).fill(0x2b2f3a);
    c.addChild(stand, frame);
    if (agentId) {
      const screen = this.screens.get(agentId) ?? new Graphics();
      this.screens.set(agentId, screen);
      c.addChild(screen);
    } else {
      const off = new Graphics().poly([-7, -15, 7, -22, 7, -12, -7, -5]).fill(p.screenOff);
      c.addChild(off);
    }
    return c;
  }

  private zoneLabels = new Container();

  private drawZoneLabels(agents: Agent[]) {
    this.zoneLabels.removeChildren().forEach((c) => c.destroy());
    if (!this.zoneLabels.parent) this.world.addChild(this.zoneLabels);
    const p = this.pal;
    const counts = new Map<string, number>();
    agents.forEach((a) => counts.set(a.department, (counts.get(a.department) ?? 0) + 1));
    for (const z of ZONES) {
      // Upright label floating above the middle of the zone's back-right wall: never over desks or avatars.
      const at = iso(z.x + z.w / 2, z.y);
      const n = counts.get(z.id) ?? 0;
      const room = this.rooms.find((r) => r.roomId === z.id);
      const color = z.kind === "department" ? hex(DEPARTMENT_COLORS[z.id as Department]) : z.kind === "meeting" && room ? p.accent : p.muted;
      const topic = room ? (room.meeting.title.length > 22 ? room.meeting.title.slice(0, 21) + "…" : room.meeting.title) : "";
      const sub = z.kind === "department" ? ` · ${n}` : z.kind === "meeting" ? (room ? ` · ${topic}` : " · no meeting") : "";
      const title = label(z.label.toUpperCase() + sub, 9, color, "700", 1.1);
      title.anchor.set(0.5);
      const pill = new Graphics()
        .roundRect(-title.width / 2 - 8, -9, title.width + 16, 18, 9)
        .fill({ color: p.dark ? 0x0b0c12 : 0xffffff, alpha: p.dark ? 0.85 : 0.95 })
        .stroke({ width: 1, color, alpha: 0.45 });
      const tag = new Container();
      tag.addChild(pill, title);
      tag.position.set(at.x, at.y - 24);
      this.zoneLabels.addChild(tag);
      if (z.kind === "department" && n === 0) {
        const c = iso(z.x + z.w / 2, z.y + z.h / 2 + 0.2);
        const btn = new Container();
        btn.eventMode = "static";
        btn.cursor = "pointer";
        const t = label("+ Hire", 10, 0xffffff, "600");
        t.anchor.set(0.5);
        const bg = new Graphics().roundRect(-t.width / 2 - 10, -11, t.width + 20, 22, 11).fill(hex(DEPARTMENT_COLORS[z.id as Department]));
        bg.alpha = 0.9;
        btn.addChild(bg, t);
        btn.position.set(c.x, c.y);
        btn.on("pointertap", (e: FederatedPointerEvent) => {
          e.stopPropagation();
          this.cb.onHire(z.id as Department);
        });
        this.zoneLabels.addChild(btn);
      }
    }
  }

  private enablePanZoom() {
    const stage = this.app.stage;
    stage.eventMode = "static";
    stage.hitArea = this.app.screen;
    let drag: { x: number; y: number; wx: number; wy: number } | null = null;
    stage.on("pointerdown", (e: FederatedPointerEvent) => {
      drag = { x: e.global.x, y: e.global.y, wx: this.world.x, wy: this.world.y };
    });
    stage.on("pointermove", (e: FederatedPointerEvent) => {
      if (!drag) return;
      this.world.position.set(drag.wx + e.global.x - drag.x, drag.wy + e.global.y - drag.y);
    });
    const end = () => (drag = null);
    stage.on("pointerup", end);
    stage.on("pointerupoutside", end);
    this.app.canvas.addEventListener("wheel", (e) => {
      e.preventDefault();
      const rect = this.app.canvas.getBoundingClientRect();
      this.zoomAt(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX - rect.left, e.clientY - rect.top);
    }, { passive: false });
  }

  private zoomAt(factor: number, sx: number, sy: number) {
    const old = this.world.scale.x;
    const next = Math.min(Math.max(old * factor, 0.35), 3);
    const wx = (sx - this.world.x) / old, wy = (sy - this.world.y) / old;
    this.world.scale.set(next);
    this.world.position.set(sx - wx * next, sy - wy * next);
    this.syncNames();
  }
}

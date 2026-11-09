import { CameraControls, Html } from "@react-three/drei";
import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { forwardRef, memo, useEffect, useImperativeHandle, useMemo, useRef, useState } from "react";
import * as THREE from "three";
import type { Agent } from "../api/types";
import { DEPARTMENT_COLORS } from "../components/AgentAvatar";
import { Building } from "./Building";
import { planeGeo, RBox, M } from "./kit";
import { Person } from "./Person";
import { MEETING_TABLES, OX, OZ, RECEPTION, ROOM_BY_ID, ROOMS, roomAt, type RoomId } from "./plan";
import { roomSummary, type OfficeView } from "./represent";
import { meetingTexture, sleepTexture } from "./textures";
import { Workstation } from "./Workstation";

export type Level = { kind: "company" } | { kind: "room"; room: RoomId } | { kind: "agent"; room: RoomId | null; agentId: string };

export interface OfficeCanvasHandle {
  overview(): void;
  room(id: RoomId): void;
  rotate(dir: 1 | -1): void;
  zoom(f: number): void;
}

export interface LinkArc { id: string; from: string; to: string; at: number }

interface Props {
  view: OfficeView;
  agents: Map<string, Agent>;
  level: Level;
  arrivals: Set<string>;
  links: LinkArc[];
  walkOnChange: boolean;
  reducedMotion: boolean;
  /** Width covered by the agent drawer on the right, so the camera frames the agent in the visible part. */
  drawerWidth: number;
  dark: boolean;
  onSelect(id: string): void;
  onRoom(id: RoomId): void;
  onTarget(t: { x: number; z: number; span: number }): void;
}

const ORIGIN = new THREE.Vector3(OX, 0, OZ);
const focusKey = (l: Level) => (l.kind === "company" ? "" : l.room ?? "");

/** The 3D office. Everything it draws comes from `view` (the representation of recorded state). */
export const OfficeCanvas = forwardRef<OfficeCanvasHandle, Props>(function OfficeCanvas(props, ref) {
  return (
    <Canvas shadows frameloop="demand" dpr={[1, 2]} gl={{ antialias: true, preserveDrawingBuffer: true }}
      camera={{ fov: 22, near: 0.5, far: 400, position: [60, 80, 60] }}
      onPointerMissed={() => undefined}>
      <Scene {...props} handle={ref} />
    </Canvas>
  );
});

function Scene({ handle, ...p }: Props & { handle: React.ForwardedRef<OfficeCanvasHandle> }) {
  const controls = useRef<CameraControls>(null);
  const azimuth = useRef(Math.PI / 4);
  const { camera, size, invalidate, scene, gl } = useThree();
  const [hover, setHover] = useState<string | null>(null);

  useEffect(() => {
    scene.background = new THREE.Color(p.dark ? "#16171a" : "#DCD8D1");
    invalidate();
  }, [p.dark, scene, invalidate]);

  useEffect(() => {
    document.body.style.cursor = hover ? "pointer" : "";
    return () => { document.body.style.cursor = ""; };
  }, [hover]);

  // Static shadows: recomputed only when the layout changes.
  useEffect(() => {
    gl.shadowMap.autoUpdate = false;
    gl.shadowMap.needsUpdate = true;
    invalidate();
  }, [p.view, focusKey(p.level), gl, invalidate]);

  const fly = (target: THREE.Vector3, dist: number, fov: number, polar: number, instant = false) => {
    const c = controls.current;
    if (!c) return;
    const cam = camera as THREE.PerspectiveCamera;
    cam.fov = fov;
    cam.updateProjectionMatrix();
    const az = first.current ? azimuth.current : c.azimuthAngle;
    const pos = new THREE.Vector3(
      target.x + dist * Math.sin(polar) * Math.sin(az),
      target.y + dist * Math.cos(polar),
      target.z + dist * Math.sin(polar) * Math.cos(az),
    );
    void c.setLookAt(pos.x, pos.y, pos.z, target.x, target.y, target.z, !instant && !p.reducedMotion);
  };

  const levelKey = p.level.kind === "company" ? "company" : p.level.kind === "room" ? `room:${p.level.room}` : `agent:${p.level.agentId}`;
  const first = useRef(true);
  useEffect(() => {
    const instant = first.current;
    first.current = false;
    const l = p.level;
    if (l.kind === "company") fly(new THREE.Vector3(1.5, 0, 3.5), 112, 22, 0.86, instant);
    else if (l.kind === "room") {
      const r = ROOM_BY_ID[l.room];
      const c = new THREE.Vector3(OX + (r.x0 + r.x1) / 2, 0.6, OZ + (r.z0 + r.z1) / 2);
      fly(c, Math.max(r.x1 - r.x0, r.z1 - r.z0) * 1.55 + 3, 34, 0.9, instant);
    } else {
      const f = p.view.figures.find((x) => x.agentId === l.agentId);
      if (f) fly(new THREE.Vector3(OX + f.spot.x, 1, OZ + f.spot.z), 8.5, 34, 0.95, instant);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [levelKey]);

  // Keep the selected agent centred in the part of the canvas the drawer leaves visible.
  useEffect(() => {
    const cam = camera as THREE.PerspectiveCamera;
    const w = size.width, h = size.height;
    if (p.level.kind === "agent" && p.drawerWidth > 0 && w > p.drawerWidth + 200) cam.setViewOffset(w + p.drawerWidth, h, p.drawerWidth, 0, w, h);
    else cam.clearViewOffset();
    cam.updateProjectionMatrix();
    invalidate();
  }, [p.level.kind, p.drawerWidth, size.width, size.height, camera, invalidate]);

  useImperativeHandle(handle, () => ({
    overview: () => fly(new THREE.Vector3(1.5, 0, 3.5), 112, 22, 0.86),
    room: (id) => p.onRoom(id),
    rotate: (dir) => {
      const c = controls.current;
      if (!c) return;
      void c.rotateTo(c.azimuthAngle + (dir * Math.PI) / 2, c.polarAngle, !p.reducedMotion);
    },
    zoom: (f) => void controls.current?.dolly((controls.current.distance * (f - 1)) / f, !p.reducedMotion),
  }));

  // Report the camera target for the floor plan's viewport frame.
  const lastReport = useRef(0);
  useFrame(() => {
    const c = controls.current;
    if (!c) return;
    const now = performance.now();
    if (now - lastReport.current < 150) return;
    lastReport.current = now;
    const t = c.getTarget(new THREE.Vector3());
    const cam = camera as THREE.PerspectiveCamera;
    p.onTarget({ x: t.x - OX, z: t.z - OZ, span: c.distance * Math.tan((cam.fov * Math.PI) / 360) * 1.6 });
  });

  const focusRoom = p.level.kind === "room" ? p.level.room : p.level.kind === "agent" ? p.level.room : null;
  const selectedId = p.level.kind === "agent" ? p.level.agentId : null;

  return (
    <>
      <CameraControls ref={controls} makeDefault minDistance={6} maxDistance={140} minPolarAngle={0.55} maxPolarAngle={1.05}
        smoothTime={0.35} draggingSmoothTime={0.1}
        verticalDragToForward
        mouseButtons={{ left: 2 /* TRUCK */, middle: 16 /* DOLLY */, right: 1 /* ROTATE */, wheel: 16 /* DOLLY */ }}
        touches={{ one: 128 /* TOUCH_TRUCK */, two: 4096 /* TOUCH_DOLLY_TRUCK */, three: 0 }} />
      <hemisphereLight args={["#ffffff", "#C9C2B6", p.dark ? 0.9 : 1.35]} />
      <directionalLight position={[-18, 34, 14]} intensity={p.dark ? 1.5 : 2.1} color="#FFF4E6" castShadow
        shadow-mapSize={[2048, 2048]} shadow-bias={-0.0004} shadow-normalBias={0.02}
        shadow-camera-left={-28} shadow-camera-right={28} shadow-camera-top={24} shadow-camera-bottom={-24} shadow-camera-near={1} shadow-camera-far={90} />
      <directionalLight position={[20, 18, 24]} intensity={0.45} color="#DDE6F0" />
      <group position={ORIGIN}>
        <Building focus={focusRoom} onRoomDoubleClick={p.onRoom} />
        {p.view.desks.map((d) => <Workstation key={d.seat.agentId} view={d} />)}
        <MeetingScreens view={p.view} agents={p.agents} />
        {p.view.figures.map((f) => {
          const a = p.agents.get(f.agentId);
          if (!a) return null;
          const inFocus = focusRoom != null && roomAt(f.spot.x, f.spot.z)?.id === focusRoom;
          return (
            <Person key={f.agentId} figure={f} name={a.name} title={a.title} departmentColor={DEPARTMENT_COLORS[a.department]}
              selected={selectedId === f.agentId} showTag={p.level.kind === "room" ? inFocus : hover === f.agentId}
              enterFrom={p.arrivals.has(f.agentId) ? RECEPTION : null} walkOnChange={p.walkOnChange} reducedMotion={p.reducedMotion}
              onSelect={p.onSelect} onHover={setHover} />
          );
        })}
        <Arcs view={p.view} links={p.links} reducedMotion={p.reducedMotion} />
        {p.level.kind === "company" && <Signs view={p.view} onRoom={p.onRoom} />}
      </group>
    </>
  );
}

/** Wall screen of each occupied meeting room: real title and turn; empty rooms keep the screen off. */
const MeetingScreens = memo(function MeetingScreens({ view, agents }: { view: OfficeView; agents: Map<string, Agent> }) {
  return (
    <>
      {(["roomA", "roomB"] as const).map((id) => {
        const r = ROOM_BY_ID[id];
        const t = MEETING_TABLES[id];
        const m = view.meetings.find((x) => x.room === id);
        const w = id === "roomA" ? 2.8 : 2;
        const h = w * 0.5625;
        const speaker = m?.speakerId ? agents.get(m.speakerId)?.name : null;
        const line = !m ? "" : m.meeting.status === "SYNTHESIZING" ? "Writing the minutes" :
          `Round ${m.meeting.currentRound} of ${m.meeting.rounds}${speaker ? ` · ${speaker} speaking` : ""}`;
        const map = m ? meetingTexture(m.meeting.title, line) : sleepTexture();
        return (
          <group key={id} position={[r.x0 + t.cx, 0, r.z0 + 0.3]}>
            <RBox s={[w + 0.03, h + 0.03, 0.025]} r={0.01} m={M.bezel()} position={[0, 1.5, 0]} />
            <mesh geometry={planeGeo(w, h)} position={[0, 1.5, 0.014]}>
              <meshBasicMaterial map={map} toneMapped={false} />
            </mesh>
          </group>
        );
      })}
    </>
  );
});

/** Room signs in the company view: name and live headcount from the representation. */
function Signs({ view, onRoom }: { view: OfficeView; onRoom(id: RoomId): void }) {
  return (
    <>
      {ROOMS.map((r) => {
        const s = roomSummary(view, r.id);
        const m = view.meetings.find((x) => x.room === r.id);
        let text: string;
        let dot: string | null = null;
        if (r.kind === "meeting") {
          text = m ? `${m.meeting.title} · ${m.spots.size} people` : "Free";
          dot = m ? "bg-warn" : null;
        } else if (r.id === "lounge") text = "Reception · new hires arrive here";
        else if (r.id === "collab") text = "Open space";
        else if (r.id === "cto" && s.count === 0) text = "Your office";
        else if (s.count === 0) text = "No agent";
        else {
          const parts = [`${s.count} agent${s.count > 1 ? "s" : ""}`];
          if (s.thinking) parts.push(`${s.thinking} thinking`);
          if (s.away) parts.push(`${s.away} in a meeting`);
          if (s.blocked) parts.push(`${s.blocked} blocked`);
          text = parts.join(" · ");
          dot = s.blocked ? "bg-danger" : s.thinking ? "bg-active" : s.away ? "bg-warn" : "bg-ok";
        }
        return (
          <Html key={r.id} position={[(r.x0 + r.x1) / 2, 3, (r.z0 + r.z1) / 2 - 1.2]} center zIndexRange={[10, 0]}>
            <button onClick={() => onRoom(r.id)} disabled={r.open}
              className="whitespace-nowrap rounded border border-line-strong bg-panel/95 px-2.5 py-1 text-left text-fg enabled:hover:border-fg">
              <b className="block text-[11px] font-semibold uppercase leading-[14px] tracking-[0.08em]" style={{ color: r.kind === "common" ? undefined : r.color }}>{r.name}</b>
              <span className="flex items-center gap-1.5 text-[11px] leading-[15px] text-muted">
                {dot && <i className={`inline-block h-1.5 w-1.5 rounded-full ${dot}`} />}{text}
              </span>
            </button>
          </Html>
        );
      })}
    </>
  );
}

const ARC_MS = 4000;

/** A real message between two agents: an arc between their heads with a travelling pulse, for 4 s. */
function Arcs({ view, links, reducedMotion }: { view: OfficeView; links: LinkArc[]; reducedMotion: boolean }) {
  const invalidate = useThree((s) => s.invalidate);
  const [now, setNow] = useState(() => Date.now());
  const live = links.filter((l) => now - l.at < ARC_MS);
  useEffect(() => {
    if (!links.some((l) => Date.now() - l.at < ARC_MS)) return;
    setNow(Date.now());
    const t = setInterval(() => setNow(Date.now()), 500);
    const stop = setTimeout(() => clearInterval(t), ARC_MS + 600);
    return () => { clearInterval(t); clearTimeout(stop); };
  }, [links]);
  useEffect(() => invalidate(), [live.length, invalidate]);
  return (
    <>
      {live.map((l) => {
        const a = view.figures.find((f) => f.agentId === l.from), b = view.figures.find((f) => f.agentId === l.to);
        if (!a || !b) return null;
        const h = (f: typeof a) => (["present", "stand"].includes(f.posture) ? 1.75 : 1.35);
        return <Arc key={l.id} a={new THREE.Vector3(a.spot.x, h(a), a.spot.z)} b={new THREE.Vector3(b.spot.x, h(b), b.spot.z)} start={l.at} still={reducedMotion} />;
      })}
    </>
  );
}

function Arc({ a, b, start, still }: { a: THREE.Vector3; b: THREE.Vector3; start: number; still: boolean }) {
  const curve = useMemo(() => {
    const mid = a.clone().add(b).multiplyScalar(0.5);
    mid.y += Math.min(4.5, 1.5 + a.distanceTo(b) * 0.25);
    return new THREE.QuadraticBezierCurve3(a, mid, b);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [a.x, a.y, a.z, b.x, b.y, b.z]);
  const tube = useMemo(() => new THREE.TubeGeometry(curve, 64, 0.035, 8), [curve]);
  const pulse = useRef<THREE.Mesh>(null);
  useFrame(({ invalidate }) => {
    if (!pulse.current || still) return;
    const k = ((Date.now() - start) / 1400) % 1;
    pulse.current.position.copy(curve.getPoint(k));
    invalidate();
  });
  return (
    <>
      <mesh geometry={tube}>
        <meshBasicMaterial color="#4F8F67" transparent opacity={0.8} toneMapped={false} />
      </mesh>
      <mesh ref={pulse} position={curve.getPoint(0.5)}>
        <sphereGeometry args={[0.12, 16, 12]} />
        <meshBasicMaterial color="#4F8F67" toneMapped={false} />
      </mesh>
    </>
  );
}

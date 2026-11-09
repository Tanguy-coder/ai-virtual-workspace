import { Html } from "@react-three/drei";
import { useFrame, useThree, type ThreeEvent } from "@react-three/fiber";
import { memo, useEffect, useLayoutEffect, useMemo, useRef } from "react";
import * as THREE from "three";
import { basic, Box, capsuleGeo, cylGeo, mat, sphereGeo } from "./kit";
import { along, route, routeLength, type Spot } from "./plan";
import type { Figure, Posture } from "./represent";

/**
 * A collaborator: an articulated figure (hips, knees, shoulders, elbows, neck) posed by the representation
 * engine. Joint angles are the only thing that changes, so a rigged glTF character can replace the body
 * without touching the staging logic. Motion happens only while its recorded cause lasts.
 */

const SKIN = ["#E7C3A1", "#C4956E", "#8A5B3C", "#F0D4B8", "#AE7A52", "#D9AE89"];
const HAIR = ["#2A2421", "#58402A", "#161616", "#8B6B4A", "#B8AFA2", "#3A2A1E"];
const OUTFITS = [
  { jacket: "#2E3B55", trousers: "#2A2D33", shirt: "#F2F0EC" },
  { jacket: "#3B3D42", trousers: "#25272B", shirt: "#F2F0EC" },
  { jacket: "#A88A64", trousers: "#3A3631", shirt: "#F4F1EA" },
  { jacket: "#5E6450", trousers: "#2E2F2A", shirt: "#F2F0EC" },
  { jacket: "#C9C2B4", trousers: "#3E4148", shirt: "#2E3036" },
  { jacket: "#4B5563", trousers: "#22252A", shirt: "#F2F0EC" },
  { jacket: "#EDEBE6", trousers: "#30333A", shirt: "#DCD8D1" },
  { jacket: "#6B3A40", trousers: "#2A2B30", shirt: "#F2F0EC" },
];

function hash(s: string) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

export function appearance(id: string) {
  const h = hash(id);
  return { skin: SKIN[h % 6], hair: HAIR[(h >>> 3) % 6], outfit: OUTFITS[(h >>> 6) % 8], long: ((h >>> 9) & 3) === 0, glasses: ((h >>> 11) & 3) === 1 };
}

const STATE_COLORS = { thinking: "#3462D8", meeting: "#B7832A", blocked: "#C5303B" } as const;
const TONE_CLASS: Record<Figure["tone"], string> = { active: "bg-active", warn: "bg-warn", danger: "bg-danger", ok: "bg-ok", muted: "bg-faint" };

/** Joint angles of a pose. pitch: arm forward, bend: elbow, yaw: inward. */
interface Arm { pitch: number; bend: number; yaw: number }
interface Pose { seated: boolean; lean: number; left: Arm; right: Arm }

const A = (pitch: number, bend: number, yaw: number): Arm => ({ pitch, bend, yaw });
const POSES: Record<Posture | "walk", Pose> = {
  type: { seated: true, lean: 0.08, left: A(0.45, 1.15, 0.25), right: A(0.45, 1.15, 0.25) },
  idle: { seated: true, lean: -0.03, left: A(0.3, 0.9, 0.2), right: A(0.3, 0.9, 0.2) },
  crossed: { seated: true, lean: -0.12, left: A(0.2, 1.45, 1.0), right: A(0.25, 1.5, 1.05) },
  table: { seated: true, lean: 0.05, left: A(0.4, 1.1, 0.3), right: A(0.4, 1.1, 0.3) },
  talk: { seated: true, lean: 0.1, left: A(0.4, 1.1, 0.3), right: A(0.7, 1.3, 0.1) },
  present: { seated: false, lean: 0, left: A(0.05, 0.15, 0), right: A(1.25, 0.35, -0.2) },
  stand: { seated: false, lean: 0, left: A(0.05, 0.15, 0.05), right: A(0.05, 0.15, 0.05) },
  walk: { seated: false, lean: 0.04, left: A(0, 0.25, 0), right: A(0, 0.25, 0) },
};

const SPEED = 1.6; // m/s
const MAX_WALK_S = 6; // long routes are walked faster so nobody is in transit for long
const SEATED_HIP = 0.5;
const STAND_HIP = 0.94;

interface Joints {
  root: THREE.Group;
  torso: THREE.Group;
  head: THREE.Group;
  hips: THREE.Group[];
  knees: THREE.Group[];
  shoulders: THREE.Group[];
  elbows: THREE.Group[];
  lantern: THREE.Group;
}

function applyPose(j: Joints, pose: Pose, swing = 0) {
  const hipY = pose.seated ? SEATED_HIP : STAND_HIP;
  j.torso.position.y = hipY;
  j.torso.rotation.x = pose.lean;
  j.hips.forEach((h, i) => {
    h.position.y = hipY;
    const s = i === 0 ? 1 : -1;
    h.rotation.x = pose.seated ? -Math.PI / 2 : -swing * s;
    j.knees[i].rotation.x = pose.seated ? Math.PI / 2 : Math.max(0, swing * s) * 0.6;
  });
  [pose.left, pose.right].forEach((arm, i) => {
    const s = i === 0 ? -1 : 1;
    const sh = j.shoulders[i];
    sh.rotation.set(-(arm.pitch + swing * s * 0.6), -arm.yaw * s, 0.08 * s, "YXZ");
    j.elbows[i].rotation.x = -arm.bend;
  });
  j.lantern.position.y = (pose.seated ? 1.25 : 1.68) + 0.36;
}

export interface PersonProps {
  figure: Figure;
  name: string;
  title: string;
  departmentColor: string;
  selected: boolean;
  showTag: boolean;
  /** Spot to walk from when this figure first appears (a live hire walks in from the reception). */
  enterFrom: { x: number; z: number } | null;
  /** Walk when the spot changes; false while the page is still loading its data. */
  walkOnChange: boolean;
  reducedMotion: boolean;
  onSelect(id: string): void;
  onHover(id: string | null): void;
}

export const Person = memo(function Person(p: PersonProps) {
  const { figure, selected, showTag } = p;
  const look = useMemo(() => appearance(figure.agentId), [figure.agentId]);
  const invalidate = useThree((s) => s.invalidate);
  const gl = useThree((s) => s.gl);
  const j = useRef<Joints>({ hips: [], knees: [], shoulders: [], elbows: [] } as unknown as Joints);
  const walk = useRef<{ pts: { x: number; z: number }[]; len: number; speed: number; start: number } | null>(null);
  const last = useRef<Spot | null>(null);
  const pose = POSES[figure.posture];
  const fade = figure.faded ? { transparent: true, opacity: 0.4 } : {};
  const m = useMemo(() => ({
    skin: mat(look.skin, fade),
    hair: mat(look.hair, { roughness: 0.95, ...fade }),
    jacket: mat(look.outfit.jacket, { roughness: 0.9, ...fade }),
    trousers: mat(look.outfit.trousers, fade),
    shirt: mat(look.outfit.shirt, fade),
    shoe: mat("#1B1B1E", fade),
    lanyard: mat(p.departmentColor, fade),
    badge: mat("#FFFFFF", fade),
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }), [look, figure.faded, p.departmentColor]);

  // Place or walk when the recorded spot changes.
  useLayoutEffect(() => {
    const root = j.current.root;
    const to = figure.spot;
    const prev = last.current;
    last.current = to;
    let from: { x: number; z: number } | null = null;
    if (!prev) from = p.enterFrom; // a live hire walks in from the reception
    else if (p.walkOnChange) from = walk.current ? { x: root.position.x, z: root.position.z } : prev;
    if (from && !p.reducedMotion && Math.hypot(from.x - to.x, from.z - to.z) > 0.3) {
      const pts = route(from, to);
      const len = routeLength(pts);
      walk.current = { pts, len, speed: Math.max(SPEED, len / MAX_WALK_S), start: performance.now() };
      invalidate();
    } else {
      walk.current = null;
      root.position.set(to.x, 0, to.z);
      root.rotation.y = to.ry;
      applyPose(j.current, pose);
      gl.shadowMap.needsUpdate = true;
      invalidate();
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [figure.spot.x, figure.spot.z, figure.spot.ry]);

  useLayoutEffect(() => {
    if (!walk.current) applyPose(j.current, pose);
    gl.shadowMap.needsUpdate = true;
    invalidate();
  }, [pose, invalidate, gl]);

  const animated = figure.posture === "type" || figure.posture === "talk" || figure.posture === "present" || figure.lantern === "thinking";
  useEffect(() => {
    if (animated) invalidate();
  }, [animated, invalidate]);

  useFrame(({ clock }) => {
    const jt = j.current;
    const t = clock.elapsedTime;
    const w = walk.current;
    if (w) {
      const d = ((performance.now() - w.start) / 1000) * w.speed;
      if (d >= w.len) {
        walk.current = null;
        jt.root.position.set(figure.spot.x, 0, figure.spot.z);
        jt.root.rotation.y = figure.spot.ry;
        applyPose(jt, pose);
      } else {
        const at = along(w.pts, d);
        jt.root.position.set(at.x, 0, at.z);
        jt.root.rotation.y = at.ry;
        applyPose(jt, POSES.walk, Math.sin((d / w.speed) * SPEED * 4.2) * 0.45);
      }
      gl.shadowMap.needsUpdate = true;
      invalidate();
      return;
    }
    if (!animated || p.reducedMotion) return;
    // Motion only while the cause is recorded: a model call running, or the agent's turn in a meeting.
    if (figure.posture === "type") jt.elbows.forEach((e, i) => (e.rotation.x = -pose[i ? "right" : "left"].bend + Math.sin(t * 14 + i * 1.7) * 0.05));
    if (figure.posture === "talk" || figure.posture === "present") {
      jt.elbows[1].rotation.x = -pose.right.bend + Math.sin(t * 1.6) * 0.18;
      jt.head.rotation.y = Math.sin(t * 0.6) * 0.3;
    }
    const ring = jt.lantern.children[1] as THREE.Mesh | undefined;
    if (ring && figure.lantern === "thinking") {
      const k = (t * 0.7) % 1;
      ring.scale.setScalar(1 + k * 0.6);
      (ring.material as THREE.MeshBasicMaterial).opacity = 0.5 * (1 - k);
    }
    invalidate();
  });

  const click = (e: ThreeEvent<MouseEvent>) => {
    e.stopPropagation();
    p.onSelect(figure.agentId);
  };

  return (
    <group
      ref={(g) => { if (g) j.current.root = g; }}
      onClick={click}
      onPointerOver={(e) => { e.stopPropagation(); p.onHover(figure.agentId); }}
      onPointerOut={() => p.onHover(null)}
      userData={{ agentId: figure.agentId }}
    >
      {[0, 1].map((i) => {
        const s = i === 0 ? 1 : -1;
        return (
          <group key={i} ref={(g) => { if (g) j.current.hips[i] = g; }} position={[s * 0.1, STAND_HIP, 0]}>
            <mesh geometry={capsuleGeo(0.075, 0.29)} material={m.trousers} position={[0, -0.22, 0]} castShadow />
            <group ref={(g) => { if (g) j.current.knees[i] = g; }} position={[0, -0.44, 0]}>
              <mesh geometry={capsuleGeo(0.065, 0.3)} material={m.trousers} position={[0, -0.215, 0]} castShadow />
              <Box s={[0.1, 0.07, 0.24]} m={m.shoe} position={[0, -0.43, 0.06]} />
            </group>
          </group>
        );
      })}
      <group ref={(g) => { if (g) j.current.torso = g; }} position={[0, STAND_HIP, 0]}>
        <mesh geometry={capsuleGeo(0.17, 0.3)} material={m.jacket} position={[0, 0.32, 0]} scale={[1.08, 1, 0.72]} castShadow />
        <Box s={[0.12, 0.22, 0.02]} m={m.shirt} position={[0, 0.4, 0.118]} />
        <Box s={[0.015, 0.2, 0.01]} m={m.lanyard} position={[-0.07, 0.38, 0.122]} rotation-z={0.25} />
        <Box s={[0.05, 0.07, 0.012]} m={m.badge} position={[-0.04, 0.26, 0.124]} />
        <mesh geometry={cylGeo(0.05, 0.055, 0.1, 12)} material={m.skin} position={[0, 0.6, 0]} />
        <group ref={(g) => { if (g) j.current.head = g; }} position={[0, 0.73, 0.01]}>
          <mesh geometry={sphereGeo(0.105, 20, 16)} material={m.skin} scale={[0.92, 1.08, 1]} castShadow />
          <Hair long={look.long} material={m.hair} />
          {look.glasses && <Box s={[0.16, 0.012, 0.01]} m={mat("#1B1B1E")} position={[0, 0.01, 0.1]} />}
        </group>
        {[0, 1].map((i) => {
          const s = i === 0 ? -1 : 1;
          return (
            <group key={i} ref={(g) => { if (g) j.current.shoulders[i] = g; }} position={[s * 0.21, 0.5, 0]}>
              <mesh geometry={capsuleGeo(0.055, 0.17)} material={m.jacket} position={[0, -0.14, 0]} castShadow />
              <group ref={(g) => { if (g) j.current.elbows[i] = g; }} position={[0, -0.28, 0]}>
                <mesh geometry={capsuleGeo(0.047, 0.18)} material={m.jacket} position={[0, -0.135, 0]} castShadow />
                <mesh geometry={sphereGeo(0.045, 10, 8)} material={m.skin} position={[0, -0.29, 0]} />
              </group>
            </group>
          );
        })}
      </group>
      <group ref={(g) => { if (g) j.current.lantern = g; }} position={[0, 2.04, 0]}>
        <Lantern kind={figure.lantern} />
      </group>
      {selected && (
        <mesh rotation-x={-Math.PI / 2} position={[0, 0.02, 0]}>
          <ringGeometry args={[0.42, 0.5, 40]} />
          <meshBasicMaterial color="#E8552B" toneMapped={false} />
        </mesh>
      )}
      {(showTag || selected) && (
        <Html position={[0, pose.seated ? 1.95 : 2.35, 0]} center zIndexRange={[20, 0]} style={{ pointerEvents: "none" }}>
          <div className={`whitespace-nowrap rounded border bg-panel px-2 py-1 text-fg ${selected ? "border-accent ring-1 ring-accent" : "border-line-strong"}`}
            style={{ transform: "translateY(-50%)" }}>
            <p className="text-[12px] font-medium leading-4">{p.name}</p>
            <p className="flex max-w-[240px] items-center gap-1.5 truncate text-[11px] leading-[14px] text-muted">
              <span className={`inline-block h-[7px] w-[7px] shrink-0 ${TONE_CLASS[figure.tone]} ${figure.lantern === "blocked" ? "rotate-45" : "rounded-full"}`} />
              <span className="truncate">{figure.label}</span>
            </p>
          </div>
        </Html>
      )}
    </group>
  );
});

function Hair({ long, material }: { long: boolean; material: THREE.Material }) {
  const cap = useMemo(() => new THREE.SphereGeometry(0.11, 20, 14, 0, Math.PI * 2, 0, long ? Math.PI * 0.62 : Math.PI * 0.5), [long]);
  return (
    <>
      <mesh geometry={cap} material={material} position={[0, 0.012, -0.012]} scale={[0.96, 1.08, 1.04]} castShadow />
      {long && <mesh geometry={capsuleGeo(0.085, 0.14)} material={material} position={[0, -0.08, -0.05]} scale-z={0.6} />}
    </>
  );
}

function Lantern({ kind }: { kind: Figure["lantern"] }) {
  if (!kind) return null;
  const color = STATE_COLORS[kind];
  if (kind === "blocked") {
    return (
      <mesh scale-y={1.3}>
        <octahedronGeometry args={[0.075]} />
        <primitive object={basic(color)} attach="material" />
      </mesh>
    );
  }
  return (
    <>
      <mesh geometry={sphereGeo(0.05, 16, 12)} material={basic(color)} />
      {kind === "thinking" && (
        <mesh rotation-x={Math.PI / 2}>
          <torusGeometry args={[0.085, 0.008, 8, 32]} />
          <meshBasicMaterial color={color} transparent opacity={0.45} toneMapped={false} />
        </mesh>
      )}
    </>
  );
}

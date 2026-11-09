import type { ThreeEvent } from "@react-three/fiber";
import { memo, useMemo } from "react";
import * as THREE from "three";
import { Box, Cyl, floorGeo, glassMat, M, mat, mix, planeGeo, RBox, sphereGeo } from "./kit";
import { CTO_DESK, D, MEETING_TABLES, meetingChairs, ROOM_BY_ID, ROOMS, W, type Room, type RoomId } from "./plan";
import { boardTexture, carpetTexture, sleepTexture, woodTexture } from "./textures";

export const LOW = 1.05;
export const FULL = 2.7;
const PI = Math.PI;

/** Static architecture: slab, floors, partitions and the furniture that never depends on state. */
export const Building = memo(function Building({ focus, onRoomDoubleClick }: { focus: RoomId | null; onRoomDoubleClick(room: RoomId): void }) {
  return (
    <group>
      <RBox s={[W + 1.2, 0.5, D + 1.2]} r={0.06} m={mat("#D2CDC5")} position={[W / 2, -0.26, D / 2]} />
      <mesh geometry={floorGeo(W, D)} material={mat("#E7E4DE")} position={[W / 2, 0.001, D / 2]} receiveShadow />
      {[10.5, 22.5].map((z) => (
        <mesh key={z} geometry={floorGeo(W - 1, 0.04)} material={mat("#D6D2CB")} position={[W / 2, 0.004, z]} />
      ))}
      {ROOMS.map((r) => (
        <Floor key={r.id} room={r} onDoubleClick={(e) => { e.stopPropagation(); onRoomDoubleClick(r.id); }} />
      ))}
      <Walls focus={focus} />
      <Furniture />
    </group>
  );
});

function Floor({ room: r, onDoubleClick }: { room: Room; onDoubleClick(e: ThreeEvent<MouseEvent>): void }) {
  const material = useMemo(() => {
    const w = r.x1 - r.x0, d = r.z1 - r.z0;
    if (r.floor === "wood") return new THREE.MeshStandardMaterial({ map: woodTexture(w / 4, d / 4), roughness: 0.7 });
    if (r.floor === "dark") return new THREE.MeshStandardMaterial({ map: carpetTexture(mix("#55575D", r.color, 0.07)), roughness: 0.95 });
    return new THREE.MeshStandardMaterial({ map: carpetTexture(mix("#EDEAE4", r.color, 0.16)), roughness: 0.95 });
  }, [r]);
  return (
    <mesh geometry={floorGeo(r.x1 - r.x0 - 0.12, r.z1 - r.z0 - 0.12)} material={material} position={[(r.x0 + r.x1) / 2, 0.006, (r.z0 + r.z1) / 2]}
      receiveShadow onDoubleClick={onDoubleClick} userData={{ room: r.id }} />
  );
}

interface WallDef { key: string; room: RoomId | null; x0: number; z0: number; x1: number; z1: number; glass?: boolean; door?: [number, number] | null; exterior?: boolean; rise?: boolean }

function wallDefs(): WallDef[] {
  const out: WallDef[] = [];
  const doorAt: [number, number] = [1.0, 2.3];
  for (const r of ROOMS) {
    if (r.open) continue;
    const north = r.z0 === 0;
    out.push({ key: `${r.id}-n`, room: r.id, x0: r.x0, z0: r.z0, x1: r.x1, z1: r.z0, glass: r.glass || north, door: north ? null : doorAt, exterior: north, rise: true });
    out.push({ key: `${r.id}-s`, room: r.id, x0: r.x0, z0: r.z1, x1: r.x1, z1: r.z1, glass: r.glass, door: north ? doorAt : null, exterior: r.z1 === D });
    out.push({ key: `${r.id}-w`, room: r.id, x0: r.x0, z0: r.z0, x1: r.x0, z1: r.z1, glass: r.glass && r.x0 !== 0, exterior: r.x0 === 0, rise: true });
    const next = ROOMS.find((o) => o.x0 === r.x1 && o.z0 === r.z0);
    if (!next || next.open) out.push({ key: `${r.id}-e`, room: r.id, x0: r.x1, z0: r.z0, x1: r.x1, z1: r.z1, glass: r.glass, exterior: r.x1 === W });
  }
  out.push({ key: "shell-e", room: null, x0: 40, z0: 12, x1: 40, z1: 32, exterior: true });
  out.push({ key: "shell-s", room: null, x0: 27, z0: 32, x1: 40, z1: 32, exterior: true, glass: true });
  for (const [a, b] of [[9, 12], [21, 24]]) {
    out.push({ key: `shell-w${a}`, room: null, x0: 0, z0: a, x1: 0, z1: b, exterior: true });
    out.push({ key: `shell-e${a}`, room: null, x0: 40, z0: a, x1: 40, z1: b, exterior: true });
  }
  return out;
}

const WALLS = wallDefs();

function Walls({ focus }: { focus: RoomId | null }) {
  return (
    <group>
      {WALLS.map((w) => (
        <Wall key={w.key} def={w} h={w.room && w.room === focus ? (w.rise ? FULL : 1.6) : LOW} />
      ))}
    </group>
  );
}

const Wall = memo(function Wall({ def, h }: { def: WallDef; h: number }) {
  const { x0, z0, x1, z1, glass, door, exterior } = def;
  const len = Math.hypot(x1 - x0, z1 - z0);
  const alongX = Math.abs(z1 - z0) < 1e-6;
  const pieces: [number, number][] = door ? [[0, door[0]], [door[1], len]] : [[0, len]];
  const t = exterior ? 0.22 : 0.12;
  const at = (s: number): [number, number] => (alongX ? [Math.min(x0, x1) + s, z0] : [x0, Math.min(z0, z1) + s]);
  return (
    <group>
      {pieces.filter(([a, b]) => b - a > 0.05).map(([a, b]) => {
        const l = b - a;
        const [cx, cz] = at((a + b) / 2);
        if (glass) {
          const posts: number[] = [];
          for (let s = 0; s <= l + 1e-6; s += 1.5) posts.push(a + s);
          return (
            <group key={a}>
              <Box s={[alongX ? l : 0.04, h, alongX ? 0.04 : l]} m={glassMat()} position={[cx, h / 2, cz]} shadow={false} />
              <Box s={[alongX ? l : 0.06, 0.05, alongX ? 0.06 : l]} m={M.frame()} position={[cx, h, cz]} />
              <Box s={[alongX ? l : 0.06, 0.06, alongX ? 0.06 : l]} m={M.frame()} position={[cx, 0.03, cz]} />
              {posts.map((p) => {
                const [px, pz] = at(p);
                return <Box key={p} s={[0.06, h, 0.06]} m={M.frame()} position={[px, h / 2, pz]} />;
              })}
            </group>
          );
        }
        return (
          <group key={a}>
            <Box s={[alongX ? l : t, h, alongX ? t : l]} m={M.wall()} position={[cx, h / 2, cz]} />
            <Box s={[alongX ? l : t + 0.01, 0.025, alongX ? t + 0.01 : l]} m={M.wallTop()} position={[cx, h, cz]} />
          </group>
        );
      })}
      {door?.map((d) => {
        const [px, pz] = at(d);
        return <Box key={d} s={[0.08, h, 0.08]} m={M.wallTop()} position={[px, h / 2, pz]} />;
      })}
    </group>
  );
});

// ---------- furniture ----------

export function Chair({ x, z, ry = 0, pushed = false }: { x: number; z: number; ry?: number; pushed?: boolean }) {
  return (
    <group position={[x, 0, z]} rotation-y={ry}>
      <group position-z={pushed ? 0.18 : 0}>
        <Cyl a={[0.03, 0.03, 0.36, 8]} m={M.metal()} position={[0, 0.2, 0]} />
        {[0, 1, 2, 3, 4].map((i) => {
          const a = (i / 5) * PI * 2;
          return <Box key={i} s={[0.26, 0.03, 0.04]} m={M.metal()} position={[Math.cos(a) * 0.13, 0.04, Math.sin(a) * 0.13]} rotation-y={-a} />;
        })}
        <RBox s={[0.48, 0.08, 0.46]} r={0.03} m={M.chairSeat()} position={[0, 0.44, 0]} />
        <RBox s={[0.46, 0.52, 0.06]} r={0.03} m={M.chair()} position={[0, 0.76, -0.24]} rotation-x={-0.08} />
      </group>
    </group>
  );
}

export function Plant({ x, z, s = 1 }: { x: number; z: number; s?: number }) {
  return (
    <group position={[x, 0, z]} scale={s}>
      <Cyl a={[0.16, 0.12, 0.32]} m={M.pot()} position={[0, 0.16, 0]} />
      {[0, 1, 2, 3, 4, 5].map((i) => (
        <mesh key={i} geometry={sphereGeo(0.17, 10, 8)} material={M.leaf()} position={[Math.cos(i) * 0.11, 0.48 + (i % 3) * 0.14, Math.sin(i * 1.7) * 0.11]} scale-y={1.4} castShadow />
      ))}
    </group>
  );
}

function Whiteboard({ x, z, ry, w = 2.2 }: { x: number; z: number; ry: number; w?: number }) {
  return (
    <group position={[x, 0, z]} rotation-y={ry}>
      <Box s={[0.04, 1.6, 0.04]} m={M.frame()} position={[-w / 2 + 0.1, 0.8, 0]} />
      <Box s={[0.04, 1.6, 0.04]} m={M.frame()} position={[w / 2 - 0.1, 0.8, 0]} />
      <RBox s={[w, 1.15, 0.05]} r={0.01} m={mat("#FBFAF7")} position={[0, 1.35, 0]} />
      <mesh geometry={planeGeo(w - 0.12, 1.03)} position={[0, 1.35, 0.03]}>
        <meshBasicMaterial map={boardTexture()} toneMapped={false} />
      </mesh>
    </group>
  );
}

/** A wall-mounted display that is off: nothing real feeds it yet. */
function DarkScreen({ x, z, ry, w = 2.4, h = 1.35, y = 1.45 }: { x: number; z: number; ry: number; w?: number; h?: number; y?: number }) {
  return (
    <group position={[x, 0, z]} rotation-y={ry}>
      <RBox s={[w + 0.03, h + 0.03, 0.025]} r={0.01} m={M.bezel()} position={[0, y, 0]} />
      <mesh geometry={planeGeo(w, h)} position={[0, y, 0.014]}>
        <meshBasicMaterial map={sleepTexture()} toneMapped={false} />
      </mesh>
    </group>
  );
}

export function MeetingTable({ cx, cz, len, wide }: { cx: number; cz: number; len: number; wide: number }) {
  return (
    <group>
      <RBox s={[len, 0.06, wide]} r={0.03} m={mat("#EDE7DD")} position={[cx, 0.74, cz]} />
      <Cyl a={[0.1, 0.2, 0.72]} m={M.metal()} position={[cx - len / 3, 0.36, cz]} />
      <Cyl a={[0.1, 0.2, 0.72]} m={M.metal()} position={[cx + len / 3, 0.36, cz]} />
    </group>
  );
}

function Sofa({ x, z, ry, w = 2, color = "#8C8F86" }: { x: number; z: number; ry: number; w?: number; color?: string }) {
  const m = mat(color, { roughness: 0.95 });
  return (
    <group position={[x, 0, z]} rotation-y={ry}>
      <RBox s={[w, 0.42, 0.85]} r={0.08} m={m} position={[0, 0.25, 0]} />
      <RBox s={[w, 0.5, 0.22]} r={0.08} m={m} position={[0, 0.6, -0.33]} />
      <RBox s={[0.2, 0.55, 0.85]} r={0.08} m={m} position={[-w / 2 + 0.1, 0.32, 0]} />
      <RBox s={[0.2, 0.55, 0.85]} r={0.08} m={m} position={[w / 2 - 0.1, 0.32, 0]} />
    </group>
  );
}

function Rack({ x, z }: { x: number; z: number }) {
  return (
    <group position={[x, 0, z]}>
      <RBox s={[0.66, 2.0, 0.9]} r={0.02} m={mat("#26282E", { roughness: 0.6 })} position={[0, 1.0, 0]} />
      {Array.from({ length: 12 }, (_, i) => (
        <Box key={i} s={[0.56, 0.1, 0.02]} m={mat("#34373F")} position={[0, 0.2 + i * 0.14, 0.455]} />
      ))}
    </group>
  );
}

function Rug({ x, z, w, d, color }: { x: number; z: number; w: number; d: number; color: string }) {
  return <mesh geometry={floorGeo(w, d)} material={mat(color, { roughness: 1 })} position={[x, 0.012, z]} receiveShadow />;
}

/** Desk with empty screens, used for the human's own desk. */
function EmptyDesk({ x, z, ry, wide }: { x: number; z: number; ry: number; wide?: boolean }) {
  const dw = wide ? 1.8 : 1.5;
  return (
    <group position={[x, 0, z]} rotation-y={ry}>
      <RBox s={[dw, 0.04, 0.78]} r={0.015} m={M.deskTop()} position={[0, 0.74, 0.62]} />
      {[-dw / 2 + 0.06, dw / 2 - 0.06].map((sx) => <Box key={sx} s={[0.05, 0.72, 0.62]} m={M.metal()} position={[sx, 0.36, 0.62]} />)}
      {[-0.3, 0.3].map((o) => (
        <group key={o}>
          <Cyl a={[0.015, 0.015, 0.22, 8]} m={M.metal()} position={[o, 0.87, 0.86]} />
          <RBox s={[0.59, 0.36, 0.025]} r={0.01} m={M.bezel()} position={[o, 1.08, 0.86]} />
        </group>
      ))}
      <Chair x={0} z={0} pushed />
    </group>
  );
}

const Furniture = memo(function Furniture() {
  const R = ROOM_BY_ID;
  const at = (id: RoomId, x: number, z: number) => [R[id].x0 + x, R[id].z0 + z] as const;
  const p = (id: RoomId, x: number, z: number, s?: number) => {
    const [px, pz] = at(id, x, z);
    return <Plant key={`${id}${x}${z}`} x={px} z={pz} s={s} />;
  };
  return (
    <group>
      {/* CTO office: the human's desk stays empty; it is yours */}
      <EmptyDesk x={R.cto.x0 + CTO_DESK.x} z={R.cto.z0 + CTO_DESK.z} ry={CTO_DESK.ry} wide />
      <Sofa x={R.cto.x0 + 3.5} z={R.cto.z0 + 7.4} ry={0} w={2} color="#6E6A86" />
      <Rug x={R.cto.x0 + 3.5} z={R.cto.z0 + 7.0} w={4.6} d={2.2} color="#D9D3E0" />
      {p("cto", 6.3, 0.7)}{p("cto", 0.7, 8.3, 0.8)}

      <Whiteboard x={R.arch.x0 + 3.4} z={R.arch.z0 + 0.35} ry={0} w={3.4} />
      <MeetingTable cx={R.arch.x0 + 2.8} cz={R.arch.z0 + 6.4} len={2.2} wide={1.1} />
      {[-0.6, 0.6].map((d) => <Chair key={`a${d}`} x={R.arch.x0 + 2.8 + d} z={R.arch.z0 + 5.55} pushed />)}
      {[-0.6, 0.6].map((d) => <Chair key={`b${d}`} x={R.arch.x0 + 2.8 + d} z={R.arch.z0 + 7.25} ry={PI} pushed />)}
      {p("arch", 8.4, 8.3)}

      <Whiteboard x={R.product.x0 + 3.9} z={R.product.z0 + 0.35} ry={0} w={3.6} />
      {p("product", 7.4, 8.2)}

      {(["roomA", "roomB"] as const).map((id) => {
        const t = MEETING_TABLES[id];
        return (
          <group key={id}>
            <MeetingTable cx={R[id].x0 + t.cx} cz={R[id].z0 + t.cz} len={t.len} wide={t.wide} />
            {meetingChairs(id).map((c, i) => <Chair key={i} x={c.x} z={c.z} ry={c.ry} />)}
          </group>
        );
      })}
      {p("roomA", 0.7, 8.2, 0.9)}

      <Whiteboard x={R.backend.x0 + 12.3} z={R.backend.z0 + 4.8} ry={-PI / 2} w={2.4} />
      {p("backend", 12.3, 8.3)}{p("backend", 0.6, 8.3, 0.85)}
      {p("frontend", 8.3, 8.3)}
      {p("specialists", 6.3, 8.3, 0.9)}

      <MeetingTable cx={R.collab.x0 + 3} cz={R.collab.z0 + 3} len={2.4} wide={1.2} />
      {[-0.6, 0.6].map((d) => <Chair key={`c${d}`} x={R.collab.x0 + 3 + d} z={R.collab.z0 + 2.1} pushed />)}
      {[-0.6, 0.6].map((d) => <Chair key={`d${d}`} x={R.collab.x0 + 3 + d} z={R.collab.z0 + 3.9} ry={PI} pushed />)}
      <Whiteboard x={R.collab.x0 + 7.5} z={R.collab.z0 + 0.4} ry={0} w={3} />
      <Rug x={R.collab.x0 + 7.6} z={R.collab.z0 + 6.2} w={4.4} d={3.2} color="#C9C2B4" />
      <Sofa x={R.collab.x0 + 7.6} z={R.collab.z0 + 7.4} ry={PI} w={2.4} color="#6F7A70" />
      <Sofa x={R.collab.x0 + 5.6} z={R.collab.z0 + 6.2} ry={PI / 2} w={1.6} color="#6F7A70" />
      <Cyl a={[0.45, 0.45, 0.4, 28]} m={mat("#EDE7DD")} position={[R.collab.x0 + 7.6, 0.2, R.collab.z0 + 6.1]} />
      {p("collab", 10.3, 0.8, 1.2)}{p("collab", 0.8, 8.2, 1.1)}

      {/* QA test bench: devices are furniture, they show nothing */}
      <RBox s={[2.2, 0.05, 0.8]} r={0.02} m={M.deskTop()} position={[R.qa.x0 + 5.6, 0.92, R.qa.z0 + 1.1]} />
      <Box s={[2.1, 0.9, 0.06]} m={M.metal()} position={[R.qa.x0 + 5.6, 0.45, R.qa.z0 + 1.1]} />
      {Array.from({ length: 6 }, (_, i) => <RBox key={i} s={[0.09 + (i % 2) * 0.1, 0.17, 0.012]} r={0.01} m={M.bezel()} position={[R.qa.x0 + 4.8 + i * 0.32, 1.03, R.qa.z0 + 1.0]} />)}
      {p("qa", 8.3, 7.3, 0.9)}

      <DarkScreen x={R.security.x0 + 4} z={R.security.z0 + 7.7} ry={PI} w={2.4} h={1.2} y={1.4} />

      {[0, 1, 2, 3].map((i) => <Rack key={i} x={R.devops.x0 + 0.7 + i * 0.75} z={R.devops.z0 + 0.8} />)}
      <DarkScreen x={R.devops.x0 + 6.6} z={R.devops.z0 + 0.3} ry={0} w={2.2} h={1.24} y={1.5} />
      <DarkScreen x={R.devops.x0 + 8.9} z={R.devops.z0 + 0.3} ry={0} w={1.6} h={0.9} y={1.5} />
      {p("devops", 9.3, 7.4)}

      {/* Lounge: nobody rests here, agents take no breaks */}
      <RBox s={[4.2, 0.92, 0.65]} r={0.02} m={mat("#3A3C42")} position={[R.lounge.x0 + 3, 0.46, R.lounge.z0 + 1]} />
      <RBox s={[4.24, 0.04, 0.7]} r={0.01} m={mat("#EDEAE4")} position={[R.lounge.x0 + 3, 0.94, R.lounge.z0 + 1]} />
      {[-1, 0, 1].map((dx) => (
        <group key={dx}>
          <Cyl a={[0.22, 0.22, 0.04]} m={mat("#3A3C42")} position={[R.lounge.x0 + 3 + dx, 0.7, R.lounge.z0 + 2]} />
          <Cyl a={[0.025, 0.025, 0.68, 8]} m={M.metal()} position={[R.lounge.x0 + 3 + dx, 0.34, R.lounge.z0 + 2]} />
        </group>
      ))}
      <Rug x={R.lounge.x0 + 8.6} z={R.lounge.z0 + 4.6} w={5} d={3.6} color="#D8CFC0" />
      <Sofa x={R.lounge.x0 + 8.6} z={R.lounge.z0 + 3.1} ry={0} w={2.6} color="#B89F80" />
      <Sofa x={R.lounge.x0 + 8.6} z={R.lounge.z0 + 6.1} ry={PI} w={2.6} color="#B89F80" />
      <Cyl a={[0.55, 0.55, 0.38, 28]} m={mat("#3A3C42")} position={[R.lounge.x0 + 8.6, 0.19, R.lounge.z0 + 4.6]} />
      {p("lounge", 12.2, 0.8, 1.3)}{p("lounge", 12.2, 7.2, 1.2)}{p("lounge", 0.8, 7.2)}
      <RBox s={[2.4, 1.05, 0.7]} r={0.04} m={mat("#EDE7DD")} position={[R.lounge.x0 + 3.5, 0.52, R.lounge.z0 + 6.4]} />
    </group>
  );
});

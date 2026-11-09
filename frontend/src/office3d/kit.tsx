import type { ThreeElements } from "@react-three/fiber";
import * as THREE from "three";
import { RoundedBoxGeometry } from "three/examples/jsm/geometries/RoundedBoxGeometry.js";

/** Shared materials and geometries: one instance per colour or size, reused across the whole floor. */

const mats = new Map<string, THREE.Material>();
export function mat(color: string | number, o: THREE.MeshStandardMaterialParameters = {}): THREE.MeshStandardMaterial {
  const k = `${color}:${JSON.stringify(o)}`;
  let m = mats.get(k);
  if (!m) {
    m = new THREE.MeshStandardMaterial({ color, roughness: 0.82, metalness: 0, ...o });
    mats.set(k, m);
  }
  return m as THREE.MeshStandardMaterial;
}

export function basic(color: string | number, o: THREE.MeshBasicMaterialParameters = {}): THREE.MeshBasicMaterial {
  const k = `basic:${color}:${JSON.stringify(o)}`;
  let m = mats.get(k);
  if (!m) {
    m = new THREE.MeshBasicMaterial({ color, toneMapped: false, ...o });
    mats.set(k, m);
  }
  return m as THREE.MeshBasicMaterial;
}

const geos = new Map<string, THREE.BufferGeometry>();
function geo<T extends THREE.BufferGeometry>(key: string, make: () => T): T {
  let g = geos.get(key);
  if (!g) {
    g = make();
    geos.set(key, g);
  }
  return g as T;
}

const r3 = (n: number) => Math.round(n * 1000) / 1000;

export const boxGeo = (w: number, h: number, d: number) => geo(`b:${r3(w)}:${r3(h)}:${r3(d)}`, () => new THREE.BoxGeometry(w, h, d));
export const rboxGeo = (w: number, h: number, d: number, r = 0.02) => {
  const rr = Math.min(r, w / 2.2, h / 2.2, d / 2.2);
  return geo(`rb:${r3(w)}:${r3(h)}:${r3(d)}:${r3(rr)}`, () => new RoundedBoxGeometry(w, h, d, 2, rr));
};
export const cylGeo = (rt: number, rb: number, h: number, seg = 20) => geo(`c:${r3(rt)}:${r3(rb)}:${r3(h)}:${seg}`, () => new THREE.CylinderGeometry(rt, rb, h, seg));
export const sphereGeo = (r: number, w = 16, h = 12) => geo(`s:${r3(r)}:${w}:${h}`, () => new THREE.SphereGeometry(r, w, h));
export const capsuleGeo = (r: number, l: number) => geo(`cap:${r3(r)}:${r3(l)}`, () => new THREE.CapsuleGeometry(r, Math.max(l, 0.01), 6, 12));
export const planeGeo = (w: number, h: number) => geo(`p:${r3(w)}:${r3(h)}`, () => new THREE.PlaneGeometry(w, h));
export const floorGeo = (w: number, h: number) => geo(`f:${r3(w)}:${r3(h)}`, () => new THREE.PlaneGeometry(w, h).rotateX(-Math.PI / 2));

type MeshProps = Omit<ThreeElements["mesh"], "args"> & { m: THREE.Material; shadow?: boolean };

export function Box({ s, m, shadow = true, ...p }: MeshProps & { s: [number, number, number] }) {
  return <mesh geometry={boxGeo(...s)} material={m} castShadow={shadow} receiveShadow {...p} />;
}
export function RBox({ s, r = 0.02, m, shadow = true, ...p }: MeshProps & { s: [number, number, number]; r?: number }) {
  return <mesh geometry={rboxGeo(s[0], s[1], s[2], r)} material={m} castShadow={shadow} receiveShadow {...p} />;
}
export function Cyl({ a, m, shadow = true, ...p }: MeshProps & { a: [number, number, number, number?] }) {
  return <mesh geometry={cylGeo(a[0], a[1], a[2], a[3])} material={m} castShadow={shadow} receiveShadow {...p} />;
}

export function mix(a: string, b: string, t: number) {
  return "#" + new THREE.Color(a).lerp(new THREE.Color(b), t).getHexString();
}

export const M = {
  deskTop: () => mat("#F4F1EC"),
  metal: () => mat("#34363C", { metalness: 0.4, roughness: 0.5 }),
  chair: () => mat("#2E3036"),
  chairSeat: () => mat("#3A3D45"),
  dark: () => mat("#2B2D33"),
  bezel: () => mat("#1E1F23"),
  frame: () => mat("#B9BCC2", { metalness: 0.35, roughness: 0.45 }),
  wall: () => mat("#F1EEE9"),
  wallTop: () => mat("#2A2B30"),
  pot: () => mat("#E9E5DE"),
  leaf: () => mat("#5D7A55", { roughness: 0.9 }),
};

let glass: THREE.MeshStandardMaterial | null = null;
export function glassMat() {
  glass ??= new THREE.MeshStandardMaterial({ color: 0xcfe0e6, transparent: true, opacity: 0.22, roughness: 0.1, metalness: 0.1, depthWrite: false });
  return glass;
}

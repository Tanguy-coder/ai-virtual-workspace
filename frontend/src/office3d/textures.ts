import * as THREE from "three";

/**
 * Canvas textures. Screen content only exists for a recorded state: a running model call ("generating"),
 * a recorded blocking reason, or an open meeting's real title and turn. Everything else is a sleeping screen.
 */

const cache = new Map<string, THREE.CanvasTexture>();

function tex(key: string, w: number, h: number, draw: (g: CanvasRenderingContext2D, w: number, h: number) => void) {
  const hit = cache.get(key);
  if (hit) return hit;
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  draw(c.getContext("2d")!, w, h);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 4;
  cache.set(key, t);
  return t;
}

function seeded(seed: number) {
  let s = seed || 7;
  return () => (s = (s * 16807) % 2147483647) / 2147483647;
}

const SANS = '"IBM Plex Sans", system-ui, sans-serif';
const MONO = '"IBM Plex Mono", ui-monospace, monospace';

export function sleepTexture() {
  return tex("sleep", 64, 40, (g, w, h) => {
    g.fillStyle = "#24262C";
    g.fillRect(0, 0, w, h);
  });
}

/** A response being generated: text lines and a cursor, no invented code or data. */
export function generatingTexture() {
  return tex("generating", 512, 320, (g, w, h) => {
    const rnd = seeded(11);
    g.fillStyle = "#15171C";
    g.fillRect(0, 0, w, h);
    g.fillStyle = "#1E2128";
    g.fillRect(0, 0, w, 26);
    g.fillStyle = "#3462D8";
    g.beginPath();
    g.arc(16, 13, 4, 0, 7);
    g.fill();
    g.fillStyle = "#9A9690";
    g.font = `500 13px ${MONO}`;
    g.fillText("model call running", 28, 17);
    let y = 48;
    for (let p = 0; p < 3; p++) {
      const lines = 3 + Math.floor(rnd() * 2);
      for (let i = 0; i < lines; i++) {
        const len = i === lines - 1 ? 120 + rnd() * 160 : 380 + rnd() * 80;
        g.fillStyle = "#4A4F5A";
        g.fillRect(20, y, len, 8);
        y += 18;
      }
      y += 12;
    }
    g.fillStyle = "#E8552B";
    g.fillRect(20, y - 4, 4, 14);
  });
}

function wrap(g: CanvasRenderingContext2D, text: string, maxWidth: number, maxLines: number): string[] {
  const words = text.split(/\s+/);
  const lines: string[] = [];
  let line = "";
  for (const w of words) {
    const next = line ? `${line} ${w}` : w;
    if (g.measureText(next).width > maxWidth && line) {
      lines.push(line);
      line = w;
      if (lines.length === maxLines) break;
    } else line = next;
  }
  if (lines.length < maxLines && line) lines.push(line);
  if (lines.length === maxLines && words.join(" ").length > lines.join(" ").length) lines[maxLines - 1] = lines[maxLines - 1].replace(/.{0,2}$/, "…");
  return lines;
}

/** The recorded blocking reason, on the agent's screen. */
export function blockedTexture(reason: string | null) {
  return tex(`blocked:${reason ?? ""}`, 512, 320, (g, w, h) => {
    g.fillStyle = "#1A1B1F";
    g.fillRect(0, 0, w, h);
    g.fillStyle = "#C5303B";
    g.fillRect(0, 0, w, 6);
    g.save();
    g.translate(34, 52);
    g.rotate(Math.PI / 4);
    g.fillRect(-9, -9, 18, 18);
    g.restore();
    g.fillStyle = "#ECEBE8";
    g.font = `600 26px ${SANS}`;
    g.fillText("Blocked", 62, 62);
    g.fillStyle = "#B9B5AE";
    g.font = `400 22px ${SANS}`;
    wrap(g, reason ?? "No reason recorded", w - 60, 6).forEach((l, i) => g.fillText(l, 30, 118 + i * 32));
  });
}

/** Wall screen of a meeting room: the real meeting title and turn. */
export function meetingTexture(title: string, line: string) {
  return tex(`meeting:${title}:${line}`, 768, 432, (g, w, h) => {
    g.fillStyle = "#FBFAF7";
    g.fillRect(0, 0, w, h);
    g.fillStyle = "#B7832A";
    g.fillRect(0, 0, w, 8);
    g.fillStyle = "#1C1D21";
    g.font = `600 40px ${SANS}`;
    wrap(g, title, w - 96, 2).forEach((l, i) => g.fillText(l, 48, 92 + i * 50));
    g.fillStyle = "#5F5B55";
    g.font = `400 26px ${SANS}`;
    g.fillText(line, 48, 240);
    g.fillStyle = "#E8552B";
    g.fillRect(48, 270, 6, 34);
    g.fillStyle = "#8F8B84";
    g.font = `400 20px ${MONO}`;
    g.fillText("live · from recorded meeting events", 66, 296);
  });
}

/** A blank whiteboard with a faint grid: furniture, not data. */
export function boardTexture() {
  return tex("board", 256, 128, (g, w, h) => {
    g.fillStyle = "#FBFAF7";
    g.fillRect(0, 0, w, h);
    g.strokeStyle = "rgba(0,0,0,0.05)";
    for (let x = 0; x <= w; x += 16) {
      g.beginPath();
      g.moveTo(x, 0);
      g.lineTo(x, h);
      g.stroke();
    }
    for (let y = 0; y <= h; y += 16) {
      g.beginPath();
      g.moveTo(0, y);
      g.lineTo(w, y);
      g.stroke();
    }
  });
}

export function woodTexture(repeatX: number, repeatY: number) {
  const base = tex("wood", 512, 512, (g) => {
    const rnd = seeded(3);
    g.fillStyle = "#CDB89A";
    g.fillRect(0, 0, 512, 512);
    for (let i = 0; i < 16; i++) {
      g.fillStyle = `rgba(${150 + rnd() * 30},${120 + rnd() * 20},${90 + rnd() * 20},0.25)`;
      g.fillRect(0, i * 32, 512, 32);
      g.fillStyle = "rgba(90,70,50,0.18)";
      g.fillRect(0, i * 32, 512, 1.5);
      g.fillRect((i * 197) % 512, i * 32, 1.5, 32);
    }
  });
  const t = base.clone();
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.repeat.set(repeatX, repeatY);
  t.needsUpdate = true;
  return t;
}

export function carpetTexture(color: string) {
  return tex(`carpet:${color}`, 256, 256, (g) => {
    const rnd = seeded(5);
    g.fillStyle = color;
    g.fillRect(0, 0, 256, 256);
    for (let i = 0; i < 2600; i++) {
      g.fillStyle = `rgba(0,0,0,${rnd() * 0.05})`;
      g.fillRect(rnd() * 256, rnd() * 256, 2, 2);
    }
    g.strokeStyle = "rgba(0,0,0,0.05)";
    for (let i = 0; i <= 256; i += 64) {
      g.beginPath();
      g.moveTo(i, 0);
      g.lineTo(i, 256);
      g.moveTo(0, i);
      g.lineTo(256, i);
      g.stroke();
    }
  });
}

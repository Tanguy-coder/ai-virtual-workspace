import { create } from "zustand";
import { D, ROOMS, W, type RoomId } from "./plan";
import type { OfficeView } from "./represent";

/** Camera target on the floor (plan coordinates), written by the 3D scene, read by the plan's viewport frame. */
export const useOfficeCamera = create<{ x: number; z: number; span: number }>(() => ({ x: W / 2, z: D / 2, span: 0 }));

const TONE_FILL: Record<string, string> = { active: "var(--active)", warn: "var(--warn)", danger: "var(--danger)", ok: "var(--ok)", muted: "var(--faint)" };

/**
 * The same floor in 2D: the minimap of the 3D view, and the whole office on small screens.
 * Dots are the figures of the representation, at their real spot.
 */
export function FloorPlan({ view, focus, selectedId, onRoom, onAgent, showViewport, names, className }: {
  view: OfficeView;
  focus: RoomId | null;
  selectedId: string | null;
  onRoom(id: RoomId): void;
  onAgent?(id: string): void;
  showViewport?: boolean;
  names?: Map<string, string>;
  className?: string;
}) {
  const cam = useOfficeCamera();
  const r = onAgent ? 0.75 : 0.55;
  return (
    <svg viewBox={`-0.5 -0.5 ${W + 1} ${D + 1}`} className={className} role="img" aria-label="Floor plan">
      <rect x={-0.5} y={-0.5} width={W + 1} height={D + 1} fill="var(--panel-2)" stroke="var(--line-strong)" strokeWidth={0.3} />
      {ROOMS.map((room) => (
        <g key={room.id} onClick={() => onRoom(room.id)} className="cursor-pointer">
          <rect x={room.x0 + 0.2} y={room.z0 + 0.2} width={room.x1 - room.x0 - 0.4} height={room.z1 - room.z0 - 0.4} rx={0.4}
            fill={room.kind === "common" ? "var(--line)" : room.color} fillOpacity={room.kind === "common" ? 1 : 0.22}
            stroke={focus === room.id ? "var(--fg)" : "var(--panel)"} strokeWidth={focus === room.id ? 0.35 : 0.2}>
            <title>{room.name}</title>
          </rect>
          {onAgent && (
            <text x={room.x0 + 0.7} y={room.z0 + 1.6} fontSize={0.95} fill="var(--muted)" fontWeight={600} style={{ letterSpacing: "0.05em" }}>
              {room.name.toUpperCase()}
            </text>
          )}
        </g>
      ))}
      {view.figures.map((f) => (
        <g key={f.agentId} onClick={onAgent ? () => onAgent(f.agentId) : undefined} className={onAgent ? "cursor-pointer" : undefined}>
          {f.lantern === "blocked" ? (
            <rect x={f.spot.x - r * 0.8} y={f.spot.z - r * 0.8} width={r * 1.6} height={r * 1.6} transform={`rotate(45 ${f.spot.x} ${f.spot.z})`} fill={TONE_FILL[f.tone]} />
          ) : (
            <circle cx={f.spot.x} cy={f.spot.z} r={r} fill={TONE_FILL[f.tone]} fillOpacity={f.faded ? 0.4 : 1} />
          )}
          {selectedId === f.agentId && <circle cx={f.spot.x} cy={f.spot.z} r={r + 0.45} fill="none" stroke="var(--accent)" strokeWidth={0.3} />}
          {names && <title>{names.get(f.agentId)}</title>}
        </g>
      ))}
      {showViewport && cam.span > 0 && (
        <rect x={cam.x - cam.span} y={cam.z - cam.span * 0.75} width={cam.span * 2} height={cam.span * 1.5} fill="none" stroke="var(--accent)" strokeWidth={0.45} pointerEvents="none" />
      )}
    </svg>
  );
}

import { memo } from "react";
import { Box, Cyl, M, planeGeo, RBox } from "./kit";
import { Chair } from "./Building";
import type { DeskView } from "./represent";
import { blockedTexture, generatingTexture, sleepTexture, workingTexture } from "./textures";

/** Number of monitors per department: a matter of furniture, not of activity. */
const MONITORS: Record<string, number> = { backend: 2, frontend: 2, devops: 2, cto: 2, security: 2 };

/**
 * One agent's desk in plan coordinates: chair at the seat, desk ahead in the facing direction.
 * Screens show content only for the recorded state (DeskView.screen); otherwise they sleep.
 */
export const Workstation = memo(function Workstation({ view }: { view: DeskView }) {
  const { seat, screen, occupied, blockedReason } = view;
  const dw = seat.wide ? 1.8 : 1.5;
  const n = seat.wide ? 3 : MONITORS[seat.room] ?? 1;
  const sw = 0.56;
  const map = screen === "generating" ? generatingTexture() : screen === "working" ? workingTexture() : screen === "blocked" ? blockedTexture(blockedReason) : sleepTexture();
  return (
    <group position={[seat.x, 0, seat.z]} rotation-y={seat.ry}>
      <RBox s={[dw, 0.04, 0.78]} r={0.015} m={M.deskTop()} position={[0, 0.74, 0.62]} />
      {[-dw / 2 + 0.06, dw / 2 - 0.06].map((sx) => <Box key={sx} s={[0.05, 0.72, 0.62]} m={M.metal()} position={[sx, 0.36, 0.62]} />)}
      <Box s={[dw - 0.1, 0.3, 0.02]} m={M.pot()} position={[0, 0.5, 0.98]} />
      <RBox s={[0.44, 0.02, 0.15]} r={0.008} m={M.dark()} position={[0, 0.77, 0.4]} />
      <RBox s={[0.07, 0.025, 0.11]} r={0.01} m={M.dark()} position={[0.32, 0.77, 0.42]} />
      {Array.from({ length: n }, (_, i) => {
        const off = (i - (n - 1) / 2) * (sw + 0.04);
        const ang = n > 1 ? -off * 0.35 : 0;
        // Only the main monitor carries the content; side monitors stay asleep.
        const main = i === Math.floor((n - 1) / 2);
        return (
          <group key={i} position={[off, 0, 0.86]} rotation-y={Math.PI + ang}>
            <Cyl a={[0.015, 0.015, 0.22, 8]} m={M.metal()} position={[0, 0.87, 0]} />
            <RBox s={[sw + 0.03, 0.36, 0.025]} r={0.01} m={M.bezel()} position={[0, 1.08, 0]} />
            <mesh geometry={planeGeo(sw, 0.33)} position={[0, 1.08, 0.014]}>
              <meshBasicMaterial map={main ? map : sleepTexture()} toneMapped={false} />
            </mesh>
          </group>
        );
      })}
      <Chair x={0} z={0} pushed={!occupied} />
    </group>
  );
});

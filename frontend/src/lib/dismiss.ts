import { useEffect, type RefObject } from "react";

/**
 * Closes a side drawer on Escape or on a press outside it. A press inside another drawer or a dialog (command
 * palette) does not count as outside, so drawers opened from one another stay usable.
 */
export function useDismiss(ref: RefObject<HTMLElement | null>, close: () => void, active: boolean) {
  useEffect(() => {
    if (!active) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    const onDown = (e: PointerEvent) => {
      const target = e.target as Element | null;
      if (!target || !target.isConnected || ref.current?.contains(target)) return;
      if (target.closest("[data-drawer], [role='dialog']")) return;
      close();
    };
    window.addEventListener("keydown", onKey);
    document.addEventListener("pointerdown", onDown, true);
    return () => {
      window.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onDown, true);
    };
  }, [ref, close, active]);
}

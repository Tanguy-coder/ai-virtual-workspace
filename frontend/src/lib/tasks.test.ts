import { describe, expect, it } from "vitest";
import type { Task } from "../api/types";
import { dropAction, schedule } from "./tasks";

const task = (id: string, number: number, estimateDays: number | null, dependsOn: string[] = []): Task => ({
  id, projectId: "p", number, title: id, description: "", acceptanceCriteria: [], status: "CREATED", priority: "MEDIUM",
  estimateDays, assigneeId: null, blockedReason: null, sourceKind: "PLAN", sourceId: null, createdByKind: "AGENT",
  createdById: null, dependsOn, createdAt: "", updatedAt: "",
});

describe("schedule", () => {
  it("starts a task when its longest dependency ends", () => {
    const plan = schedule([task("a", 1, 2), task("b", 2, 3), task("c", 3, 1, ["a", "b"])]);
    const c = plan.find((s) => s.task.id === "c")!;
    expect(c.start).toBe(3);
    expect(c.level).toBe(1);
  });

  it("defaults missing estimates to one day and ignores cancelled tasks", () => {
    const cancelled = { ...task("x", 4, 5), status: "CANCELLED" as const };
    const plan = schedule([task("a", 1, null), cancelled, task("b", 2, 2, ["a", "x"])]);
    expect(plan.map((s) => s.task.id)).toEqual(["a", "b"]);
    expect(plan[1].start).toBe(1);
  });
});

describe("Kanban drop rules", () => {
  const t = (status: string, assigneeId: string | null = null) => ({ status, assigneeId, dependsOn: [] }) as never;

  it("asks who when a ready task is dropped on Assigned, and unassigns on the way back", () => {
    expect(dropAction(t("READY"), "ASSIGNED", 0)).toEqual({ kind: "assign", unblockFirst: false });
    expect(dropAction(t("ASSIGNED", "a1"), "READY", 0)).toEqual({ kind: "unassign" });
  });

  it("blocks, unblocks and cancels", () => {
    expect(dropAction(t("ASSIGNED", "a1"), "BLOCKED", 0)).toEqual({ kind: "block" });
    expect(dropAction(t("BLOCKED", "a1"), "ASSIGNED", 0)).toEqual({ kind: "unblock" });
    expect(dropAction(t("BLOCKED"), "ASSIGNED", 0)).toEqual({ kind: "assign", unblockFirst: true });
    expect(dropAction(t("CREATED"), "CANCELLED", 1)).toEqual({ kind: "cancel" });
  });

  it("refuses what only the engine or the dependencies decide", () => {
    expect(dropAction(t("ASSIGNED", "a1"), "DONE", 0).kind).toBe("refuse");
    expect(dropAction(t("ASSIGNED", "a1"), "IN_PROGRESS", 0).kind).toBe("refuse");
    expect(dropAction(t("CREATED"), "READY", 2).kind).toBe("refuse");
    expect(dropAction(t("CREATED"), "ASSIGNED", 2).kind).toBe("refuse");
    expect(dropAction(t("READY"), "CREATED", 0).kind).toBe("refuse");
    expect(dropAction(t("READY"), "READY", 0)).toEqual({ kind: "none" });
  });
});

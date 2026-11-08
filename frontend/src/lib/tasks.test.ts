import { describe, expect, it } from "vitest";
import type { Task } from "../api/types";
import { schedule } from "./tasks";

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

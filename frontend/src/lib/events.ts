import type { EventRecord } from "../api/types";

/** One-line human description of an event, built only from its recorded fields. */
export function describe(e: EventRecord, names: Record<string, string> = {}): string {
  const p = e.payload as Record<string, string | number | boolean | null>;
  const agent = names[e.subject.id] ?? (p.name as string) ?? "agent";
  const who = (id: unknown) => (id && names[String(id)]) || "an agent";
  const actor = e.actor.kind === "AGENT" ? who(e.actor.id) : e.actor.kind === "HUMAN" ? "The CTO" : "The platform";
  const job: Record<string, string> = {
    LEAD_ANALYZE: "specification analysis", LEAD_ARCHITECT: "architecture and team proposal",
    LEAD_PLAN: "project plan", MEETING_TURN: "meeting turn", MEETING_SYNTHESIS: "meeting minutes",
    TASK_STEP: "work step", TASK_REVIEW: "code review", MR_MERGE: "merge",
    LEAD_SPLIT: "task split", LEAD_REALIGN: "realignment meeting agenda",
  };
  switch (e.type) {
    case "task_run.started": return `${who(p.agentId)} started T-${p.number} on ${p.branch}`;
    case "task_run.step": return `${who(p.agentId)} · ${p.summary}`;
    case "task_run.status_changed": return `Work on a task is ${String(p.status).toLowerCase()}${p.reason ? `: ${p.reason}` : ""}`;
    case "test_run.completed": return `Tests ${String(p.status).toLowerCase()} on ${p.branch} (tr-${p.number}${p.passed != null ? ` · ${p.passed} passed` : ""}${p.failed ? `, ${p.failed} failed` : ""})`;
    case "merge_request.opened": return `${actor} opened merge request !${p.number}: ${p.title}`;
    case "merge_request.reviewed": return `${actor} ${p.decision === "APPROVE" ? "approved" : "requested changes on"} !${p.number}`;
    case "merge_request.merged": return `!${p.number} merged into ${p.into ?? "main"} (${String(p.commit).slice(0, 7)})`;
    case "branch.created": return `${actor} created the ${p.branch} branch from ${p.from}`;
    case "branch.merged": return `develop merged into main with the CTO's approval (${String(p.commit).slice(0, 7)})`;
    case "branch.merge_failed": return `The merge of develop into main failed: ${p.reason}`;
    case "agent.step_completed": return `${agent} · ${p.summary} (${String(p.outcome).toLowerCase()}, ${Math.round(Number(p.durationMs) / 100) / 10}s)`;
    case "job.queued": return `Queued ${job[String(p.type)] ?? p.type} for ${who(p.agentId)}`;
    case "job.completed": return `${who(p.agentId)} finished the ${job[String(p.type)] ?? p.type}`;
    case "job.failed": return `${who(p.agentId)} is blocked: ${job[String(p.type)] ?? p.type} failed — ${p.error}`;
    case "job.retried": return `The CTO retried the ${job[String(p.type)] ?? p.type}`;
    case "briefing.started": return "The CTO briefed the Lead";
    case "briefing.analyzed": return `The Lead analyzed the specification · ${p.questions} question(s), ${p.risks} risk(s)`;
    case "briefing.answered": return "The CTO answered the Lead's questions";
    case "briefing.approved": return "The CTO approved the architecture";
    case "briefing.failed": return `The briefing failed: ${p.error}`;
    case "hiring_proposal.created": return `The Lead proposed a team · ${p.agents} agent(s), version ${p.version}`;
    case "hiring_proposal.revision_requested": return `The CTO asked the Lead to revise proposal v${p.version}`;
    case "hiring_proposal.accepted": return `The CTO accepted the team · ${p.hired} agent(s) hired`;
    case "hiring_proposal.rejected": return `The CTO rejected proposal v${p.version}`;
    case "meeting.started": return `Meeting started: ${p.title}`;
    case "meeting.turn": return p.next === "minutes" ? "Last turn spoken; the organizer is writing the minutes" : `Next to speak: ${who(p.nextAgentId)}`;
    case "meeting.minutes_published": return `Minutes published for ${p.title} · ${p.decisions} decision(s), ${p.actionItems} action item(s)`;
    case "meeting.ended": return `Meeting ${String(p.outcome).toLowerCase()}: ${p.title}`;
    case "meeting.failed": return `Meeting failed: ${p.title} — ${p.error}`;
    case "meeting.resumed": return `Meeting resumed: ${p.title}`;
    case "memory.recorded": return `${actor} recorded ${p.shared ? "in shared memory" : "a personal note"}: ${p.title}${Number(p.version) > 1 ? ` (v${p.version})` : ""}`;
    case "plan.created": return `The Lead planned the project · ${p.tasks} task(s)`;
    case "task.split_requested": return `${actor} asked the Lead to split T-${p.number}`;
    case "task.split": return `T-${p.number} split into ${Array.isArray(e.payload.into) ? (e.payload.into as number[]).map((n) => `T-${n}`).join(", ") : "smaller tasks"}`;
    case "task.split_failed": return `Splitting T-${p.number} failed — ${p.error}`;
    case "task_run.budget_changed": return `${actor} changed a task's token budget: ${p.from} → ${p.to}`;
    case "realignment.requested": return `${actor} asked the Lead to organize a realignment meeting`;
    case "realignment.failed": return `The Lead could not organize the realignment meeting — ${p.error}`;
    case "realignment.applied": return `Realignment ${String(p.outcome).toLowerCase()}: ${p.solutionsDelivered} solution(s) delivered, ${p.resumed} run(s) resumed`;
    case "task.created": return `T-${p.number} created: ${p.title}`;
    case "task.assigned": return `T-${p.number} assigned to ${who(p.assigneeId)}`;
    case "task.unassigned": return `T-${p.number} unassigned`;
    case "task.status_changed": return `T-${p.number}: ${String(p.from).toLowerCase()} → ${String(p.to).toLowerCase()}`;
    case "task.blocked": return `T-${p.number} blocked: ${p.reason}`;
    case "task.updated": return `T-${p.number} edited`;
    case "task.dependency_added": return `A task now depends on T-${p.dependsOnNumber}`;
    case "task.dependency_removed": return "A task dependency was removed";
    case "workspace.created": return `Workspace "${p.name}" created`;
    case "project.created": return `Project "${p.name}" created`;
    case "document.added": return `Document "${p.title}" added`;
    case "agent.hired": return `${p.name} joined as ${p.title}`;
    case "agent.model_bound": return `${agent} now uses ${p.model}`;
    case "agent.department_changed": return `${agent} moved from ${String(p.from).toLowerCase()} to ${String(p.to).toLowerCase()}`;
    case "role_template.created": return `New role "${p.title}" added to the catalog`;
    case "agent.instructions_updated": return `${agent}'s instructions were edited`;
    case "agent.paused": return `${agent} was paused`;
    case "agent.resumed": return `${agent} was resumed`;
    case "agent.dismissed": return `${agent} was dismissed`;
    case "agent.state_changed": return `${agent}: ${String(p.from).toLowerCase()} → ${String(p.to).toLowerCase()}`;
    case "message.sent":
      if (p.threadId) {
        const kind = String(p.type).toLowerCase().replace("_", " ");
        return p.toAgentId ? `${actor} → ${who(p.toAgentId)} (${kind}): ${p.preview}` : `${actor} (${kind}): ${p.preview}`;
      }
      return p.from ? `${p.from} replied` : `Message sent to ${p.to}`;
    case "message.failed": return `Message to ${agent} failed: ${p.error}`;
    case "model.call_completed":
      if (p.outcome === "REUSED") return `${p.model} answer reused from an identical earlier call · no tokens spent`;
      return `${p.model} call ${String(p.outcome).toLowerCase()} · ${p.inputTokens} in / ${p.outputTokens} out` +
        (Number(p.cacheReadTokens) > 0 ? ` · ${p.cacheReadTokens} from cache` : "") +
        (p.costUsd ? ` · $${Number(p.costUsd).toFixed(4)}` : " · unpriced") + ` · ${p.latencyMs} ms`;
    case "budget.threshold_reached": return `Budget ${p.limit} limit at ${p.threshold} ($${p.spentUsd} of $${p.limitUsd})`;
    case "provider.created": return `Provider "${p.name}" added`;
    case "provider.deleted": return `Provider "${p.name}" removed`;
    case "provider.updated": return p.economyModel ? `Economy model set to ${p.economyModel}` : "Economy model cleared";
    case "approval.requested": return `Approval requested: ${p.summary}`;
    case "approval.granted": return `Approved: ${p.action}`;
    case "approval.denied": return `Rejected: ${p.action}`;
    default: return e.type;
  }
}

export function tone(e: EventRecord): "danger" | "warn" | "ok" | "muted" {
  if (e.type === "test_run.completed") return e.payload.status === "PASSED" ? "ok" : "danger";
  if (e.type === "merge_request.merged" || e.type === "branch.merged") return "ok";
  if (e.type === "branch.merge_failed") return "danger";
  if (e.type === "message.failed" || e.payload.outcome === "ERROR" || e.type.endsWith(".failed") || e.type === "task.blocked") return "danger";
  if (e.type === "hiring_proposal.created" || e.type === "briefing.analyzed") return "warn";
  if (e.type === "plan.created" || e.type === "meeting.minutes_published" || e.type === "hiring_proposal.accepted") return "ok";
  if (e.type.startsWith("budget.") || e.type === "approval.requested") return "warn";
  if (e.type === "agent.hired" || e.type === "approval.granted") return "ok";
  return "muted";
}

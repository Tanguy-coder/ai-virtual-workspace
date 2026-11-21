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
    case "branch.deleted": return p.mergedInto
      ? `Branch ${p.branch} deleted after its merge into ${p.mergedInto} (!${p.mr})`
      : `${actor} deleted the branch ${p.branch}${p.merged ? "" : " (not merged: its commits are gone)"}`;
    case "agent.step_completed": return `${agent} · ${p.summary} (${String(p.outcome).toLowerCase()}, ${Math.round(Number(p.durationMs) / 100) / 10}s)`;
    case "job.queued": return `Queued ${job[String(p.type)] ?? p.type} for ${who(p.agentId)}`;
    case "job.completed": return `${who(p.agentId)} finished the ${job[String(p.type)] ?? p.type}`;
    case "job.failed": return `${who(p.agentId)} is blocked: ${job[String(p.type)] ?? p.type} failed — ${p.error}`;
    case "job.retried": return `The CTO retried the ${job[String(p.type)] ?? p.type}`;
    case "task.lead_decided": return p.decision === "fix" ? `The Lead fixed the code of T-${p.number} itself and resumed the work: ${p.message}`
      : p.decision === "answer" ? `The Lead answered the block on T-${p.number} and resumed the work: ${p.message}`
      : p.decision === "rewrite" ? `The Lead rewrote T-${p.number} and resumed the work: ${p.message}`
      : p.decision === "restore_tests" ? `T-${p.number} had rewritten a test from develop that failed: the platform restored it and resumed the work`
      : p.decision === "restore_regressed" ? `T-${p.number} broke tests that pass on develop: the platform restored develop's version of the files and the agent redoes its change on top`
      : p.decision === "restore_parsing" ? `T-${p.number}: files that did not parse were restored to their last valid version and the work resumed`
      : p.decision === "split" ? `The Lead is splitting T-${p.number}: ${p.why}`
      : p.decision === "takeover" ? `The Lead takes over T-${p.number} and works it itself: ${p.why}`
      : p.decision === "handback" ? `The Lead repaired T-${p.number} and handed it back: ${p.why}`
      : p.decision === "takeover_submitted" ? `The Lead finished T-${p.number} itself and submitted it: ${p.why}`
      : p.decision === "takeover_failed" ? `The Lead could not finish T-${p.number} itself: ${p.why}`
      : `The Lead asks the CTO about T-${p.number}: ${p.question}`;
    case "task.lead_decision_requested": return `The CTO asked the Lead to decide on T-${p.number}`;
    case "task.lead_decision_failed": return `The Lead could not decide on T-${p.number}: ${p.error}`;
    case "job.deferred": return `${who(p.agentId)} waits for the provider: ${p.reason}`;
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
    case "agent.model_bound": return p.source === "LEAD" ? `The Lead gave ${agent} ${p.model} (${p.reason})`
      : p.source === "SYSTEM" ? `${agent} now uses ${p.model} (${p.reason})` : `${agent} now uses ${p.model}`;
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
    case "model.rerouted": return `${p.fromModel} unavailable (${p.reason}): ${p.agentId ? who(p.agentId) + "'s call" : "the call"} moved to ${p.toModel}${p.toProvider !== p.fromProvider ? ` on ${p.toProvider}` : ""}`;
    case "budget.threshold_reached": return `Budget ${p.limit} limit at ${p.threshold} ($${p.spentUsd} of $${p.limitUsd})`;
    case "provider.created": return `Provider "${p.name}" added`;
    case "provider.deleted": return `Provider "${p.name}" removed`;
    case "provider.updated": return p.economyModel ? `Economy model set to ${p.economyModel}` : "Economy model cleared";
    case "security_scan.started": return `Security scan started: ${p.tool} (${String(p.kind).toLowerCase()})`;
    case "security_scan.completed": return p.status === "DONE"
      ? `${String(p.kind).toLowerCase()} scan done: ${p.findings} finding${p.findings === 1 ? "" : "s"}${p.opened ? `, ${p.opened} new` : ""}${p.fixed ? `, ${p.fixed} fixed` : ""}`
      : `${String(p.kind).toLowerCase()} scan ${String(p.status).toLowerCase()}`;
    case "finding.opened": return `New ${String(p.severity).toLowerCase()} finding F-${p.number}: ${p.title}`;
    case "finding.reopened": return `Finding back (${String(p.severity).toLowerCase()}): ${p.title}`;
    case "finding.fixed": return `A ${String(p.kind).toLowerCase()} finding is fixed: the scanner no longer sees it`;
    case "finding.fix_task": return `Fix task T-${p.taskNumber} created for F-${p.number}: ${p.title}`;
    case "finding.risk_accepted": return `${actor} accepted the risk of F-${p.number} (${String(p.severity).toLowerCase()}): ${p.title}`;
    case "environment.created": return `Test environment created at ${p.url}`;
    case "environment.stopped": return `${actor} stopped the ${p.name} environment`;
    case "deployment.requested": return p.approved ? `${actor} deployed ${p.ref} (${String(p.commit).slice(0, 7)}) to test` : `${actor} asks to deploy ${p.ref} (${String(p.commit).slice(0, 7)}) to test`;
    case "deployment.approved": return `${actor} approved deployment d-${p.number}`;
    case "deployment.rejected": return `${actor} rejected deployment d-${p.number}`;
    case "deployment.status_changed": return `Deployment d-${p.number}: ${String(p.status).toLowerCase()}`;
    case "deployment.healthy": return `Deployment d-${p.number} is live at ${p.url}`;
    case "deployment.failed": return `Deployment d-${p.number} failed: ${p.reason}`;
    case "deployment.restored": return `d-${p.failed} failed: d-${p.number} put back${p.ok ? "" : " (could not restart)"}`;
    case "release.requested": return `${actor} asks for release v${p.version}`;
    case "release.created": return `Release v${p.version} tagged on main (${String(p.commit).slice(0, 7)})`;
    case "remote.pushed": return `${actor} pushed main, develop and the tags to ${p.url}`;
    case "remote.push_failed": return `The push to ${p.url} failed`;
    case "member.invited": return `${actor} invited someone as ${String(p.role).toLowerCase()}`;
    case "member.joined": return `A new ${String(p.role).toLowerCase()} joined the workspace`;
    case "member.role_changed": return `${actor} changed a member's role from ${String(p.from).toLowerCase()} to ${String(p.to).toLowerCase()}`;
    case "member.removed": return p.self ? "A member left the workspace" : `${actor} removed a member`;
    case "approval.requested": return `Approval requested: ${p.summary}`;
    case "approval.granted": return `Approved: ${p.action}`;
    case "approval.denied": return `Rejected: ${p.action}`;
    default: return e.type;
  }
}

export function tone(e: EventRecord): "danger" | "warn" | "ok" | "muted" {
  if (e.type === "test_run.completed") return e.payload.status === "PASSED" ? "ok" : "danger";
  if (e.type === "merge_request.merged" || e.type === "branch.merged" || e.type === "finding.fixed") return "ok";
  if (e.type === "finding.opened" || e.type === "finding.reopened") return e.payload.severity === "CRITICAL" || e.payload.severity === "HIGH" ? "danger" : "warn";
  if (e.type === "finding.risk_accepted") return "warn";
  if (e.type === "deployment.healthy" || e.type === "release.created" || e.type === "remote.pushed") return "ok";
  if (e.type === "deployment.restored") return "warn";
  if (e.type === "branch.merge_failed") return "danger";
  if (e.type === "message.failed" || e.payload.outcome === "ERROR" || e.type.endsWith(".failed") || e.type === "task.blocked") return "danger";
  if (e.type === "hiring_proposal.created" || e.type === "briefing.analyzed") return "warn";
  if (e.type === "plan.created" || e.type === "meeting.minutes_published" || e.type === "hiring_proposal.accepted") return "ok";
  if (e.type.startsWith("budget.") || e.type === "approval.requested" || e.type === "job.deferred"
    || (e.type === "task.lead_decided" && (e.payload.decision === "escalate" || e.payload.decision === "takeover_failed"))
    || e.type === "model.rerouted") return "warn";
  if (e.type === "agent.hired" || e.type === "approval.granted") return "ok";
  return "muted";
}

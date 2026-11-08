export type Role = "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";

export interface User { id: string; email: string; displayName: string }
export interface Workspace { id: string; name: string; role: Role }
export interface Project {
  id: string; workspaceId: string; name: string; description: string; status: string;
  createdBy: string; createdAt: string; updatedAt: string;
}
export interface ProjectDocument {
  id: string; projectId: string; kind: "SPECIFICATION" | "TECHNICAL"; title: string; mediaType: string;
  sizeBytes: number; createdBy: string; createdAt: string; content: string | null;
}
export type Department =
  | "DIRECTION" | "ARCHITECTURE" | "PRODUCT" | "BACKEND" | "FRONTEND" | "QA" | "SECURITY" | "DEVOPS" | "OTHER";
export interface RoleTemplate {
  id: string; family: string; title: string; department: Department; skills: string[];
  responsibilities: string[]; builtin: boolean;
}
export interface Agent {
  id: string; workspaceId: string; projectId: string; roleTemplateId: string | null; name: string; title: string;
  department: Department; seniority: string; simulatedYears: number; skills: string[]; responsibilities: string[];
  systemInstructions: string; providerId: string | null; model: string | null; lead: boolean;
  status: "ACTIVE" | "PAUSED" | "DISMISSED"; activity: string; blockedReason: string | null; createdAt: string;
}
export interface AgentMessage {
  id: string; agentId: string; authorKind: "HUMAN" | "AGENT"; authorId: string; content: string;
  status: "PENDING" | "ANSWERED" | "FAILED" | "FINAL"; error: string | null; createdAt: string;
}
export type ProviderKind = "ANTHROPIC" | "OPENAI_COMPATIBLE";
export interface Provider {
  id: string; workspaceId: string; kind: ProviderKind; name: string; baseUrl: string | null; hasKey: boolean;
  createdAt: string;
}
export interface Price {
  providerKind: ProviderKind; model: string; inputUsdPerMtok: number; outputUsdPerMtok: number; builtin: boolean;
}
export interface Spend {
  todayUsd: number; totalUsd: number; callsToday: number; callsTotal: number; unpricedCalls: number;
  inputTokens: number; outputTokens: number;
}
export interface BudgetView { dailyLimitUsd: number | null; totalLimitUsd: number | null; spend: Spend }
export interface EventRecord {
  id: string; seq: number; type: string; occurredAt: string; workspaceId: string; projectId: string | null;
  actor: { kind: "HUMAN" | "AGENT" | "SYSTEM"; id: string | null };
  subject: { kind: string; id: string };
  payload: Record<string, unknown>;
}
export interface ProjectDashboard {
  agentsByState: Record<string, number>; agentCount: number; spend: Spend; dailyLimitUsd: number | null;
  totalLimitUsd: number | null; pendingApprovals: number; documents: number;
  tasksByStatus: Partial<Record<TaskStatus, number>>; recentEvents: EventRecord[];
}
export interface Approval {
  id: string; workspaceId: string; projectId: string | null; requestedByKind: string; requestedById: string | null;
  action: string; targetKind: string; targetId: string; summary: string; details: Record<string, unknown>;
  status: "PENDING" | "APPROVED" | "REJECTED"; decidedBy: string | null; decidedAt: string | null;
  reason: string | null; createdAt: string;
}
export interface TestResult {
  ok: boolean; reply: string | null; servedModel: string | null; inputTokens: number; outputTokens: number;
  latencyMs: number; costUsd: string | null; error: string | null;
}

export type TaskStatus =
  | "CREATED" | "READY" | "ASSIGNED" | "IN_PROGRESS" | "BLOCKED" | "IN_REVIEW" | "IN_TEST" | "VALIDATION" | "DONE" | "CANCELLED";
export type Priority = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export interface Task {
  id: string; projectId: string; number: number; title: string; description: string;
  acceptanceCriteria: { text: string; met: boolean }[]; status: TaskStatus; priority: Priority;
  estimateDays: number | null; assigneeId: string | null; blockedReason: string | null;
  sourceKind: "PLAN" | "MEETING" | "MANUAL"; sourceId: string | null; createdByKind: string; createdById: string | null;
  dependsOn: string[]; createdAt: string; updatedAt: string;
}
export interface Question { id: string; text: string; why: string; answer: string | null }
export interface Analysis {
  summary: string; functional: string[]; nonFunctional: string[]; domains: string[];
  risks: { risk: string; impact: string; mitigation: string }[];
}
export type BriefingStatus =
  | "ANALYZING" | "AWAITING_ANSWERS" | "ARCHITECTING" | "PROPOSED" | "APPROVED" | "FAILED" | "CANCELLED";
export interface Briefing {
  id: string; projectId: string; leadId: string; status: BriefingStatus; analysis: Analysis | null;
  questions: Question[]; architecture: string | null; error: string | null; createdAt: string; updatedAt: string;
}
export interface ProposalLine {
  templateId: string; title: string; count: number; department: Department; seniority: string;
  justification: string; quote: string | null; skills: string[]; responsibilities: string[];
}
export interface HiringProposal {
  id: string; projectId: string; briefingId: string; version: number;
  status: "PENDING" | "REVISING" | "ACCEPTED" | "REJECTED" | "SUPERSEDED";
  rationale: string; lines: ProposalLine[]; estimatedDailyUsd: number | null; revisionComment: string | null;
  decidedBy: string | null; decidedAt: string | null; createdAt: string;
}
export interface BriefingDetail { briefing: Briefing; proposals: HiringProposal[]; threadId: string | null }
export interface Message {
  id: string; threadId: string; authorKind: "AGENT" | "HUMAN" | "SYSTEM"; authorId: string | null;
  toAgentId: string | null; type: string; content: string; createdAt: string;
}
export interface ActionItem { title: string; ownerAgentId: string | null; due: string | null; taskId: string | null }
export interface Minutes {
  summary: string; decisions: { title: string; detail: string }[];
  disagreements: { topic: string; positions: string }[]; actionItems: ActionItem[];
}
export interface Meeting {
  id: string; projectId: string; purpose: "KICKOFF" | "ADHOC"; title: string; agenda: string; organizerId: string;
  participantIds: string[];
  convenedByKind: string; status: "IN_PROGRESS" | "SYNTHESIZING" | "ENDED" | "FAILED" | "CANCELLED";
  rounds: number; currentRound: number; turnIndex: number; threadId: string; minutes: Minutes | null;
  error: string | null; startedAt: string; endedAt: string | null;
}
export interface MeetingDetail { meeting: Meeting; participantIds: string[]; messages: Message[] }
export interface MemoryEntry {
  id: string; projectId: string; agentId: string | null; entryKey: string; version: number; kind: string;
  title: string; content: string; sourceKind: string; sourceId: string | null; authorKind: string;
  authorId: string | null; createdAt: string;
}
export interface Job {
  id: string; projectId: string; agentId: string; type: string; payload: Record<string, unknown>;
  status: "QUEUED" | "RUNNING" | "DONE" | "FAILED" | "CANCELLED"; attempts: number; maxAttempts: number;
  lastError: string | null; createdAt: string; startedAt: string | null; finishedAt: string | null;
}
export interface AgentStep {
  id: string; agentId: string; jobId: string | null; kind: string; summary: string; model: string | null;
  inputTokens: number; outputTokens: number; costUsd: number | null; durationMs: number; outcome: string;
  error: string | null; createdAt: string;
}

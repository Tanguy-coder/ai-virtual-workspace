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
  /** Lead replies: outcome of each order it gave, recorded by the platform. */
  actions?: { action: string; ok: boolean; detail: string }[] | null;
}
export type ProviderKind = "ANTHROPIC" | "OPENAI_COMPATIBLE";
export interface Provider {
  id: string; workspaceId: string; kind: ProviderKind; name: string; baseUrl: string | null; hasKey: boolean;
  createdAt: string;
  /** Model for light calls (minutes, summaries); null: each agent's own model. */
  economyModel: string | null;
  /** Model bound to new agents while this provider is the workspace default. */
  defaultModel: string | null;
  isDefault: boolean;
  preset: string | null;
}
export interface ProviderPreset {
  id: string; label: string; kind: ProviderKind; baseUrl: string | null; keyRequired: boolean; local: boolean;
  defaultModel: string | null; economyModel: string | null; keyUrl: string | null;
}
export interface Price {
  providerKind: ProviderKind; model: string; inputUsdPerMtok: number; outputUsdPerMtok: number; builtin: boolean;
}
export interface Spend {
  todayUsd: number; totalUsd: number; callsToday: number; callsTotal: number; unpricedCalls: number;
  inputTokens: number; outputTokens: number;
}
export interface BudgetView {
  dailyLimitUsd: number | null; totalLimitUsd: number | null;
  /** Tokens per task run set by the project (null: the platform default below). */
  taskTokenBudget: number | null; defaultTaskTokenBudget: number; spend: Spend;
}
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
  acceptanceCriteria: { text: string; met: boolean; evidence?: string | null }[]; status: TaskStatus; priority: Priority;
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
  /** Realignment meetings only: problems exposed and the solutions agreed (delivered: it reached the task's work). */
  problems?: { raisedByAgentId: string | null; taskNumber: number | null; problem: string; solution: string | null; delivered: boolean }[];
}
export interface Meeting {
  id: string; projectId: string; purpose: "KICKOFF" | "ADHOC" | "REALIGNMENT"; title: string; agenda: string; organizerId: string;
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

// ---- Increment 4: execution (docs/10)
export interface TaskRun {
  id: string; projectId: string; taskId: string; agentId: string; branch: string; worktree: string; sandboxId: string | null;
  image: string | null; status: "RUNNING" | "PAUSED" | "SUBMITTED" | "BLOCKED" | "DONE" | "CANCELLED"; pauseRequested: boolean;
  steps: number; segmentSteps: number; inputTokens: number; outputTokens: number; cacheReadTokens: number; tokenBudget: number;
  costUsd: number; blockedReason: string | null; startedAt: string; updatedAt: string; endedAt: string | null;
}
export interface RunStep {
  id: string; runId: string; seq: number; kind: "MODEL" | "TOOL" | "PLATFORM" | "MESSAGE"; tool: string | null; summary: string;
  detail: Record<string, unknown>; outcome: "OK" | "ERROR" | "REFUSED"; inputTokens: number; outputTokens: number;
  durationMs: number; createdAt: string;
}
export interface Gate { status: "ok" | "failed" | "pending"; detail: string }
export interface MergeRequest {
  id: string; projectId: string; number: number; taskId: string; runId: string; sourceBranch: string; targetBranch: string;
  authorAgentId: string; reviewerAgentId: string | null; title: string; summary: string;
  status: "IN_REVIEW" | "CHANGES_REQUESTED" | "AWAITING_APPROVAL" | "MERGED" | "CLOSED"; reviewRounds: number;
  headCommit: string | null; checks: Record<string, Gate>; approvalId: string | null; mergedCommit: string | null;
  createdAt: string; updatedAt: string; mergedAt: string | null;
}
export interface ReviewComment {
  id: string; mrId: string; round: number; authorKind: "AGENT" | "HUMAN"; authorId: string; file: string | null;
  line: number | null; body: string; createdAt: string;
}
export interface FileChange { status: string; path: string; added: number; removed: number }
export interface TestRun {
  id: string; projectId: string; number: number; taskId: string | null; runId: string | null; mrId: string | null; branch: string;
  commitSha: string | null; trigger: string; command: string; status: "PASSED" | "FAILED" | "ERROR"; passed: number | null;
  failed: number | null; durationMs: number; failingExcerpt: string; createdAt: string;
}
export interface Branch { name: string; head: string; ahead: number; behind: number; lastSubject: string; lastDate: string }
export interface Commit { sha: string; author: string; date: string; subject: string }
export interface RepoEntry { path: string; type: string; size: number }

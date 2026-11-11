# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Current state

Increments 1 (Socle) and 2 (Organisation virtuelle) are implemented: `backend/` (Spring Boot), `frontend/` (React), `docker-compose.yml`. Sources of truth:
- `Prompt.md` (French): the full product brief.
- `docs/01-product-discovery.md`: personas, use cases, features, constraints, risks, decisions D1–D12 (validated by the user on 2026-11-08).
- `docs/02-architecture.md`: modules, agent runtime, data model, API and event contracts, security, delivery plan. Diagrams are in `docs/images/`.
- `docs/03-ux-ui.md`: navigation, screen inventory, detailed Virtual Office (agent state → visual mapping) and recruitment flows, visual system.

This is a personal project. Keep project documents as local files in `docs/`. Never create them in the claude.ai organization workspace (Claude Docs / Artifacts).

Scope is the **complete product**, not an MVP. It is delivered in 6 increments (Socle → Organisation → Orchestration → Production → Qualité/Sécurité → Livraison), each gated by a tested pass criterion before the next starts.

- `docs/04-increment-1-report.md`, `docs/05-increment-2-report.md`: what each increment delivers, how it was verified, known limits.
- `docs/06-frontend-audit-et-direction.md` (validated design system "Studio Model"), `docs/07-virtual-office-proposition.md` (validated Virtual Office direction: React Three Fiber, rigged glTF characters; interactive mockup in `docs/design/office/`), `docs/08-refonte-frontend.md` (redesign status), `docs/09-sobriete-tokens.md` (token sobriety, measured), `docs/10-engineering-workspace-proposition.md` (Engineering Workspace and increment 4 proposal; mockup in `docs/design/engineering/`), `docs/11-budget-decoupage-recadrage.md` (editable task budgets, small tasks sized to the per-task budget, Lead task split, realignment meetings), `docs/12-lead-execute-les-ordres.md` (the Lead carries out the CTO's orders from its chat), `docs/13-petits-fournisseurs.md` (rate limits, output caps, credits and native tool calls of small providers).

## Commands

- Local infra: `./scripts/init-env.sh` once, then `docker compose --env-file .env up -d` (Postgres on :55432, Redis on :56379).
- Backend: `cd backend && mvn -q package -DskipTests`, run with `.env` exported: `set -a && . ./.env && set +a && java -jar backend/target/aiw-backend-0.1.0-SNAPSHOT.jar` (port 8090).
- Frontend: `cd frontend && npm run dev` (port 5180, proxies `/api` and `/ws` to 8090). `npm run typecheck`, `npm run build`.
- Backend tests (Docker required, Testcontainers): `cd backend && mvn test`. Run a single test with `mvn test -Dtest='FoundationIntegrationTest#anthropicAdapterUsesSdkWithFallbacksAndListPrice'`.
- Frontend unit tests: `cd frontend && npx vitest run`.
- Visual check (headless system Chrome, logs in with `AIW_DEV_TEST_*` from `.env`): `cd frontend && node scripts/shoot.mjs <outDir> "/w/{ws}/p/{p}/office"`; `node scripts/console.mjs <page>` prints runtime errors. Look at screenshots before claiming a UI change works.
- `ModularityTest` runs Spring Modulith `verify()`: module dependency cycles or access to another module's internals fail the build.

## Code architecture (backend)

`dev.aiw.<module>` packages are Spring Modulith modules: `shared` (open kernel), `access` (membership checks), `identity`, `events`, `governance`, `modelgateway`, `projects`, `organization`, `dashboard`. Allowed direction: identity/projects/organization/modelgateway → governance → events → access → shared. Cross-module reactions use Spring application events published inside the transaction (e.g. `ProjectCreated` → the organization module creates the Lead).

- Every state change publishes a row in `events` inside the same transaction (`EventPublisher.publish`, `Propagation.MANDATORY`). `OutboxRelay` moves it to the Redis stream `aiw:events`; `EventBroadcaster` pushes it to STOMP topic `/topic/workspace.{id}`. The UI only animates/derives state from these events.
- Sensitive or notable actions also go to `AuditLog.record` (hash-chained, append-only via DB trigger).
- Providers: Anthropic adapter plus one OpenAI-compatible adapter that serves every preset in `ProviderPresets` (OpenAI, Gemini, Mistral, DeepSeek, xAI, Groq, Together, OpenRouter, Ollama, LM Studio, custom). Model ids are never hard-coded for them: the UI lists what the provider reports (`GET .../providers/{id}/models`). The workspace default provider (`is_default`) and its `default_model` are bound to new agents; `economy_model` serves light calls.
- All model calls go through `ModelGateway.complete` (budget check, provider key from `SecretVault`, usage + cost + `model.call_completed` event). Never call a provider directly. The gateway waits out rate limits, shrinks `max_tokens` to provider/model/credit ceilings and remembers output caps (`outputCap`); add new provider limit messages there, not in callers.
- Tenant data access must go through `WorkspaceAccess.require*`; non-members get 404.
- Persistence is plain `JdbcClient` SQL; schema in `backend/src/main/resources/db/migration` (Flyway). V1/V2 are not yet released anywhere, but from now on add new migrations instead of editing old ones.
- Spring Boot 4 / Jackson 3: JSON classes are in `tools.jackson.*`, not `com.fasterxml.jackson.*`.
- Task sizing: `TaskSizing.rules(budget)` goes into every Lead prompt that writes tasks (plan, split); keep tasks within one run's token budget (`TaskExecution.taskTokenBudget`). `TaskSplitter` splits a task (`LEAD_SPLIT`); `RealignmentService` runs realignment meetings (`LEAD_REALIGN`, pauses participants' runs, delivers agreed solutions on `MeetingEnded`). Collaboration gets a participant's work status through the `ParticipantStatus` interface, not by depending on orchestration. The Lead's chat (`AgentChatService`) gets the project state and runs `<orders>` through the `LeadCommands` interface, implemented by `orchestration/LeadChatCommands`; outcomes are stored in `agent_messages.actions`.

## Validated architecture decisions

- Self-hosted via Docker Compose; users bring their own model API keys.
- Providers: Anthropic + any OpenAI-compatible API, behind an in-house model-gateway abstraction. Agent tools use MCP.
- Backend: Java 21 / Spring Boot modular monolith (Spring Modulith, 16 modules), plus separate `agent-worker` processes and one ephemeral Docker sandbox per task. Agent code never runs in the api process.
- PostgreSQL 16 (+ pgvector, transactional outbox) and Redis 7 Streams. No Kafka for now.
- Frontend: React + TypeScript; React Three Fiber + drei (Three.js) for the Virtual Office (replaces the earlier PixiJS choice, see `docs/07`); Monaco for the Code Workspace; WebSocket/STOMP for events.
- In-house orchestration engine with persistent steps, leases/heartbeats, idempotent tool calls, and a watchdog.
- Git: local repo per project plus GitHub remote; GitLab comes in increment 6.

## What is being built

An "AI Engineering Workspace": a platform where a developer (acting as CTO) recruits, organizes, and supervises a team of AI agents that design, build, test, secure, and ship real software. It combines three connected spaces:
1. A **Virtual Office**: an immersive graphical office with departments (Backend, Frontend, QA, DevOps, Security, Product, Architecture), agent avatars, and meeting rooms. This is the key product differentiator.
2. **Project/task management**: Kanban and timeline views, meetings with minutes/decisions/action items, and a CTO dashboard.
3. A real **code workspace/IDE**: Git (GitHub/GitLab), branches, diffs, commits, logs, and test results.

Two control modes must coexist: **Mode A**, where an AI Engineering Lead agent analyzes the brief, proposes the team, and delegates and validates work, and **Mode B**, where the human manages everything manually. The human can override the Lead's decisions at any point.

## Mandated working order (from `Prompt.md` §15)

1. Product Discovery: personas, use cases, features, constraints, risks, open decisions.
2. Architecture: components, interactions, data model, multi-agent system, stack choices, security strategy.
3. UX/UI: Virtual Office and recruitment flow first.
4. Implementation in phases (§12): Foundations → Virtual organization → Orchestration → Software production → Quality & delivery. Do not start a new phase until the previous phase's essentials are tested.
5. Validation of real user journeys.

Do not generate the full codebase before the design is established and validated with the user. Each step ends with a report: what is done, what remains, and any blockers.

## Non-negotiable design constraints

- **No fake activity.** Virtual Office animations and agent states (available, thinking, in meeting, developing, reviewing, testing, blocked, awaiting validation, paused, done) must be driven by real system events. Dashboards must not show mock data as if it were real. Clearly label unimplemented features.
- **Real execution engine, not independent prompts.** You need a central orchestrator with persistent state, events, deliverables, dependency tracking, retry/resume, and blockage detection. Agents need shared project memory as well as their own individual context.
- **Provider-agnostic model layer.** Each agent role can map to a different provider/model based on skill, cost, and performance.
- **Objective validation.** An agent never declares work done by itself. Completion requires tests run and passing, review done, acceptance criteria met, and no critical blockers.
- **No silent overwrites.** Agents work on branches, with merge rules, review, and conflict detection, and every change is traceable.
- **Security by design.** Use granular permissions, sandboxed (Docker) execution, secure secret management, quotas and cost limits, and an audit log. These actions require explicit human approval: deleting important files, changing sensitive config, accessing production, releasing, deploying, deleting branches, and modifying sensitive data.
- **Token sobriety** (user requirement, 2026-11-09): use as few model tokens as possible while staying effective. The code decides and the model only judges. Keep context minimal and the stable prefix cached, use rolling summaries, pick the model class per call type, keep outputs terse (patches, not whole files), and memoize calls. See `docs/09-sobriete-tokens.md`.
- Simulated agent "years of experience" is profile flavor, not a claim about the model.
- Architecture should be modular. Avoid both a disorganized monolith and microservices that aren't justified.

## Preferred stack (justify any deviation)

Java 21 / Spring Boot backend where it fits, PostgreSQL, Redis for cache/coordination, an async message broker, React or Angular with TypeScript, WebSockets/SSE for real-time events, Docker for isolation, and GitHub/GitLab integration.

## Code architecture (frontend)

- Virtual Office (`src/office3d/`): `plan.ts` is the pure, tested floor plan (13 rooms in metres, desks, seating, meeting placement, walking routes). `represent.ts` is the representation engine: a pure function from recorded state (agents, open meetings) to figures (spot, posture, state light) and desks (screen content). Components only render that output: `Building` (static architecture), `Workstation`, `Person` (articulated figure posed by joint angles, so a rigged glTF can replace the body), `OfficeCanvas` (camera levels company → room → agent, signs, message arcs), `FloorPlan` (minimap and the 2D view on small screens). The canvas uses `frameloop="demand"`: anything animated must call `invalidate()` only while its recorded cause lasts. Entrance walks happen only for `agent.hired` events received live (`useRealtime().arrivals`), never for replayed ones. Visual check: `node scripts/office-shots.mjs <outDir> [keys...]` (`MEETING=1` convenes a real meeting first).
- `src/realtime/realtime.ts` owns the STOMP connection, replays missed events by seq on (re)connect, and invalidates TanStack Query keys per event type. New event types need a mapping there and a description in `src/lib/events.ts`.
- The agent drawer is global (`src/lib/selection.ts` + `components/AgentDrawer.tsx`); open it with `useSelection().open(id)` instead of building per-page agent panels.
- Design system "Studio Model" (validated): paper/graphite, mineral greys, one signal-orange accent used only for what is live or needs the CTO, IBM Plex Sans + Mono (self-hosted via @fontsource). Tokens are CSS variables in `src/index.css` (paper/light default, slate/dark via `data-theme`), exposed as Tailwind colors (`bg-panel`, `bg-panel-2`, `border-line`, `text-muted`, `text-faint`, `bg-ink`, `bg-graphite`, `accent`, state colors `ok/active/review/testing/warn/danger`). No gradients, no shadows at rest, lists and tables before cards.
- Use `components/ui.tsx` primitives (Button variants primary/ghost/subtle/danger/signal, Table, SectionTitle, Segmented, Tabs, Dot, Badge, Skeleton). Screens for future increments render `NotAvailablePage`, never placeholder data.
- `components/Shell.tsx`: graphite rail with five spaces (Executive, Virtual Office, Agents, Project, Engineering; `SPACES`/`NAV`), context panel per space, breadcrumb, graphite status bar, mobile bottom tabs; the office route is immersive (no panel). `CommandPalette.tsx` handles Ctrl/⌘ K.

-- Increment 3: agent runtime (jobs with leases), shared memory, collaboration (threads, messages,
-- meetings) and orchestration (Lead briefing, hiring proposals, tasks and dependencies).

-- Organization: an agent's displayed activity is its resting activity (AVAILABLE, IN_MEETING, BLOCKED)
-- unless at least one model call is running, in which case it is THINKING.
ALTER TABLE agents ADD COLUMN resting_activity TEXT NOT NULL DEFAULT 'AVAILABLE';
ALTER TABLE agents ADD COLUMN thinking_calls INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agents ADD COLUMN blocked_reason TEXT;

-- Agent runtime
CREATE TABLE agent_jobs (
    id             TEXT PRIMARY KEY,
    workspace_id   TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id     TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    agent_id       TEXT NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    type           TEXT NOT NULL,
    payload        JSONB NOT NULL DEFAULT '{}'::jsonb,
    status         TEXT NOT NULL DEFAULT 'QUEUED'
                   CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    attempts       INTEGER NOT NULL DEFAULT 0,
    max_attempts   INTEGER NOT NULL DEFAULT 3,
    run_after      TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner    TEXT,
    lease_until    TIMESTAMPTZ,
    last_error     TEXT,
    correlation_id TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ
);
CREATE INDEX agent_jobs_runnable_idx ON agent_jobs (created_at) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX agent_jobs_project_idx ON agent_jobs (project_id, created_at);

CREATE TABLE agent_steps (
    id            TEXT PRIMARY KEY,
    workspace_id  TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id    TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    agent_id      TEXT NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    job_id        TEXT REFERENCES agent_jobs (id) ON DELETE SET NULL,
    kind          TEXT NOT NULL,
    summary       TEXT NOT NULL,
    model         TEXT,
    input_tokens  BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cost_usd      NUMERIC(14, 6),
    duration_ms   BIGINT NOT NULL DEFAULT 0,
    outcome       TEXT NOT NULL,
    error         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX agent_steps_agent_idx ON agent_steps (agent_id, created_at);

-- Memory: shared (agent_id IS NULL) or individual. Each change is a new version of the same entry_key.
CREATE TABLE memory_entries (
    id              TEXT PRIMARY KEY,
    workspace_id    TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id      TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    agent_id        TEXT REFERENCES agents (id) ON DELETE CASCADE,
    entry_key       TEXT NOT NULL,
    version         INTEGER NOT NULL,
    kind            TEXT NOT NULL CHECK (kind IN ('ARCHITECTURE', 'DECISION', 'CONTRACT', 'CONVENTION', 'GLOSSARY',
                                                  'REQUIREMENT', 'RISK', 'NOTE')),
    title           TEXT NOT NULL,
    content         TEXT NOT NULL,
    source_kind     TEXT NOT NULL,
    source_id       TEXT,
    author_kind     TEXT NOT NULL,
    author_id       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    search          TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', title || ' ' || content)) STORED,
    UNIQUE (entry_key, version)
);
CREATE INDEX memory_entries_project_idx ON memory_entries (project_id, created_at);
CREATE INDEX memory_entries_search_idx ON memory_entries USING gin (search);

-- Collaboration
CREATE TABLE threads (
    id           TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id   TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    kind         TEXT NOT NULL CHECK (kind IN ('MEETING', 'TASK', 'BRIEFING', 'AGENTS')),
    subject_id   TEXT,
    title        TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX threads_project_idx ON threads (project_id, created_at);
CREATE UNIQUE INDEX threads_subject_uq ON threads (kind, subject_id) WHERE subject_id IS NOT NULL;

CREATE TABLE messages (
    id           TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id   TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    thread_id    TEXT NOT NULL REFERENCES threads (id) ON DELETE CASCADE,
    author_kind  TEXT NOT NULL CHECK (author_kind IN ('AGENT', 'HUMAN', 'SYSTEM')),
    author_id    TEXT,
    to_agent_id  TEXT REFERENCES agents (id) ON DELETE SET NULL,
    type         TEXT NOT NULL CHECK (type IN ('QUESTION', 'ANSWER', 'RECOMMENDATION', 'PROBLEM', 'DEPENDENCY',
                                               'REVIEW_REQUEST', 'ASSIGNMENT', 'CONTRIBUTION')),
    content      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX messages_thread_idx ON messages (thread_id, created_at);
CREATE INDEX messages_project_idx ON messages (project_id, created_at);

CREATE TABLE meetings (
    id                 TEXT PRIMARY KEY,
    workspace_id       TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id         TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    purpose            TEXT NOT NULL CHECK (purpose IN ('KICKOFF', 'ADHOC')),
    title              TEXT NOT NULL,
    agenda             TEXT NOT NULL,
    organizer_agent_id TEXT NOT NULL REFERENCES agents (id),
    convened_by_kind   TEXT NOT NULL,
    convened_by_id     TEXT,
    status             TEXT NOT NULL DEFAULT 'IN_PROGRESS'
                       CHECK (status IN ('IN_PROGRESS', 'SYNTHESIZING', 'ENDED', 'FAILED', 'CANCELLED')),
    rounds             INTEGER NOT NULL DEFAULT 1,
    current_round      INTEGER NOT NULL DEFAULT 1,
    turn_index         INTEGER NOT NULL DEFAULT 0,
    thread_id          TEXT NOT NULL REFERENCES threads (id),
    minutes            JSONB,
    error              TEXT,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at           TIMESTAMPTZ
);
CREATE INDEX meetings_project_idx ON meetings (project_id, started_at);

CREATE TABLE meeting_participants (
    meeting_id TEXT NOT NULL REFERENCES meetings (id) ON DELETE CASCADE,
    agent_id   TEXT NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    turn_order INTEGER NOT NULL,
    PRIMARY KEY (meeting_id, agent_id)
);

-- Orchestration
CREATE TABLE briefings (
    id           TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id   TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    lead_id      TEXT NOT NULL REFERENCES agents (id),
    status       TEXT NOT NULL CHECK (status IN ('ANALYZING', 'AWAITING_ANSWERS', 'ARCHITECTING', 'PROPOSED',
                                                 'APPROVED', 'FAILED', 'CANCELLED')),
    analysis     JSONB,
    questions    JSONB NOT NULL DEFAULT '[]'::jsonb,
    architecture TEXT,
    error        TEXT,
    created_by   TEXT NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX briefings_project_idx ON briefings (project_id, created_at);

CREATE TABLE hiring_proposals (
    id                  TEXT PRIMARY KEY,
    workspace_id        TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id          TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    briefing_id         TEXT NOT NULL REFERENCES briefings (id) ON DELETE CASCADE,
    version             INTEGER NOT NULL,
    status              TEXT NOT NULL CHECK (status IN ('PENDING', 'REVISING', 'ACCEPTED', 'REJECTED', 'SUPERSEDED')),
    rationale           TEXT NOT NULL,
    lines               JSONB NOT NULL,
    estimated_daily_usd NUMERIC(14, 6),
    revision_comment    TEXT,
    decided_by          TEXT REFERENCES users (id),
    decided_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (briefing_id, version)
);

CREATE TABLE tasks (
    id                  TEXT PRIMARY KEY,
    workspace_id        TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id          TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    number              INTEGER NOT NULL,
    title               TEXT NOT NULL,
    description         TEXT NOT NULL DEFAULT '',
    acceptance_criteria JSONB NOT NULL DEFAULT '[]'::jsonb,
    status              TEXT NOT NULL CHECK (status IN ('CREATED', 'READY', 'ASSIGNED', 'IN_PROGRESS', 'BLOCKED',
                                                        'IN_REVIEW', 'IN_TEST', 'VALIDATION', 'DONE', 'CANCELLED')),
    priority            TEXT NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    estimate_days       NUMERIC(6, 2),
    assignee_id         TEXT REFERENCES agents (id) ON DELETE SET NULL,
    blocked_reason      TEXT,
    status_before_block TEXT,
    source_kind         TEXT NOT NULL CHECK (source_kind IN ('PLAN', 'MEETING', 'MANUAL')),
    source_id           TEXT,
    created_by_kind     TEXT NOT NULL,
    created_by_id       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, number)
);
CREATE INDEX tasks_project_idx ON tasks (project_id, status);
CREATE INDEX tasks_assignee_idx ON tasks (assignee_id);

CREATE TABLE task_dependencies (
    task_id       TEXT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    depends_on_id TEXT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, depends_on_id),
    CHECK (task_id <> depends_on_id)
);
CREATE INDEX task_dependencies_on_idx ON task_dependencies (depends_on_id);

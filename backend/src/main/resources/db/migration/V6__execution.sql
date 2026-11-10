-- Increment 4: agents execute their tasks (docs/10-engineering-workspace-proposition.md).

-- One Git repository per project, on the platform's disk.
CREATE TABLE repositories (
    project_id     TEXT PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,
    workspace_id   TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    path           TEXT NOT NULL,
    default_branch TEXT NOT NULL DEFAULT 'main',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A run: one agent working on one task, on its branch, in its sandbox, until it submits, blocks or is paused.
CREATE TABLE task_runs (
    id              TEXT PRIMARY KEY,
    workspace_id    TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id      TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    task_id         TEXT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    agent_id        TEXT NOT NULL REFERENCES agents (id),
    branch          TEXT NOT NULL,
    worktree        TEXT NOT NULL,
    sandbox_id      TEXT,
    image           TEXT,
    status          TEXT NOT NULL CHECK (status IN ('RUNNING', 'PAUSED', 'SUBMITTED', 'BLOCKED', 'DONE', 'CANCELLED')),
    pause_requested BOOLEAN NOT NULL DEFAULT false,
    steps           INTEGER NOT NULL DEFAULT 0,
    segment_steps   INTEGER NOT NULL DEFAULT 0,
    input_tokens    BIGINT NOT NULL DEFAULT 0,
    output_tokens   BIGINT NOT NULL DEFAULT 0,
    cache_read_tokens BIGINT NOT NULL DEFAULT 0,
    token_budget    BIGINT NOT NULL DEFAULT 200000,
    cost_usd        NUMERIC(14, 6) NOT NULL DEFAULT 0,
    blocked_reason  TEXT,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ
);
CREATE INDEX task_runs_task_idx ON task_runs (task_id, started_at DESC);
CREATE INDEX task_runs_agent_idx ON task_runs (agent_id, status);
CREATE UNIQUE INDEX task_runs_one_open_idx ON task_runs (task_id) WHERE status IN ('RUNNING', 'PAUSED', 'SUBMITTED');

-- Every step of a run: a model decision, a tool call, a platform action, a message received.
CREATE TABLE run_steps (
    id            TEXT PRIMARY KEY,
    run_id        TEXT NOT NULL REFERENCES task_runs (id) ON DELETE CASCADE,
    seq           INTEGER NOT NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('MODEL', 'TOOL', 'PLATFORM', 'MESSAGE')),
    tool          TEXT,
    summary       TEXT NOT NULL,
    detail        JSONB NOT NULL DEFAULT '{}'::jsonb,
    outcome       TEXT NOT NULL DEFAULT 'OK' CHECK (outcome IN ('OK', 'ERROR', 'REFUSED')),
    input_tokens  BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    duration_ms   BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, seq)
);

CREATE TABLE merge_requests (
    id                TEXT PRIMARY KEY,
    workspace_id      TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id        TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    number            INTEGER NOT NULL,
    task_id           TEXT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    run_id            TEXT NOT NULL REFERENCES task_runs (id) ON DELETE CASCADE,
    source_branch     TEXT NOT NULL,
    target_branch     TEXT NOT NULL DEFAULT 'main',
    author_agent_id   TEXT NOT NULL REFERENCES agents (id),
    reviewer_agent_id TEXT REFERENCES agents (id),
    title             TEXT NOT NULL,
    summary           TEXT NOT NULL DEFAULT '',
    status            TEXT NOT NULL CHECK (status IN ('IN_REVIEW', 'CHANGES_REQUESTED', 'AWAITING_APPROVAL', 'MERGED', 'CLOSED')),
    review_rounds     INTEGER NOT NULL DEFAULT 0,
    head_commit       TEXT,
    checks            JSONB NOT NULL DEFAULT '{}'::jsonb,
    approval_id       TEXT,
    merged_commit     TEXT,
    merged_by         TEXT REFERENCES users (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    merged_at         TIMESTAMPTZ,
    UNIQUE (project_id, number)
);
CREATE INDEX merge_requests_task_idx ON merge_requests (task_id);

CREATE TABLE review_comments (
    id          TEXT PRIMARY KEY,
    mr_id       TEXT NOT NULL REFERENCES merge_requests (id) ON DELETE CASCADE,
    round       INTEGER NOT NULL,
    author_kind TEXT NOT NULL CHECK (author_kind IN ('AGENT', 'HUMAN')),
    author_id   TEXT NOT NULL,
    file        TEXT,
    line        INTEGER,
    body        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX review_comments_mr_idx ON review_comments (mr_id, created_at);

-- Tests are executed by the platform in the sandbox, never reported by the agent.
CREATE TABLE test_runs (
    id              TEXT PRIMARY KEY,
    workspace_id    TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id      TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    number          INTEGER NOT NULL,
    task_id         TEXT REFERENCES tasks (id) ON DELETE CASCADE,
    run_id          TEXT REFERENCES task_runs (id) ON DELETE CASCADE,
    mr_id           TEXT REFERENCES merge_requests (id) ON DELETE CASCADE,
    branch          TEXT NOT NULL,
    commit_sha      TEXT,
    trigger         TEXT NOT NULL,
    command         TEXT NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('PASSED', 'FAILED', 'ERROR')),
    passed          INTEGER,
    failed          INTEGER,
    duration_ms     BIGINT NOT NULL,
    failing_excerpt TEXT NOT NULL DEFAULT '',
    output          TEXT NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, number)
);
CREATE INDEX test_runs_project_idx ON test_runs (project_id, created_at DESC);

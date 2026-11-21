-- Increments 5 and 6 (docs/18): security scans and findings, test environments, deployments, releases, remotes,
-- invitations. Everything here is decided by code: no model call.

-- A fix task comes from a finding (source_kind FINDING, source_id = the finding).
ALTER TABLE tasks DROP CONSTRAINT tasks_source_kind_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_source_kind_check CHECK (source_kind IN ('PLAN', 'MEETING', 'MANUAL', 'SPLIT', 'FINDING'));

CREATE TABLE quality_scans (
    id           TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id   TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    number       INTEGER NOT NULL,
    kind         TEXT NOT NULL CHECK (kind IN ('SECRETS', 'SAST', 'DEPENDENCIES', 'DAST', 'LOAD', 'E2E')),
    tool         TEXT NOT NULL,
    ref          TEXT NOT NULL,
    commit_sha   TEXT,
    target_url   TEXT,
    trigger      TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED', 'SKIPPED')),
    summary      JSONB NOT NULL DEFAULT '{}',
    error        TEXT,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at     TIMESTAMPTZ,
    duration_ms  BIGINT,
    UNIQUE (project_id, number)
);
CREATE INDEX quality_scans_project_idx ON quality_scans (project_id, started_at DESC);

CREATE TABLE security_findings (
    id              TEXT PRIMARY KEY,
    workspace_id    TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id      TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    number          INTEGER NOT NULL,
    kind            TEXT NOT NULL,
    tool            TEXT NOT NULL,
    rule            TEXT NOT NULL,
    severity        TEXT NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO')),
    title           TEXT NOT NULL,
    file            TEXT,
    line            INTEGER,
    location        TEXT,
    evidence        TEXT NOT NULL DEFAULT '',
    reference       TEXT,
    recommendation  TEXT NOT NULL DEFAULT '',
    fingerprint     TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'FIX_TASK', 'RISK_ACCEPTED', 'FIXED')),
    task_id         TEXT REFERENCES tasks (id) ON DELETE SET NULL,
    accepted_by     TEXT REFERENCES users (id),
    accepted_reason TEXT,
    first_scan_id   TEXT REFERENCES quality_scans (id) ON DELETE SET NULL,
    last_scan_id    TEXT REFERENCES quality_scans (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    fixed_at        TIMESTAMPTZ,
    UNIQUE (project_id, number),
    UNIQUE (project_id, fingerprint)
);
CREATE INDEX security_findings_open_idx ON security_findings (project_id, status, severity);

CREATE TABLE environments (
    id                    TEXT PRIMARY KEY,
    workspace_id          TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id            TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name                  TEXT NOT NULL,
    kind                  TEXT NOT NULL DEFAULT 'DOCKER_LOCAL',
    host_port             INTEGER NOT NULL UNIQUE,
    url                   TEXT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'EMPTY' CHECK (status IN ('EMPTY', 'DEPLOYING', 'HEALTHY', 'UNHEALTHY', 'STOPPED')),
    current_deployment_id TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, name)
);

CREATE TABLE releases (
    id                TEXT PRIMARY KEY,
    workspace_id      TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id        TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    version           TEXT NOT NULL,
    commit_sha        TEXT NOT NULL,
    notes             TEXT NOT NULL DEFAULT '',
    status            TEXT NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'RELEASED', 'REJECTED', 'FAILED')),
    approval_id       TEXT REFERENCES approvals (id),
    requested_by_kind TEXT NOT NULL,
    requested_by_id   TEXT NOT NULL,
    error             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at       TIMESTAMPTZ,
    UNIQUE (project_id, version)
);

CREATE TABLE deployments (
    id                TEXT PRIMARY KEY,
    workspace_id      TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id        TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    environment_id    TEXT NOT NULL REFERENCES environments (id) ON DELETE CASCADE,
    number            INTEGER NOT NULL,
    ref               TEXT NOT NULL,
    commit_sha        TEXT NOT NULL,
    release_id        TEXT REFERENCES releases (id) ON DELETE SET NULL,
    rollback_of       TEXT,
    status            TEXT NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'REJECTED', 'QUEUED', 'BUILDING', 'STARTING',
                                                     'HEALTHY', 'FAILED', 'SUPERSEDED', 'STOPPED')),
    approval_id       TEXT REFERENCES approvals (id),
    approved_by       TEXT REFERENCES users (id),
    requested_by_kind TEXT NOT NULL,
    requested_by_id   TEXT NOT NULL,
    image             TEXT,
    container_id      TEXT,
    container_port    INTEGER,
    url               TEXT,
    log               TEXT NOT NULL DEFAULT '',
    error             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    UNIQUE (project_id, number)
);
CREATE INDEX deployments_env_idx ON deployments (environment_id, created_at DESC);

-- GitHub/GitLab remote of a project; the token lives in the vault (secrets), referenced by name.
CREATE TABLE project_remotes (
    project_id     TEXT PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,
    workspace_id   TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    provider       TEXT NOT NULL CHECK (provider IN ('GITHUB', 'GITLAB', 'OTHER')),
    url            TEXT NOT NULL,
    secret_name    TEXT,
    last_push_at   TIMESTAMPTZ,
    last_push_ok   BOOLEAN,
    last_push_info TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invitations (
    id           TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    email        TEXT NOT NULL,
    role         TEXT NOT NULL CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER')),
    token_hash   TEXT NOT NULL UNIQUE,
    invited_by   TEXT NOT NULL REFERENCES users (id),
    expires_at   TIMESTAMPTZ NOT NULL,
    accepted_by  TEXT REFERENCES users (id),
    accepted_at  TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX invitations_workspace_idx ON invitations (workspace_id, created_at DESC);

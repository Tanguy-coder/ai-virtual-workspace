-- Increment 1 (Socle): identity, projects, organization, events, audit, governance, model gateway.
-- Identifiers are ULIDs stored as text. Every tenant-scoped table carries workspace_id.

CREATE TABLE users (
    id            TEXT PRIMARY KEY,
    email         TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_uq ON users (lower(email));

CREATE TABLE workspaces (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    owner_id   TEXT NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE memberships (
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         TEXT NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX memberships_user_idx ON memberships (user_id);

CREATE TABLE projects (
    id           TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    description  TEXT NOT NULL DEFAULT '',
    status       TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by   TEXT NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX projects_workspace_idx ON projects (workspace_id);

CREATE TABLE project_documents (
    id           TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id   TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    kind         TEXT NOT NULL CHECK (kind IN ('SPECIFICATION', 'TECHNICAL')),
    title        TEXT NOT NULL,
    content      TEXT NOT NULL,
    media_type   TEXT NOT NULL,
    size_bytes   INTEGER NOT NULL,
    created_by   TEXT NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX project_documents_project_idx ON project_documents (project_id);

-- Event log. Also acts as the transactional outbox: rows with published_at IS NULL
-- have not been relayed to Redis yet.
CREATE TABLE events (
    seq          BIGSERIAL PRIMARY KEY,
    id           TEXT NOT NULL UNIQUE,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id   TEXT,
    type         TEXT NOT NULL,
    actor_kind   TEXT NOT NULL CHECK (actor_kind IN ('HUMAN', 'AGENT', 'SYSTEM')),
    actor_id     TEXT,
    subject_kind TEXT NOT NULL,
    subject_id   TEXT NOT NULL,
    payload      JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX events_workspace_seq_idx ON events (workspace_id, seq);
CREATE INDEX events_project_seq_idx ON events (project_id, seq);
CREATE INDEX events_unpublished_idx ON events (seq) WHERE published_at IS NULL;

-- Append-only audit trail, hash-chained per workspace.
CREATE TABLE audit_log (
    seq          BIGSERIAL PRIMARY KEY,
    id           TEXT NOT NULL UNIQUE,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    actor_kind   TEXT NOT NULL,
    actor_id     TEXT,
    action       TEXT NOT NULL,
    target_kind  TEXT NOT NULL,
    target_id    TEXT NOT NULL,
    decision     TEXT NOT NULL CHECK (decision IN ('ALLOWED', 'DENIED', 'APPROVED', 'REJECTED', 'RECORDED')),
    details      JSONB NOT NULL DEFAULT '{}'::jsonb,
    prev_hash    TEXT NOT NULL,
    hash         TEXT NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_workspace_seq_idx ON audit_log (workspace_id, seq);

CREATE OR REPLACE FUNCTION audit_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();

CREATE TABLE secrets (
    id           TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id   TEXT REFERENCES projects (id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    nonce        BYTEA NOT NULL,
    ciphertext   BYTEA NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE approvals (
    id                TEXT PRIMARY KEY,
    workspace_id      TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id        TEXT REFERENCES projects (id) ON DELETE CASCADE,
    requested_by_kind TEXT NOT NULL,
    requested_by_id   TEXT,
    action            TEXT NOT NULL,
    target_kind       TEXT NOT NULL,
    target_id         TEXT NOT NULL,
    summary           TEXT NOT NULL,
    details           JSONB NOT NULL DEFAULT '{}'::jsonb,
    status            TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by        TEXT REFERENCES users (id),
    decided_at        TIMESTAMPTZ,
    reason            TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX approvals_workspace_status_idx ON approvals (workspace_id, status);

-- Model gateway
CREATE TABLE providers (
    id           TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    kind         TEXT NOT NULL CHECK (kind IN ('ANTHROPIC', 'OPENAI_COMPATIBLE')),
    name         TEXT NOT NULL,
    base_url     TEXT,
    secret_id    TEXT REFERENCES secrets (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX providers_workspace_idx ON providers (workspace_id);

-- Prices in USD per million tokens. workspace_id NULL = built-in list price.
CREATE TABLE model_prices (
    id                   TEXT PRIMARY KEY,
    workspace_id         TEXT REFERENCES workspaces (id) ON DELETE CASCADE,
    provider_kind        TEXT NOT NULL,
    model                TEXT NOT NULL,
    input_usd_per_mtok   NUMERIC(12, 4) NOT NULL,
    output_usd_per_mtok  NUMERIC(12, 4) NOT NULL
);
CREATE UNIQUE INDEX model_prices_uq ON model_prices (coalesce(workspace_id, ''), provider_kind, model);

CREATE TABLE usage_records (
    id            TEXT PRIMARY KEY,
    workspace_id  TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id    TEXT,
    agent_id      TEXT,
    provider_id   TEXT NOT NULL REFERENCES providers (id),
    model         TEXT NOT NULL,
    input_tokens  BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cost_usd      NUMERIC(14, 6),
    latency_ms    BIGINT NOT NULL,
    outcome       TEXT NOT NULL CHECK (outcome IN ('OK', 'REFUSED', 'ERROR')),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX usage_records_project_idx ON usage_records (project_id, occurred_at);
CREATE INDEX usage_records_workspace_idx ON usage_records (workspace_id, occurred_at);

CREATE TABLE budgets (
    project_id      TEXT PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,
    workspace_id    TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    daily_limit_usd NUMERIC(14, 6),
    total_limit_usd NUMERIC(14, 6),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Organization
CREATE TABLE role_templates (
    id               TEXT PRIMARY KEY,
    workspace_id     TEXT REFERENCES workspaces (id) ON DELETE CASCADE,
    family           TEXT NOT NULL,
    title            TEXT NOT NULL,
    department       TEXT NOT NULL,
    skills           TEXT[] NOT NULL DEFAULT '{}',
    responsibilities TEXT[] NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agents (
    id                  TEXT PRIMARY KEY,
    workspace_id        TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id          TEXT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    role_template_id    TEXT REFERENCES role_templates (id),
    name                TEXT NOT NULL,
    title               TEXT NOT NULL,
    department          TEXT NOT NULL,
    seniority           TEXT NOT NULL CHECK (seniority IN ('JUNIOR', 'MID', 'SENIOR', 'PRINCIPAL')),
    simulated_years     INTEGER NOT NULL DEFAULT 0,
    skills              TEXT[] NOT NULL DEFAULT '{}',
    responsibilities    TEXT[] NOT NULL DEFAULT '{}',
    system_instructions TEXT NOT NULL,
    provider_id         TEXT REFERENCES providers (id) ON DELETE SET NULL,
    model               TEXT,
    is_lead             BOOLEAN NOT NULL DEFAULT false,
    status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED', 'DISMISSED')),
    activity            TEXT NOT NULL DEFAULT 'AVAILABLE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX agents_project_idx ON agents (project_id);
CREATE UNIQUE INDEX agents_one_lead_per_project ON agents (project_id) WHERE is_lead AND status <> 'DISMISSED';

CREATE TABLE agent_messages (
    id           TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    agent_id     TEXT NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    author_kind  TEXT NOT NULL CHECK (author_kind IN ('HUMAN', 'AGENT')),
    author_id    TEXT NOT NULL,
    content      TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('PENDING', 'ANSWERED', 'FAILED', 'FINAL')),
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX agent_messages_agent_idx ON agent_messages (agent_id, created_at);

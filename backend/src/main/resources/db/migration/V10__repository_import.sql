-- CTO requirement 2026-11-18 (docs/17): a project can start from existing code, cloned from a Git repository or
-- imported from a folder. The access token, if any, is in the secret vault, never in the repository's configuration.
ALTER TABLE repositories ADD COLUMN source_kind TEXT NOT NULL DEFAULT 'NEW';
ALTER TABLE repositories ADD COLUMN remote_url TEXT;
ALTER TABLE repositories ADD COLUMN remote_branch TEXT;
ALTER TABLE repositories ADD COLUMN remote_secret_id TEXT REFERENCES secrets (id);
ALTER TABLE repositories ADD COLUMN imported_at TIMESTAMPTZ;

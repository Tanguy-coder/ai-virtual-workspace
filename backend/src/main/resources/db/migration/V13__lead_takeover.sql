-- The Lead takes over a task its agent failed four times (docs/17): it works on the agent's branch with the same tools
-- until the tests pass, then submits or hands the work back. While takeover_by is set, the run's steps are the Lead's.
ALTER TABLE task_runs ADD COLUMN takeover_by TEXT REFERENCES agents(id) ON DELETE SET NULL;
ALTER TABLE task_runs ADD COLUMN takeover_seq INTEGER;
ALTER TABLE task_runs ADD COLUMN takeover_tokens BIGINT;

-- CTO feedback 2026-11-10: editable task budgets, small tasks, realignment meetings mid-project.

-- Token budget given to each new run of the project (NULL: the platform default, aiw.execution.token-budget).
ALTER TABLE budgets ADD COLUMN task_token_budget BIGINT CHECK (task_token_budget IS NULL OR task_token_budget > 0);

-- A task too big for its budget is split by the Lead into smaller tasks (source_kind SPLIT, source_id = the original).
-- base_branch: the first of them continues the work already committed on the original task's branch.
ALTER TABLE tasks DROP CONSTRAINT tasks_source_kind_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_source_kind_check CHECK (source_kind IN ('PLAN', 'MEETING', 'MANUAL', 'SPLIT'));
ALTER TABLE tasks ADD COLUMN base_branch TEXT;

-- Realignment: a meeting convened mid-project, by the CTO or by the Lead on the CTO's order. The participants' running
-- work is paused for its duration and resumes when it ends.
ALTER TABLE meetings DROP CONSTRAINT meetings_purpose_check;
ALTER TABLE meetings ADD CONSTRAINT meetings_purpose_check CHECK (purpose IN ('KICKOFF', 'ADHOC', 'REALIGNMENT'));
ALTER TABLE task_runs ADD COLUMN paused_for_meeting TEXT REFERENCES meetings (id) ON DELETE SET NULL;

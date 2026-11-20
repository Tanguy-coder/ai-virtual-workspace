-- The Lead settled a block while the agent works on another task (docs/16): the run resumes as soon as the agent is
-- free, before the agent gets a new task, instead of going to the CTO.
ALTER TABLE task_runs ADD COLUMN resume_when_free BOOLEAN NOT NULL DEFAULT false;

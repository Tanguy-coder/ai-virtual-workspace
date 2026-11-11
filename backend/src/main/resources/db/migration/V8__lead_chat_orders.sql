-- CTO requirement 2026-11-11: the Lead carries out the CTO's orders from its chat. Outcomes recorded by the platform
-- for each order of a reply: [{"action": "...", "ok": true, "detail": "..."}].
ALTER TABLE agent_messages ADD COLUMN actions JSONB;

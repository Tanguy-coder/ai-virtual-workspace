-- CTO requirement 2026-11-14 (docs/15): the platform picks models itself. The Lead assigns each recruit the model
-- that fits its mission; a call whose model is out of quota moves to the best other model available.

-- Who chose the agent's model (CTO, LEAD, SYSTEM) and why, shown in the agent drawer.
ALTER TABLE agents ADD COLUMN model_source TEXT;
ALTER TABLE agents ADD COLUMN model_reason TEXT;
UPDATE agents SET model_source = 'CTO' WHERE model IS NOT NULL;

-- What the platform observed of each model: replies it could use or not (JSON agents). Adjusts the router's priors.
CREATE TABLE model_reputation (
    workspace_id TEXT NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    provider_id  TEXT NOT NULL REFERENCES providers (id) ON DELETE CASCADE,
    model        TEXT NOT NULL,
    usable       BIGINT NOT NULL DEFAULT 0,
    unusable     BIGINT NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, provider_id, model)
);

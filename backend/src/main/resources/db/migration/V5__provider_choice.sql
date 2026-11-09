-- Any provider can serve the agents, not only Anthropic.
-- default_model: model bound to new agents when this provider is the workspace default.
-- is_default: the provider new agents are bound to (one per workspace).
ALTER TABLE providers ADD COLUMN default_model TEXT;
ALTER TABLE providers ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT false;
-- Which preset the provider was created from (openai, mistral, ollama...), for display only.
ALTER TABLE providers ADD COLUMN preset TEXT;
UPDATE providers SET default_model = 'claude-opus-5-5', preset = 'anthropic' WHERE kind = 'ANTHROPIC';
UPDATE providers p SET is_default = true
WHERE p.id = (SELECT q.id FROM providers q WHERE q.workspace_id = p.workspace_id
              ORDER BY (q.kind = 'ANTHROPIC') DESC, q.created_at LIMIT 1);
CREATE UNIQUE INDEX providers_one_default_idx ON providers (workspace_id) WHERE is_default;

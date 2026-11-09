-- Token sobriety (docs/09-sobriete-tokens.md).

-- Provider prompt cache: tokens written to and read from the cache, priced apart (write 1.25x, read 0.1x).
-- REUSED: the answer came from a memoized identical call; nothing was sent to the provider.
ALTER TABLE usage_records ADD COLUMN cache_write_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE usage_records ADD COLUMN cache_read_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE usage_records DROP CONSTRAINT usage_records_outcome_check;
ALTER TABLE usage_records ADD CONSTRAINT usage_records_outcome_check CHECK (outcome IN ('OK', 'REFUSED', 'ERROR', 'REUSED'));

-- Model used for light calls (summaries, minutes, extraction). NULL: light calls use the agent's own model.
ALTER TABLE providers ADD COLUMN economy_model TEXT;
UPDATE providers SET economy_model = 'claude-haiku-5-5' WHERE kind = 'ANTHROPIC';

-- Memoized model answers: an identical request (same provider, model, prompt, limits) within 24 h is not sent again.
CREATE TABLE model_call_cache (
    project_id   TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    text         TEXT NOT NULL,
    served_model TEXT NOT NULL,
    stop_reason  TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, request_hash)
);

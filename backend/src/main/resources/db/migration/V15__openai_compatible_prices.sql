-- List prices (USD per million tokens) of common OpenAI-compatible models, so the USD budget of a project applies to
-- them too (until now every call to them was unpriced and a spend cap never triggered). Dated ids
-- (gpt-4.1-mini-2025-04-14) and provider prefixes (openai/gpt-oss-120b) resolve to these (PricingService.find).
-- They are list prices: a workspace can override any of them in Settings.
INSERT INTO model_prices (id, workspace_id, provider_kind, model, input_usd_per_mtok, output_usd_per_mtok) VALUES
    ('mp_gpt-4.1',        NULL, 'OPENAI_COMPATIBLE', 'gpt-4.1',        2.00,  8.00),
    ('mp_gpt-4.1-mini',   NULL, 'OPENAI_COMPATIBLE', 'gpt-4.1-mini',   0.40,  1.60),
    ('mp_gpt-4.1-nano',   NULL, 'OPENAI_COMPATIBLE', 'gpt-4.1-nano',   0.10,  0.40),
    ('mp_gpt-4o',         NULL, 'OPENAI_COMPATIBLE', 'gpt-4o',         2.50, 10.00),
    ('mp_gpt-4o-mini',    NULL, 'OPENAI_COMPATIBLE', 'gpt-4o-mini',    0.15,  0.60),
    ('mp_gpt-5',          NULL, 'OPENAI_COMPATIBLE', 'gpt-5',          1.25, 10.00),
    ('mp_gpt-5-mini',     NULL, 'OPENAI_COMPATIBLE', 'gpt-5-mini',     0.25,  2.00),
    ('mp_gpt-5-nano',     NULL, 'OPENAI_COMPATIBLE', 'gpt-5-nano',     0.05,  0.40),
    ('mp_o3',             NULL, 'OPENAI_COMPATIBLE', 'o3',             2.00,  8.00),
    ('mp_o4-mini',        NULL, 'OPENAI_COMPATIBLE', 'o4-mini',        1.10,  4.40),
    ('mp_gpt-oss-120b',   NULL, 'OPENAI_COMPATIBLE', 'gpt-oss-120b',   0.15,  0.75),
    ('mp_gpt-oss-20b',    NULL, 'OPENAI_COMPATIBLE', 'gpt-oss-20b',    0.10,  0.50)
ON CONFLICT (id) DO NOTHING;

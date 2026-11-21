-- "Pro" models cost 10 to 60 times more: with their list price, the router's sobriety penalty keeps them for work that
-- needs them, instead of picking them unpriced (seen 2026-11-21: a Lead bound to gpt-5-pro).
INSERT INTO model_prices (id, workspace_id, provider_kind, model, input_usd_per_mtok, output_usd_per_mtok) VALUES
    ('mp_gpt-5-pro', NULL, 'OPENAI_COMPATIBLE', 'gpt-5-pro', 15.00, 120.00),
    ('mp_o3-pro',    NULL, 'OPENAI_COMPATIBLE', 'o3-pro',    20.00,  80.00),
    ('mp_o1-pro',    NULL, 'OPENAI_COMPATIBLE', 'o1-pro',   150.00, 600.00)
ON CONFLICT (id) DO NOTHING;

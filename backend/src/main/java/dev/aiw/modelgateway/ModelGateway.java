package dev.aiw.modelgateway;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single entry point for model calls: budget check, provider resolution, the call itself,
 * then usage, cost and a model.call_completed event recorded in one transaction.
 * Failed calls are recorded too (outcome ERROR, zero tokens) so the activity is never hidden.
 */
@Service
public class ModelGateway {
    private static final BigDecimal[] THRESHOLDS = {new BigDecimal("0.8"), BigDecimal.ONE};

    private final ProviderService providers;
    private final PricingService pricing;
    private final BudgetService budgets;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    ModelGateway(ProviderService providers, PricingService pricing, BudgetService budgets, EventPublisher events,
                 TransactionTemplate tx, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.providers = providers;
        this.pricing = pricing;
        this.budgets = budgets;
        this.events = events;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    public ModelResult complete(ModelRequest request) {
        Provider provider = providers.find(request.workspaceId(), request.providerId())
                .orElseThrow(() -> ApiException.badRequest("provider_missing", "The configured provider no longer exists"));
        if (request.tier() == ModelRequest.Tier.LIGHT && provider.economyModel() != null) {
            request = request.withModel(provider.economyModel());
        }
        ModelRequest req = request;

        // Memoized answer: an identical request in the last 24 h is not sent again (retries, replays).
        String hash = hash(provider, req);
        if (req.projectId() != null) {
            var hit = jdbc.sql("""
                    SELECT text, served_model, stop_reason FROM model_call_cache
                    WHERE project_id = ? AND request_hash = ? AND created_at > now() - interval '24 hours'
                    """).params(req.projectId(), hash)
                    .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).optional();
            if (hit.isPresent()) {
                String[] h = hit.get();
                record(req, provider, h[1], 0, 0, 0, 0, BigDecimal.ZERO, 0, "REUSED", null);
                return new ModelResult(h[0], h[1], 0, 0, false, h[2], 0, BigDecimal.ZERO, 0, 0, true, req.projectId(), hash);
            }
        }

        budgets.ensureWithinBudget(req.projectId());
        String apiKey = providers.apiKey(req.workspaceId(), provider.id());
        ModelAdapter adapter = providers.adapter(provider.kind());

        long start = System.nanoTime();
        AdapterResult result;
        try {
            result = adapter.call(provider.id(), provider.baseUrl(), apiKey, req);
        } catch (ModelCallException e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            record(req, provider, req.model(), 0, 0, 0, 0, null, latency, "ERROR", e.getMessage());
            throw e;
        }
        long latency = (System.nanoTime() - start) / 1_000_000;
        BigDecimal readFactor = provider.kind() == ProviderKind.ANTHROPIC ? new BigDecimal("0.1") : new BigDecimal("0.5");
        BigDecimal cost = pricing.find(req.workspaceId(), provider.kind(), result.servedModel())
                .map(p -> PricingService.cost(p, result.inputTokens(), result.outputTokens(), result.cacheWriteTokens(),
                        result.cacheReadTokens(), readFactor))
                .orElse(null);
        record(req, provider, result.servedModel(), result.inputTokens(), result.outputTokens(), result.cacheWriteTokens(),
                result.cacheReadTokens(), cost, latency, result.refused() ? "REFUSED" : "OK", null);
        boolean complete = !result.refused() && !"max_tokens".equals(result.stopReason()) && !result.text().isBlank();
        if (req.projectId() != null && complete) {
            jdbc.sql("""
                    INSERT INTO model_call_cache (project_id, request_hash, text, served_model, stop_reason)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (project_id, request_hash) DO UPDATE
                    SET text = EXCLUDED.text, served_model = EXCLUDED.served_model, stop_reason = EXCLUDED.stop_reason, created_at = now()
                    """).params(req.projectId(), hash, result.text(), result.servedModel(), result.stopReason()).update();
        }
        return new ModelResult(result.text(), result.servedModel(), result.inputTokens(), result.outputTokens(),
                result.refused(), result.stopReason(), latency, cost, result.cacheWriteTokens(), result.cacheReadTokens(), false,
                req.projectId(), hash);
    }

    /** Drops a memoized answer the caller could not use, so a retry asks the model again. */
    public void forget(ModelResult result) {
        if (result.projectId() == null || result.requestHash() == null) return;
        jdbc.sql("DELETE FROM model_call_cache WHERE project_id = ? AND request_hash = ?")
                .params(result.projectId(), result.requestHash()).update();
    }

    /** Identity of a request: everything that can change the answer. */
    static String hash(Provider provider, ModelRequest r) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            java.util.function.Consumer<String> put = v -> {
                byte[] b = (v == null ? "\u0000" : v).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                md.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());
                md.update(b);
            };
            put.accept(provider.id());
            put.accept(r.agentId());
            put.accept(r.model());
            put.accept(r.systemPrefix());
            put.accept(r.system());
            for (ChatTurn t : r.turns()) {
                put.accept(t.role().name());
                put.accept(t.text());
            }
            put.accept(String.valueOf(r.maxTokens()));
            put.accept(r.effort());
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void record(ModelRequest request, Provider provider, String model, long in, long out, long cacheWrite,
                        long cacheRead, BigDecimal cost, long latency, String outcome, String error) {
        tx.executeWithoutResult(status -> {
            BudgetService.Spend before = request.projectId() == null ? null : budgets.spend(request.projectId());
            String id = Ids.newId();
            jdbc.sql("""
                    INSERT INTO usage_records (id, workspace_id, project_id, agent_id, provider_id, model, input_tokens,
                                               output_tokens, cache_write_tokens, cache_read_tokens, cost_usd, latency_ms, outcome)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(id, request.workspaceId(), request.projectId(), request.agentId(), provider.id(), model,
                    in, out, cacheWrite, cacheRead, cost, latency, outcome).update();
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("provider", provider.name());
            payload.put("inputTokens", in);
            payload.put("outputTokens", out);
            payload.put("cacheWriteTokens", cacheWrite);
            payload.put("cacheReadTokens", cacheRead);
            payload.put("costUsd", cost == null ? null : cost.toPlainString());
            payload.put("latencyMs", latency);
            payload.put("outcome", outcome);
            if (request.agentId() != null) payload.put("agentId", request.agentId());
            if (error != null) payload.put("error", error);
            Actor actor = request.agentId() == null ? Actor.system() : Actor.agent(request.agentId());
            events.publish(request.workspaceId(), request.projectId(), "model.call_completed", actor,
                    new Subject("usage", id), payload);
            if (before != null && cost != null && cost.signum() > 0) {
                emitThresholds(request, before, budgets.spend(request.projectId()));
            }
        });
    }

    private void emitThresholds(ModelRequest request, BudgetService.Spend before, BudgetService.Spend after) {
        BudgetService.Budget budget = budgets.get(request.projectId());
        for (BigDecimal t : THRESHOLDS) {
            check(request, "daily", budget.dailyLimitUsd(), before.todayUsd(), after.todayUsd(), t);
            check(request, "total", budget.totalLimitUsd(), before.totalUsd(), after.totalUsd(), t);
        }
    }

    private void check(ModelRequest request, String limitName, BigDecimal limit, BigDecimal before, BigDecimal after,
                       BigDecimal threshold) {
        if (limit == null || limit.signum() <= 0) return;
        BigDecimal mark = limit.multiply(threshold);
        if (before.compareTo(mark) < 0 && after.compareTo(mark) >= 0) {
            events.publish(request.workspaceId(), request.projectId(), "budget.threshold_reached", Actor.system(),
                    new Subject("project", request.projectId()),
                    Map.of("limit", limitName, "threshold", threshold.movePointRight(2).intValue() + "%",
                            "limitUsd", limit.toPlainString(), "spentUsd", after.toPlainString()));
        }
    }

    /** Minimal real call used by "Test connection" in Settings. */
    public ModelResult test(String workspaceId, String providerId, String model) {
        return complete(new ModelRequest(workspaceId, null, null, providerId, model, null,
                List.of(new ChatTurn(ChatTurn.Role.USER, "Reply with the single word: OK")), 16, "low"));
    }
}

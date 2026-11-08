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
        budgets.ensureWithinBudget(request.projectId());
        String apiKey = providers.apiKey(request.workspaceId(), provider.id());
        ModelAdapter adapter = providers.adapter(provider.kind());

        long start = System.nanoTime();
        AdapterResult result;
        try {
            result = adapter.call(provider.id(), provider.baseUrl(), apiKey, request);
        } catch (ModelCallException e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            record(request, provider, request.model(), 0, 0, null, latency, "ERROR", e.getMessage());
            throw e;
        }
        long latency = (System.nanoTime() - start) / 1_000_000;
        BigDecimal cost = pricing.find(request.workspaceId(), provider.kind(), result.servedModel())
                .map(p -> PricingService.cost(p, result.inputTokens(), result.outputTokens()))
                .orElse(null);
        record(request, provider, result.servedModel(), result.inputTokens(), result.outputTokens(), cost, latency,
                result.refused() ? "REFUSED" : "OK", null);
        return new ModelResult(result.text(), result.servedModel(), result.inputTokens(), result.outputTokens(),
                result.refused(), result.stopReason(), latency, cost);
    }

    private void record(ModelRequest request, Provider provider, String model, long in, long out, BigDecimal cost,
                        long latency, String outcome, String error) {
        tx.executeWithoutResult(status -> {
            BudgetService.Spend before = request.projectId() == null ? null : budgets.spend(request.projectId());
            String id = Ids.newId();
            jdbc.sql("""
                    INSERT INTO usage_records (id, workspace_id, project_id, agent_id, provider_id, model, input_tokens,
                                               output_tokens, cost_usd, latency_ms, outcome)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(id, request.workspaceId(), request.projectId(), request.agentId(), provider.id(), model,
                    in, out, cost, latency, outcome).update();
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("provider", provider.name());
            payload.put("inputTokens", in);
            payload.put("outputTokens", out);
            payload.put("costUsd", cost == null ? null : cost.toPlainString());
            payload.put("latencyMs", latency);
            payload.put("outcome", outcome);
            if (request.agentId() != null) payload.put("agentId", request.agentId());
            if (error != null) payload.put("error", error);
            Actor actor = request.agentId() == null ? Actor.system() : Actor.agent(request.agentId());
            events.publish(request.workspaceId(), request.projectId(), "model.call_completed", actor,
                    new Subject("usage", id), payload);
            if (before != null && cost != null) {
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

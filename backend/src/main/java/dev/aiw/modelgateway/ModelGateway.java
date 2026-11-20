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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single entry point for model calls: budget check, provider resolution, the call itself,
 * then usage, cost and a model.call_completed event recorded in one transaction.
 * Failed calls are recorded too (outcome ERROR, zero tokens) so the activity is never hidden.
 */
@Service
public class ModelGateway {
    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);
    private static final BigDecimal[] THRESHOLDS = {new BigDecimal("0.8"), BigDecimal.ONE};
    /** Rate-limited calls are retried this many times, waiting what the provider asks (small plans: Groq, OpenRouter). */
    private static final int RATE_LIMIT_RETRIES = 4;
    private static final long MAX_WAIT_SECONDS = 60;
    /** Models a call may move through when its own is out of quota, before it waits. */
    private static final int MAX_HOPS = 3;
    /** Below this, a request shrunk to fit the provider's per-minute limit could not produce a useful answer. */
    private static final long MIN_OUTPUT_TOKENS = 512;
    private static final Pattern LIMIT = Pattern.compile("Limit (\\d+),(?: Used \\d+,)? Requested (\\d+)");
    /** A model's own output ceiling ("max_tokens must be less than or equal to `512`"). */
    private static final Pattern MODEL_MAX = Pattern.compile("max_tokens`? must be less than or equal to `?(\\d+)");
    /** OpenRouter credits: "You requested up to 8000 tokens, but can only afford 2395". */
    private static final Pattern AFFORD = Pattern.compile("can only afford (\\d+)");
    private static final Pattern TRY_AGAIN = Pattern.compile("try again in (?:(\\d+)h)?(?:(\\d+)m)?([\\d.]+)(ms|s)");

    /** Output cap learned per provider and model from "request too large" answers (output tokens per minute). */
    private final Map<String, Long> outputCaps = new ConcurrentHashMap<>();
    /** Tokens per minute allowed per provider and model, learned from "request too large (TPM)" answers. */
    private final Map<String, Long> perMinute = new ConcurrentHashMap<>();

    private final ProviderService providers;
    private final PricingService pricing;
    private final BudgetService budgets;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final ModelRouter router;

    ModelGateway(ProviderService providers, PricingService pricing, BudgetService budgets, EventPublisher events,
                 TransactionTemplate tx, org.springframework.jdbc.core.simple.JdbcClient jdbc, ModelRouter router) {
        this.router = router;
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
        // A model out of quota (or a provider out of credits) does not stop the work: the call moves to the best
        // other model for the same kind of work, chosen by the router (docs/15). It comes back once the quota resets.
        ModelProfiles.Need need = request.effectiveNeed();
        double primary = ModelProfiles.profile(request.model()).score(need);
        double floor = need == ModelProfiles.Need.LIGHT ? 3 : Math.max(Math.min(ModelRouter.FLOOR, primary), primary - 3);
        java.util.Set<String> tried = new java.util.HashSet<>();
        ModelCallException last = null;
        for (int hop = 0; ; hop++) {
            java.util.Optional<String> away = router.unavailable(provider.id(), request.model());
            if (away.isEmpty()) {
                try {
                    return completeOn(provider, request);
                } catch (ModelCallException e) {
                    java.time.Duration out = unavailableFor(e);
                    if (out == null) throw e;
                    boolean wholeProvider = e.statusCode() == 401 || e.statusCode() == 402 || e.statusCode() == 403;
                    if (!out.isZero()) {
                        router.markExhausted(provider.id(), wholeProvider ? null : request.model(), out, reasonOf(e));
                    }
                    last = e;
                }
            }
            tried.add(provider.id() + "|" + request.model());
            if (hop >= MAX_HOPS) break;
            long context = request.promptTokensEstimate() + Math.min(request.maxTokens(), 4_096);
            var ranking = router.ranking(request.workspaceId(), need, (p, m) -> tried.contains(p + "|" + m), context, null, floor);
            if (ranking.isEmpty()) {
                log.info("{} {} unavailable: no other model fits {} work (floor {}, context {} tokens, candidates {}; best below the floor: {})",
                        provider.name(), request.model(), need, floor, context, router.candidates(request.workspaceId()).stream()
                                .collect(java.util.stream.Collectors.groupingBy(c -> c.provider().name()
                                        + (router.unavailable(c.provider().id(), c.info().id()).isPresent() ? " (out)" : ""),
                                        java.util.stream.Collectors.counting())),
                        router.ranking(request.workspaceId(), need, (p, m) -> tried.contains(p + "|" + m), context, null, 0)
                                .stream().limit(4).map(c -> String.format(java.util.Locale.ROOT, "%s %.1f", c.model(), c.score())).toList());
                break;
            }
            ModelRouter.Choice to = ranking.getFirst();
            String why = last != null ? reasonOf(last) : away.orElse("out of quota");
            log.info("{} {} unavailable ({}): moved to {} {} [{}]; next: {}", provider.name(), request.model(), why,
                    to.providerName(), to.model(), to.reason(), ranking.stream().skip(1).limit(3)
                            .map(c -> String.format(java.util.Locale.ROOT, "%s %.1f", c.model(), c.score())).toList());
            Provider from = provider;
            ModelRequest fromReq = request;
            tx.executeWithoutResult(st -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("fromProvider", from.name());
                payload.put("fromModel", fromReq.model());
                payload.put("toProvider", to.providerName());
                payload.put("toModel", to.model());
                payload.put("reason", why);
                payload.put("choice", to.reason());
                if (fromReq.agentId() != null) payload.put("agentId", fromReq.agentId());
                events.publish(fromReq.workspaceId(), fromReq.projectId(), "model.rerouted",
                        fromReq.agentId() == null ? Actor.system() : Actor.agent(fromReq.agentId()),
                        fromReq.agentId() == null ? new Subject("provider", from.id()) : new Subject("agent", fromReq.agentId()),
                        payload);
            });
            provider = providers.find(request.workspaceId(), to.providerId()).orElseThrow();
            request = request.withProvider(to.providerId(), to.model());
        }
        if (last != null) throw last;
        // The bound model is known to be out and nothing else fits: the job waits, as before routing existed.
        long wait = Math.max(60, router.secondsUntilAvailable(provider.id(), request.model()));
        throw new ModelCallException(provider.name() + " " + request.model() + " is unavailable ("
                + router.unavailable(provider.id(), request.model()).orElse("out of quota")
                + ") and no other configured model fits this work: the work waits " + (wait >= 120 ? (wait + 59) / 60 + " min" : wait + " s")
                + " and resumes by itself.", 429, null, wait);
    }

    /**
     * How long a failed call's model (or provider: credits, key) is out of service. ZERO: move this call only (it is
     * too large for the model's per-minute limit); null: not a reason to move (a bad request, a network error).
     */
    static java.time.Duration unavailableFor(ModelCallException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        int status = e.statusCode();
        if (status == 429 && e.retryAfterSeconds() > 0) return java.time.Duration.ofSeconds(e.retryAfterSeconds());
        if (status == 402 || status == 401 || status == 403) return java.time.Duration.ofHours(1);
        if (status == 404 || (status == 400 && (m.contains("decommissioned") || m.contains("model_not_found")
                || m.contains("does not exist")))) {
            return java.time.Duration.ofHours(24);
        }
        if ((status == 413 || status == 429) && (m.contains("per minute") || m.contains("larger than"))) {
            return java.time.Duration.ZERO;
        }
        if (status == 429) return java.time.Duration.ofMinutes(1);
        return null;
    }

    private static String reasonOf(ModelCallException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e.statusCode() == 429 && m.contains("daily")) return "daily quota reached";
        if (e.statusCode() == 402) return "credits exhausted";
        if (e.statusCode() == 401 || e.statusCode() == 403) return "key refused";
        if (e.statusCode() == 404 || e.statusCode() == 400) return "model not available";
        if (m.contains("per minute")) return "request too large for its per-minute limit";
        return "rate limited";
    }

    private ModelResult completeOn(Provider provider, ModelRequest request) {
        Long cap = outputCaps.get(provider.id() + "|" + request.model());
        if (cap != null && request.maxTokens() > cap) request = request.withMaxTokens(cap);
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
                return new ModelResult(h[0], h[1], 0, 0, false, h[2], 0, BigDecimal.ZERO, 0, 0, true, req.projectId(), hash,
                        provider.id());
            }
        }

        budgets.ensureWithinBudget(req.projectId());
        String apiKey = providers.apiKey(req.workspaceId(), provider.id());
        ModelAdapter adapter = providers.adapter(provider.kind());

        long start = System.nanoTime();
        AdapterResult called;
        ModelRequest sent = req;
        long[] lastRequested = {-1};
        for (int attempt = 0; ; attempt++) {
            try {
                called = adapter.call(provider.id(), provider.baseUrl(), apiKey, sent);
                break;
            } catch (ModelCallException e) {
                ModelRequest retry;
                try {
                    retry = attempt < RATE_LIMIT_RETRIES ? afterRateLimit(provider, sent, e, attempt, lastRequested) : null;
                } catch (ModelCallException clear) {
                    long latency = (System.nanoTime() - start) / 1_000_000;
                    record(req, provider, req.model(), 0, 0, 0, 0, null, latency, "ERROR", clear.getMessage());
                    throw clear;
                }
                if (retry == null) {
                    long latency = (System.nanoTime() - start) / 1_000_000;
                    record(req, provider, req.model(), 0, 0, 0, 0, null, latency, "ERROR", e.getMessage());
                    throw e;
                }
                sent = retry;
            }
        }
        AdapterResult result = called;
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
                req.projectId(), hash, provider.id());
    }

    /**
     * What to do after a rate-limit answer (429/413) or no answer at all (network): the same request after the wait the provider asks, or a smaller
     * one when it is larger than the per-minute limit itself. Null: not a rate limit, or nothing can make it fit.
     */
    ModelRequest afterRateLimit(Provider provider, ModelRequest request, ModelCallException e, int attempt, long[] lastRequested) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String key = provider.id() + "|" + request.model();
        if (e.statusCode() == 0 && e.getCause() != null) {
            // No answer at all (timeout, connection reset): a network blip, waited out like a rate limit.
            // Status 0 without a cause is a configuration or protocol error: retrying cannot help.
            long wait = 5L << attempt; // 5, 10, 20, 40 s
            log.info("{} {}: provider unreachable, retrying in {} s (attempt {})", provider.name(), request.model(), wait, attempt + 1);
            sleep(wait * 1000);
            return request;
        }
        Matcher modelMax = MODEL_MAX.matcher(message);
        if (e.statusCode() == 400 && modelMax.find()) {
            long max = Long.parseLong(modelMax.group(1));
            if (max >= request.maxTokens() || max < 1) return null;
            outputCaps.put(key, max);
            log.info("{} {}: the model allows max_tokens {} at most; remembered", provider.name(), request.model(), max);
            return request.withMaxTokens(max);
        }
        if (e.statusCode() == 402) {
            Matcher afford = AFFORD.matcher(message);
            if (afford.find()) {
                long max = Long.parseLong(afford.group(1)) * 9 / 10;
                if (max < MIN_OUTPUT_TOKENS || max >= request.maxTokens()) {
                    throw new ModelCallException("The " + provider.name() + " credits are nearly exhausted (room for "
                            + afford.group(1) + " output tokens). Add credits or choose another provider.", 402, e);
                }
                log.info("{} {}: credits allow {} output tokens; retried smaller", provider.name(), request.model(), max);
                return request.withMaxTokens(max);
            }
            if (!message.contains("in_flight")) return null;
            // Credits held by requests still in flight: wait for them like a rate limit.
        } else if (e.statusCode() != 429 && e.statusCode() != 413) {
            return null;
        }
        Matcher limit = LIMIT.matcher(message);
        boolean tooLarge = message.contains("Request too large") || message.contains("exceed the enforced limit");
        if (tooLarge && limit.find()) {
            long allowed = Long.parseLong(limit.group(1)), requested = Long.parseLong(limit.group(2));
            boolean outputLimit = message.contains("output tokens");
            if (!outputLimit) {
                perMinute.put(key, allowed);
                // Still the same size after a smaller max_tokens: the prompt itself is above the per-minute limit.
                if (requested == lastRequested[0]) {
                    throw new ModelCallException("The prompt (about " + requested + " tokens) is larger than what "
                            + provider.name() + " allows per minute for " + request.model() + " (" + allowed
                            + "). The next attempt sends a shorter one; a model with higher limits avoids this.", e.statusCode(), e);
                }
                lastRequested[0] = requested;
            }
            long max = outputLimit ? allowed : request.maxTokens() - (requested - allowed) - 64;
            if (max < MIN_OUTPUT_TOKENS || max >= request.maxTokens()) {
                throw new ModelCallException("This request needs more tokens per minute than " + provider.name()
                        + " allows for " + request.model() + " (limit " + allowed + ", needed " + requested
                        + "). Choose a model with higher limits or raise the plan.", e.statusCode(), e);
            }
            if (outputLimit) outputCaps.put(key, max);
            log.info("{} {}: request above the per-minute limit, retried with max_tokens {}", provider.name(), request.model(), max);
            return request.withMaxTokens(max);
        }
        if (tooLarge) return null;
        long wait = e.retryAfterSeconds();
        Matcher again = TRY_AGAIN.matcher(message);
        if (wait < 0 && again.find()) {
            double v = Double.parseDouble(again.group(3));
            double secs = "ms".equals(again.group(4)) ? v / 1000 : v;
            wait = (long) Math.ceil(secs + (again.group(2) == null ? 0 : Long.parseLong(again.group(2)) * 60)
                    + (again.group(1) == null ? 0 : Long.parseLong(again.group(1)) * 3600));
        }
        if (wait < 0) wait = 5L << attempt; // 5, 10, 20, 40 s
        if (wait > MAX_WAIT_SECONDS) {
            // Too long to hold the call (a daily quota, say): the job waits in the queue and resumes by itself.
            String kind = message.contains("per day") ? "daily limit" : "rate limit";
            throw new ModelCallException(provider.name() + " " + kind + " reached for " + request.model()
                    + ": the work waits " + (wait >= 120 ? (wait + 59) / 60 + " min" : wait + " s") + " and resumes by itself."
                    + " A model on another provider avoids the wait.", 429, e, wait);
        }
        log.info("{} {}: rate limited, waiting {} s (attempt {})", provider.name(), request.model(), wait, attempt + 1);
        sleep(wait * 1000 + 250);
        return request;
    }

    /** Output ceiling learned for this provider and model, if any (small plans: Groq, OpenRouter free tiers). */
    public java.util.Optional<Long> outputCap(String providerId, String model) {
        return java.util.Optional.ofNullable(outputCaps.get(providerId + "|" + model));
    }

    /** Tokens per minute the provider allows for this model, when it said so: prompts must fit well under it. */
    public java.util.Optional<Long> tokensPerMinute(String providerId, String model) {
        return java.util.Optional.ofNullable(perMinute.get(providerId + "|" + model));
    }

    /** Overridable so tests do not wait. */
    void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("Interrupted while waiting for the provider's rate limit", 429, ie);
        }
    }

    /** Whether the caller could use the answer (parsed, valid): the router learns which models break their replies. */
    public void noteReply(String workspaceId, ModelResult result, boolean usable) {
        if (result == null || result.reused()) return;
        router.noteReply(workspaceId, result.providerId(), result.servedModel(), usable);
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

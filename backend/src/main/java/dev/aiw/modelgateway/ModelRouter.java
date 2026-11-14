package dev.aiw.modelgateway;

import dev.aiw.modelgateway.ModelProfiles.Need;
import dev.aiw.modelgateway.ModelProfiles.Profile;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Picks the model that fits a kind of work among every model the workspace's providers offer (docs/15). The code
 * decides, without a model call: priors from {@link ModelProfiles}, adjusted by what the platform observed (replies it
 * could not use), the context window, the load already placed on a model (quotas are per model on small plans) and
 * models currently out of quota. Used to assign recruits their model and to move a call off an exhausted model.
 */
@Service
public class ModelRouter {
    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);
    private static final Duration CATALOG_TTL = Duration.ofHours(6);
    private static final Duration CATALOG_RETRY = Duration.ofMinutes(10);
    /** Agents already on a model lower its score: one daily quota is shared by all of them. */
    static final double LOAD_PENALTY = 0.75;
    /** Below this, a model does more harm than waiting for a better one (broken JSON, wrong code). */
    static final double FLOOR = 4.5;
    /** Unusable replies weigh once there are this many observations. */
    private static final long MIN_OBSERVATIONS = 5;

    /** A model a provider offers, with its profile. */
    public record Candidate(Provider provider, ModelInfo info, Profile profile) {
        String key() {
            return provider.id() + "|" + info.id();
        }
    }

    /** The chosen model and why, in words for the CTO. */
    public record Choice(String providerId, String providerName, String model, double score, String reason) {}

    private record Cached(Instant at, List<ModelInfo> models) {}

    private final ProviderService providers;
    private final JdbcClient jdbc;
    private final PricingService pricing;
    private final Map<String, Cached> catalog = new ConcurrentHashMap<>();
    /** provider|model, or provider|* for the whole provider (credits, key): until when it cannot serve calls. */
    private final Map<String, Instant> exhausted = new ConcurrentHashMap<>();
    private final Map<String, String> exhaustedWhy = new ConcurrentHashMap<>();

    ModelRouter(ProviderService providers, JdbcClient jdbc, PricingService pricing) {
        this.providers = providers;
        this.jdbc = jdbc;
        this.pricing = pricing;
    }

    // ------------------------------------------------------------------ availability

    public void markExhausted(String providerId, String model, Duration forHowLong, String why) {
        String key = providerId + "|" + (model == null ? "*" : model);
        exhausted.put(key, Instant.now().plus(forHowLong));
        exhaustedWhy.put(key, why);
    }

    /** Why this model (or its whole provider) cannot serve calls right now, or empty. */
    public Optional<String> unavailable(String providerId, String model) {
        for (String key : List.of(providerId + "|" + model, providerId + "|*")) {
            Instant until = exhausted.get(key);
            if (until == null) continue;
            if (until.isBefore(Instant.now())) {
                exhausted.remove(key);
                exhaustedWhy.remove(key);
                continue;
            }
            return Optional.of(exhaustedWhy.getOrDefault(key, "out of quota"));
        }
        return Optional.empty();
    }

    /** Seconds until this model (or its provider) serves calls again; 0 when it does now. */
    public long secondsUntilAvailable(String providerId, String model) {
        long out = 0;
        for (String key : List.of(providerId + "|" + model, providerId + "|*")) {
            Instant until = exhausted.get(key);
            if (until != null) out = Math.max(out, Duration.between(Instant.now(), until).toSeconds());
        }
        return out;
    }

    // ------------------------------------------------------------------ catalog

    /** Every chat model the workspace's providers offer (cached), plus the ones already configured or bound. */
    public List<Candidate> candidates(String workspaceId) {
        List<Candidate> out = new ArrayList<>();
        for (Provider p : providers.list(workspaceId)) {
            if (!p.hasKey() && !keyless(p)) continue;
            Map<String, ModelInfo> models = new LinkedHashMap<>();
            for (ModelInfo m : models(workspaceId, p)) models.put(m.id(), m);
            List<String> known = new ArrayList<>();
            if (p.defaultModel() != null) known.add(p.defaultModel());
            if (p.economyModel() != null) known.add(p.economyModel());
            known.addAll(jdbc.sql("SELECT DISTINCT model FROM agents WHERE provider_id = ? AND model IS NOT NULL AND status <> 'DISMISSED'")
                    .param(p.id()).query(String.class).list());
            for (String id : known) models.putIfAbsent(id, new ModelInfo(id, null));
            for (ModelInfo m : models.values()) {
                Profile profile = ModelProfiles.profile(m.id());
                if (!profile.chat()) continue;
                if (m.blendedUsdPerMtok() == null) {
                    // Groq does not list prices: the platform's price list (Settings → Pricing) may know them.
                    var price = pricing.find(workspaceId, p.kind(), m.id());
                    if (price.isPresent()) {
                        m = m.withPrice(price.get().inputUsdPerMtok().doubleValue(), price.get().outputUsdPerMtok().doubleValue());
                    }
                }
                out.add(new Candidate(p, m, profile));
            }
        }
        return out;
    }

    /** Local servers and custom APIs may run without a key. */
    private static boolean keyless(Provider p) {
        return p.preset() != null && ProviderPresets.find(p.preset()).map(pr -> !pr.keyRequired()).orElse(false);
    }

    private List<ModelInfo> models(String workspaceId, Provider p) {
        Cached c = catalog.get(p.id());
        if (c != null && c.at().isAfter(Instant.now().minus(c.models().isEmpty() ? CATALOG_RETRY : CATALOG_TTL))) {
            return c.models();
        }
        List<ModelInfo> fresh;
        try {
            fresh = providers.adapter(p.kind()).listModelInfo(p.id(), p.baseUrl(), providers.apiKey(workspaceId, p.id()));
        } catch (RuntimeException e) {
            log.info("Models of {} could not be listed: {}", p.name(), e.getMessage());
            fresh = c == null ? List.of() : c.models();
        }
        catalog.put(p.id(), new Cached(Instant.now(), fresh));
        return fresh;
    }

    /** Forgets a provider's model list (key changed, provider deleted). */
    public void evict(String providerId) {
        catalog.remove(providerId);
    }

    // ------------------------------------------------------------------ reputation

    /** Records whether a model's reply could be used (parsed, valid): the router learns which models break JSON. */
    public void noteReply(String workspaceId, String providerId, String model, boolean usable) {
        if (workspaceId == null || providerId == null || model == null) return;
        jdbc.sql("""
                INSERT INTO model_reputation (workspace_id, provider_id, model, usable, unusable) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, provider_id, model) DO UPDATE
                SET usable = model_reputation.usable + EXCLUDED.usable,
                    unusable = model_reputation.unusable + EXCLUDED.unusable, updated_at = now()
                """).params(workspaceId, providerId, model, usable ? 1 : 0, usable ? 0 : 1).update();
    }

    private Map<String, long[]> reputation(String workspaceId) {
        Map<String, long[]> out = new HashMap<>();
        jdbc.sql("SELECT provider_id, model, usable, unusable FROM model_reputation WHERE workspace_id = ?")
                .param(workspaceId).query(rs -> {
                    out.put(rs.getString(1) + "|" + rs.getString(2), new long[] {rs.getLong(3), rs.getLong(4)});
                });
        return out;
    }

    /** Agents per provider|model in the workspace: the load already on each model. */
    public Map<String, Long> load(String workspaceId, String exceptAgentId) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("""
                SELECT provider_id, model, count(*) FROM agents
                WHERE workspace_id = ? AND model IS NOT NULL AND status <> 'DISMISSED' AND id <> ?
                GROUP BY provider_id, model
                """).params(workspaceId, exceptAgentId == null ? "" : exceptAgentId)
                .query(rs -> {
                    out.put(rs.getString(1) + "|" + rs.getString(2), rs.getLong(3));
                });
        return out;
    }

    // ------------------------------------------------------------------ choice

    /**
     * The best model for {@code need}. {@code skip}: (providerId, model) pairs not to consider; {@code minContext}: tokens
     * the call needs; {@code load}: agents already per provider|model; {@code floor}: lowest acceptable score (0 = any).
     */
    public Optional<Choice> best(String workspaceId, Need need, BiPredicate<String, String> skip, long minContext,
                                 Map<String, Long> load, double floor) {
        return ranking(workspaceId, need, skip, minContext, load, floor).stream().findFirst();
    }

    /** Every acceptable model for {@code need}, best first. */
    public List<Choice> ranking(String workspaceId, Need need, BiPredicate<String, String> skip, long minContext,
                                Map<String, Long> load, double floor) {
        return rank(candidates(workspaceId), reputation(workspaceId), need, skip, minContext, load, floor);
    }

    /** Pure ranking, tested without providers. */
    List<Choice> rank(List<Candidate> candidates, Map<String, long[]> reputation, Need need,
                      BiPredicate<String, String> skip, long minContext, Map<String, Long> load, double floor) {
        List<Choice> out = new ArrayList<>();
        for (Candidate c : candidates) {
            String model = c.info().id();
            if (!c.profile().chat()) continue;
            if (skip != null && skip.test(c.provider().id(), model)) continue;
            if (unavailable(c.provider().id(), model).isPresent()) continue;
            Long window = c.info().contextWindow();
            if (window != null && minContext > 0 && window < minContext) continue;
            double score = c.profile().score(need);
            List<String> why = new ArrayList<>();
            why.add(String.format(Locale.ROOT, "%s %.1f/10 (%s)", label(need), c.profile().score(need), c.profile().basis()));
            long[] rep = reputation.get(c.key());
            if (rep != null && rep[0] + rep[1] >= MIN_OBSERVATIONS && rep[1] > 0) {
                double rate = (double) rep[1] / (rep[0] + rep[1]);
                score -= 5 * rate;
                why.add(String.format(Locale.ROOT, "%d%% unusable replies", Math.round(rate * 100)));
            }
            if (window != null && window < 32_000 && need != Need.LIGHT) {
                score -= 1;
                why.add((window / 1000) + "k context");
            }
            Double price = c.info().blendedUsdPerMtok();
            if (price != null && price > 1) {
                // Token sobriety: a model 60x the price must be much better to be chosen (Opus ≈ -3.5, Sonnet ≈ -2.2).
                score -= 0.6 * (Math.log(price) / Math.log(2));
                why.add(String.format(Locale.ROOT, "$%.2f per million tokens", price));
            }
            if (c.provider().isDefault() && model.equals(c.provider().defaultModel())) {
                score += 0.5;
                why.add("the workspace default");
            }
            long agents = load == null ? 0 : load.getOrDefault(c.key(), 0L);
            if (agents > 0) {
                score -= LOAD_PENALTY * agents;
                why.add(agents + (agents == 1 ? " agent" : " agents") + " already on it");
            }
            if (score < floor) continue;
            out.add(new Choice(c.provider().id(), c.provider().name(), model, score, String.join(", ", why)));
        }
        out.sort(Comparator.comparingDouble(Choice::score).reversed().thenComparing(Choice::model));
        return out;
    }

    private static String label(Need need) {
        return switch (need) {
            case CODING -> "coding";
            case REASONING -> "reasoning";
            case LIGHT -> "light work";
        };
    }
}

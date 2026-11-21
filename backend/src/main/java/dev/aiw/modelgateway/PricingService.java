package dev.aiw.modelgateway;

import dev.aiw.shared.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** USD per million tokens. A workspace price overrides the built-in list price for the same model. */
@Service
public class PricingService {
    public record Price(String providerKind, String model, BigDecimal inputUsdPerMtok, BigDecimal outputUsdPerMtok,
                        boolean builtin) {}

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private final JdbcClient jdbc;

    PricingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Price> list(String workspaceId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (provider_kind, model) provider_kind, model, input_usd_per_mtok,
                       output_usd_per_mtok, workspace_id IS NULL AS builtin
                FROM model_prices WHERE workspace_id = ? OR workspace_id IS NULL
                ORDER BY provider_kind, model, workspace_id NULLS LAST
                """).param(workspaceId)
                .query((rs, n) -> new Price(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBoolean(5)))
                .list();
    }

    /**
     * The price of a model: its exact id, else the same id without a provider prefix (openai/gpt-oss-120b) or a date
     * suffix (gpt-4.1-mini-2025-04-14, claude-x-20250101): providers report the dated snapshot they served.
     */
    public Optional<Price> find(String workspaceId, ProviderKind kind, String model) {
        if (model == null) return Optional.empty();
        List<Price> prices = list(workspaceId).stream().filter(p -> p.providerKind().equals(kind.name())).toList();
        for (String candidate : candidates(model)) {
            Optional<Price> hit = prices.stream().filter(p -> p.model().equalsIgnoreCase(candidate)).findFirst();
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    static List<String> candidates(String model) {
        String m = model.strip();
        String noPrefix = m.contains("/") ? m.substring(m.lastIndexOf('/') + 1) : m;
        String noDate = noPrefix.replaceFirst("-(\\d{4}-\\d{2}-\\d{2}|\\d{8})$", "");
        return List.of(m, noPrefix, noDate).stream().distinct().toList();
    }

    @Transactional
    public void upsert(String workspaceId, ProviderKind kind, String model, BigDecimal in, BigDecimal out) {
        int updated = jdbc.sql("""
                UPDATE model_prices SET input_usd_per_mtok = ?, output_usd_per_mtok = ?
                WHERE workspace_id = ? AND provider_kind = ? AND model = ?
                """).params(in, out, workspaceId, kind.name(), model).update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO model_prices (id, workspace_id, provider_kind, model, input_usd_per_mtok, output_usd_per_mtok)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(Ids.newId(), workspaceId, kind.name(), model, in, out).update();
        }
    }

    /**
     * Cost with the provider prompt cache: written tokens cost 1.25x the input price (Anthropic), read tokens
     * {@code readFactor} of it (0.1 on Anthropic; OpenAI-compatible providers vary, 0.5 is assumed).
     */
    public static BigDecimal cost(Price price, long inputTokens, long outputTokens, long cacheWrite, long cacheRead,
                                  BigDecimal readFactor) {
        BigDecimal input = BigDecimal.valueOf(inputTokens)
                .add(BigDecimal.valueOf(cacheWrite).multiply(new BigDecimal("1.25")))
                .add(BigDecimal.valueOf(cacheRead).multiply(readFactor));
        return price.inputUsdPerMtok().multiply(input)
                .add(price.outputUsdPerMtok().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    public static BigDecimal cost(Price price, long inputTokens, long outputTokens) {
        return price.inputUsdPerMtok().multiply(BigDecimal.valueOf(inputTokens))
                .add(price.outputUsdPerMtok().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}

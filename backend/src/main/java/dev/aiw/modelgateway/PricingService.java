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

    public Optional<Price> find(String workspaceId, ProviderKind kind, String model) {
        return list(workspaceId).stream()
                .filter(p -> p.providerKind().equals(kind.name()) && p.model().equals(model))
                .findFirst();
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

    public static BigDecimal cost(Price price, long inputTokens, long outputTokens) {
        return price.inputUsdPerMtok().multiply(BigDecimal.valueOf(inputTokens))
                .add(price.outputUsdPerMtok().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}

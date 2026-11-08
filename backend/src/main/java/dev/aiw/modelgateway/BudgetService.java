package dev.aiw.modelgateway;

import dev.aiw.shared.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-project spending limits on model usage. Days are UTC. Calls to unpriced models
 * cannot be counted in USD; they are reported separately (unpricedCalls).
 */
@Service
public class BudgetService {
    public record Budget(BigDecimal dailyLimitUsd, BigDecimal totalLimitUsd) {}

    public record Spend(BigDecimal todayUsd, BigDecimal totalUsd, long callsToday, long callsTotal,
                        long unpricedCalls, long inputTokens, long outputTokens) {}

    private final JdbcClient jdbc;

    BudgetService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Budget get(String projectId) {
        return jdbc.sql("SELECT daily_limit_usd, total_limit_usd FROM budgets WHERE project_id = ?")
                .param(projectId)
                .query((rs, n) -> new Budget(rs.getBigDecimal(1), rs.getBigDecimal(2)))
                .optional().orElse(new Budget(null, null));
    }

    @Transactional
    public void set(String workspaceId, String projectId, BigDecimal daily, BigDecimal total) {
        jdbc.sql("""
                INSERT INTO budgets (project_id, workspace_id, daily_limit_usd, total_limit_usd) VALUES (?, ?, ?, ?)
                ON CONFLICT (project_id) DO UPDATE
                SET daily_limit_usd = excluded.daily_limit_usd, total_limit_usd = excluded.total_limit_usd,
                    updated_at = now()
                """).params(projectId, workspaceId, daily, total).update();
    }

    public Spend spend(String projectId) {
        return jdbc.sql("""
                SELECT coalesce(sum(cost_usd) FILTER (WHERE occurred_at >= :dayStart), 0),
                       coalesce(sum(cost_usd), 0),
                       count(*) FILTER (WHERE occurred_at >= :dayStart),
                       count(*),
                       count(*) FILTER (WHERE cost_usd IS NULL),
                       coalesce(sum(input_tokens), 0),
                       coalesce(sum(output_tokens), 0)
                FROM usage_records WHERE project_id = :project
                """)
                .param("dayStart", java.sql.Timestamp.from(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()))
                .param("project", projectId)
                .query((rs, n) -> new Spend(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7)))
                .single();
    }

    /** Throws when the project has reached a limit. Calls without a project are not budgeted. */
    void ensureWithinBudget(String projectId) {
        if (projectId == null) return;
        Budget budget = get(projectId);
        if (budget.dailyLimitUsd() == null && budget.totalLimitUsd() == null) return;
        Spend spend = spend(projectId);
        if (budget.dailyLimitUsd() != null && spend.todayUsd().compareTo(budget.dailyLimitUsd()) >= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "budget_exceeded",
                    "Daily budget of $" + budget.dailyLimitUsd() + " reached for this project");
        }
        if (budget.totalLimitUsd() != null && spend.totalUsd().compareTo(budget.totalLimitUsd()) >= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "budget_exceeded",
                    "Total budget of $" + budget.totalLimitUsd() + " reached for this project");
        }
    }
}

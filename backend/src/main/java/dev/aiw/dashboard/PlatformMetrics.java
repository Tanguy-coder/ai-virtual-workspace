package dev.aiw.dashboard;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The platform's own metrics for Prometheus (docs/18), read from the recorded state: model tokens and cost, agent
 * work, queues, findings, deployments. Each scrape runs a few aggregate queries, cached for 10 seconds.
 */
@Component
class PlatformMetrics implements MeterBinder {
    private static final long TTL_MS = 10_000;
    private final JdbcClient jdbc;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    PlatformMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Cached(long at, Map<String, Double> values) {}

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String direction : List.of("input", "output", "cache_read")) {
            FunctionCounter.builder("aiw.model.tokens", this, m -> m.value("usage", () -> usage(), direction))
                    .tag("direction", direction).description("Model tokens recorded by the gateway").register(registry);
        }
        FunctionCounter.builder("aiw.model.cost", this, m -> m.value("usage", () -> usage(), "cost"))
                .baseUnit("usd").description("Model cost recorded by the gateway").register(registry);
        FunctionCounter.builder("aiw.model.calls", this, m -> m.value("usage", () -> usage(), "calls"))
                .description("Model calls").register(registry);
        for (String s : List.of("RUNNING", "PAUSED", "SUBMITTED", "BLOCKED")) {
            Gauge.builder("aiw.runs", this, m -> m.value("runs", () -> grouped("SELECT status, count(*) FROM task_runs GROUP BY status"), s))
                    .tag("status", s.toLowerCase()).description("Task runs by status").register(registry);
        }
        Gauge.builder("aiw.jobs.queued", this, m -> m.value("jobs", () -> grouped("SELECT 'queued', count(*) FROM agent_jobs WHERE status = 'QUEUED'"), "queued"))
                .description("Agent jobs waiting").register(registry);
        for (String sev : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")) {
            Gauge.builder("aiw.findings.open", this, m -> m.value("findings",
                    () -> grouped("SELECT severity, count(*) FROM security_findings WHERE status IN ('OPEN', 'FIX_TASK') GROUP BY severity"), sev))
                    .tag("severity", sev.toLowerCase()).description("Open security findings").register(registry);
        }
        for (String s : List.of("HEALTHY", "UNHEALTHY", "DEPLOYING", "STOPPED")) {
            Gauge.builder("aiw.environments", this, m -> m.value("envs", () -> grouped("SELECT status, count(*) FROM environments GROUP BY status"), s))
                    .tag("status", s.toLowerCase()).description("Test environments by status").register(registry);
        }
        FunctionCounter.builder("aiw.tasks.done", this, m -> m.value("tasks", () -> grouped("SELECT status, count(*) FROM tasks GROUP BY status"), "DONE"))
                .description("Tasks done (validated and merged)").register(registry);
    }

    private Map<String, Double> usage() {
        return jdbc.sql("""
                SELECT coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0), coalesce(sum(cache_read_tokens), 0),
                       coalesce(sum(cost_usd), 0), count(*) FROM usage_records
                """).query((rs, i) -> Map.of("input", rs.getDouble(1), "output", rs.getDouble(2), "cache_read", rs.getDouble(3),
                "cost", rs.getDouble(4), "calls", rs.getDouble(5))).single();
    }

    private Map<String, Double> grouped(String sql) {
        Map<String, Double> out = new java.util.HashMap<>();
        jdbc.sql(sql).query((java.sql.ResultSet rs) -> {
            out.put(rs.getString(1), rs.getDouble(2));
        });
        return out;
    }

    private double value(String key, Supplier<Map<String, Double>> load, String name) {
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c == null || now - c.at() > TTL_MS) {
            try {
                c = new Cached(now, load.get());
            } catch (RuntimeException e) {
                return Double.NaN; // the database is unreachable: no value rather than a wrong one
            }
            cache.put(key, c);
        }
        return c.values().getOrDefault(name, 0.0);
    }
}

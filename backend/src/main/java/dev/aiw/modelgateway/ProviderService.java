package dev.aiw.modelgateway;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.governance.SecretVault;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderService {
    /** Model bound by default to new agents when an Anthropic provider exists. */
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-opus-5-5";

    private final JdbcClient jdbc;
    private final SecretVault vault;
    private final EventPublisher events;
    private final AuditLog audit;
    private final ApplicationEventPublisher appEvents;
    private final List<ModelAdapter> adapters;

    ProviderService(JdbcClient jdbc, SecretVault vault, EventPublisher events, AuditLog audit,
                    ApplicationEventPublisher appEvents, List<ModelAdapter> adapters) {
        this.jdbc = jdbc;
        this.vault = vault;
        this.events = events;
        this.audit = audit;
        this.appEvents = appEvents;
        this.adapters = adapters;
    }

    public List<Provider> list(String workspaceId) {
        return jdbc.sql("SELECT * FROM providers WHERE workspace_id = ? ORDER BY created_at")
                .param(workspaceId).query(this::map).list();
    }

    public Optional<Provider> find(String workspaceId, String providerId) {
        return jdbc.sql("SELECT * FROM providers WHERE id = ? AND workspace_id = ?")
                .params(providerId, workspaceId).query(this::map).optional();
    }

    /** First Anthropic provider of the workspace, used for default agent bindings. */
    public Optional<Provider> defaultProvider(String workspaceId) {
        return list(workspaceId).stream().filter(p -> p.kind() == ProviderKind.ANTHROPIC).findFirst();
    }

    @Transactional
    public Provider create(String workspaceId, String userId, ProviderKind kind, String name, String baseUrl,
                          String apiKey) {
        if (kind == ProviderKind.ANTHROPIC && (apiKey == null || apiKey.isBlank())) {
            throw ApiException.badRequest("api_key_required", "An API key is required for Anthropic");
        }
        if (kind == ProviderKind.OPENAI_COMPATIBLE) {
            validateBaseUrl(baseUrl);
        }
        String id = Ids.newId();
        String secretId = apiKey == null || apiKey.isBlank()
                ? null
                : vault.store(workspaceId, null, "provider:" + id, apiKey.trim());
        jdbc.sql("INSERT INTO providers (id, workspace_id, kind, name, base_url, secret_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, workspaceId, kind.name(), name.trim(), blankToNull(baseUrl), secretId)
                .update();
        Actor actor = Actor.human(userId);
        events.publish(workspaceId, null, "provider.created", actor, new Subject("provider", id),
                Map.of("kind", kind.name(), "name", name.trim()));
        audit.record(workspaceId, actor, "provider.create", "provider", id, AuditLog.Decision.RECORDED,
                Map.of("kind", kind.name(), "name", name.trim()));
        appEvents.publishEvent(new ProviderCreated(workspaceId, id, kind,
                kind == ProviderKind.ANTHROPIC ? DEFAULT_ANTHROPIC_MODEL : null));
        return find(workspaceId, id).orElseThrow();
    }

    @Transactional
    public void delete(String workspaceId, String userId, String providerId) {
        Provider provider = find(workspaceId, providerId).orElseThrow(() -> ApiException.notFound("Provider"));
        long used = jdbc.sql("SELECT count(*) FROM usage_records WHERE provider_id = ?").param(providerId)
                .query(Long.class).single();
        if (used > 0) {
            throw ApiException.conflict("provider_in_use",
                    "This provider has recorded usage and cannot be deleted; its history would be lost");
        }
        String secretId = jdbc.sql("SELECT secret_id FROM providers WHERE id = ?").param(providerId)
                .query(String.class).optional().orElse(null);
        jdbc.sql("DELETE FROM providers WHERE id = ?").param(providerId).update();
        if (secretId != null) vault.delete(workspaceId, secretId);
        adapters.forEach(a -> a.evict(providerId));
        Actor actor = Actor.human(userId);
        events.publish(workspaceId, null, "provider.deleted", actor, new Subject("provider", providerId),
                Map.of("name", provider.name()));
        audit.record(workspaceId, actor, "provider.delete", "provider", providerId, AuditLog.Decision.RECORDED,
                Map.of("name", provider.name()));
    }

    /** Server-side only: the decrypted API key, or null when the provider has none. */
    String apiKey(String workspaceId, String providerId) {
        String secretId = jdbc.sql("SELECT secret_id FROM providers WHERE id = ? AND workspace_id = ?")
                .params(providerId, workspaceId).query(String.class).optional().orElse(null);
        return secretId == null ? null : vault.reveal(workspaceId, secretId);
    }

    ModelAdapter adapter(ProviderKind kind) {
        return adapters.stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
    }

    private static void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw ApiException.badRequest("base_url_required", "A base URL is required for an OpenAI-compatible provider");
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_base_url", "Base URL must be an absolute http(s) URL");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Provider map(ResultSet rs, int row) throws SQLException {
        return new Provider(rs.getString("id"), rs.getString("workspace_id"), ProviderKind.valueOf(rs.getString("kind")),
                rs.getString("name"), rs.getString("base_url"), rs.getString("secret_id") != null,
                rs.getTimestamp("created_at").toInstant());
    }
}

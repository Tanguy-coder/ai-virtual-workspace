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
    /** Default model for light calls on Anthropic providers (docs/09). */
    public static final String DEFAULT_ANTHROPIC_ECONOMY_MODEL = "claude-haiku-5-5";

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

    /** The provider new agents are bound to, with its default model. */
    public Optional<Provider> defaultProvider(String workspaceId) {
        return list(workspaceId).stream().filter(Provider::isDefault).findFirst();
    }

    /** Creates a provider from a preset (or a custom OpenAI-compatible API). The first one becomes the default. */
    @Transactional
    public Provider create(String workspaceId, String userId, ProviderKind kind, String name, String baseUrl,
                           String apiKey, String presetId, String defaultModel, String economyModel) {
        ProviderPresets.Preset preset = presetId == null ? null : ProviderPresets.find(presetId)
                .orElseThrow(() -> ApiException.badRequest("unknown_preset", "Unknown provider preset"));
        if (preset != null) {
            kind = preset.kind();
            if (blankToNull(baseUrl) == null) baseUrl = preset.baseUrl();
            if (blankToNull(defaultModel) == null) defaultModel = preset.defaultModel();
            if (blankToNull(economyModel) == null) economyModel = preset.economyModel();
            if (preset.keyRequired() && (apiKey == null || apiKey.isBlank())) {
                throw ApiException.badRequest("api_key_required", "An API key is required for " + preset.label());
            }
        }
        if (kind == null) throw ApiException.badRequest("kind_required", "Choose a provider");
        if (kind == ProviderKind.ANTHROPIC && (apiKey == null || apiKey.isBlank())) {
            throw ApiException.badRequest("api_key_required", "An API key is required for Anthropic");
        }
        if (kind == ProviderKind.OPENAI_COMPATIBLE) {
            validateBaseUrl(baseUrl);
        }
        if (kind == ProviderKind.ANTHROPIC && preset == null) {
            if (blankToNull(defaultModel) == null) defaultModel = DEFAULT_ANTHROPIC_MODEL;
            if (blankToNull(economyModel) == null) economyModel = DEFAULT_ANTHROPIC_ECONOMY_MODEL;
        }
        String id = Ids.newId();
        String secretId = apiKey == null || apiKey.isBlank()
                ? null
                : vault.store(workspaceId, null, "provider:" + id, apiKey.trim());
        boolean first = defaultProvider(workspaceId).isEmpty();
        jdbc.sql("""
                INSERT INTO providers (id, workspace_id, kind, name, base_url, secret_id, economy_model, default_model,
                                       is_default, preset)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, workspaceId, kind.name(), name.trim(), blankToNull(baseUrl), secretId,
                        blankToNull(economyModel), blankToNull(defaultModel), first, preset == null ? null : preset.id())
                .update();
        Actor actor = Actor.human(userId);
        events.publish(workspaceId, null, "provider.created", actor, new Subject("provider", id),
                Map.of("kind", kind.name(), "name", name.trim()));
        audit.record(workspaceId, actor, "provider.create", "provider", id, AuditLog.Decision.RECORDED,
                Map.of("kind", kind.name(), "name", name.trim()));
        if (first && blankToNull(defaultModel) != null) {
            appEvents.publishEvent(new DefaultModelChanged(workspaceId, id, defaultModel.trim()));
        }
        return find(workspaceId, id).orElseThrow();
    }

    /** Sets the model bound to new agents and the model for light calls (blank clears the latter). */
    @Transactional
    public Provider setModels(String workspaceId, String userId, String providerId, String defaultModel, String economyModel) {
        Provider p = find(workspaceId, providerId).orElseThrow(() -> ApiException.notFound("Provider"));
        jdbc.sql("UPDATE providers SET default_model = ?, economy_model = ? WHERE id = ?")
                .params(blankToNull(defaultModel), blankToNull(economyModel), providerId).update();
        Actor actor = Actor.human(userId);
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("name", p.name());
        payload.put("defaultModel", blankToNull(defaultModel));
        payload.put("economyModel", blankToNull(economyModel));
        events.publish(workspaceId, null, "provider.updated", actor, new Subject("provider", providerId), payload);
        audit.record(workspaceId, actor, "provider.models", "provider", providerId, AuditLog.Decision.RECORDED,
                Map.of("defaultModel", String.valueOf(blankToNull(defaultModel)),
                        "economyModel", String.valueOf(blankToNull(economyModel))));
        if (p.isDefault() && blankToNull(defaultModel) != null) {
            appEvents.publishEvent(new DefaultModelChanged(workspaceId, providerId, defaultModel.trim()));
        }
        return find(workspaceId, providerId).orElseThrow();
    }

    /** Makes this provider the one new agents are bound to. */
    @Transactional
    public Provider makeDefault(String workspaceId, String userId, String providerId) {
        Provider p = find(workspaceId, providerId).orElseThrow(() -> ApiException.notFound("Provider"));
        if (p.defaultModel() == null) {
            throw ApiException.badRequest("default_model_required", "Choose this provider's default model first");
        }
        jdbc.sql("UPDATE providers SET is_default = false WHERE workspace_id = ? AND is_default").param(workspaceId).update();
        jdbc.sql("UPDATE providers SET is_default = true WHERE id = ?").param(providerId).update();
        Actor actor = Actor.human(userId);
        events.publish(workspaceId, null, "provider.updated", actor, new Subject("provider", providerId),
                Map.of("name", p.name(), "isDefault", true));
        audit.record(workspaceId, actor, "provider.default", "provider", providerId, AuditLog.Decision.RECORDED,
                Map.of("name", p.name()));
        appEvents.publishEvent(new DefaultModelChanged(workspaceId, providerId, p.defaultModel()));
        return find(workspaceId, providerId).orElseThrow();
    }

    /** Models the provider reports for its key. */
    public List<String> models(String workspaceId, String providerId) {
        Provider p = find(workspaceId, providerId).orElseThrow(() -> ApiException.notFound("Provider"));
        return adapter(p.kind()).listModels(p.id(), p.baseUrl(), apiKey(workspaceId, providerId));
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
        if (provider.isDefault()) {
            // The oldest remaining provider with a default model takes over.
            jdbc.sql("""
                    UPDATE providers SET is_default = true WHERE id = (
                        SELECT id FROM providers WHERE workspace_id = ? AND default_model IS NOT NULL ORDER BY created_at LIMIT 1)
                    """).param(workspaceId).update();
        }
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
                rs.getTimestamp("created_at").toInstant(), rs.getString("economy_model"), rs.getString("default_model"),
                rs.getBoolean("is_default"), rs.getString("preset"));
    }
}

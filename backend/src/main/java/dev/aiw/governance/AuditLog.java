package dev.aiw.governance;

import dev.aiw.shared.Actor;
import dev.aiw.shared.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only audit trail. Each entry's hash covers its content and the previous entry's hash
 * in the same workspace, so any later edit or deletion breaks the chain (see {@link #verify}).
 * The table itself rejects UPDATE and DELETE through a trigger.
 */
@Service
public class AuditLog {
    public enum Decision { ALLOWED, DENIED, APPROVED, REJECTED, RECORDED }

    static final String GENESIS = "0".repeat(64);

    private final JdbcClient jdbc;
    /** Canonical form: map keys sorted at every depth, so the hash survives a JSONB round trip. */
    private final JsonMapper json = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String workspaceId, Actor actor, String action, String targetKind, String targetId,
                       Decision decision, Map<String, ?> details) {
        // Serialize appends per workspace so the chain stays linear.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("audit:" + workspaceId).query().singleRow();
        String prev = jdbc.sql("SELECT hash FROM audit_log WHERE workspace_id = ? ORDER BY seq DESC LIMIT 1")
                .param(workspaceId).query(String.class).optional().orElse(GENESIS);
        String detailsJson = json.writeValueAsString(details);
        String id = Ids.newId();
        String hash = hash(prev, id, workspaceId, actor.kind().name(), actor.id(), action, targetKind, targetId,
                decision.name(), detailsJson);
        jdbc.sql("""
                INSERT INTO audit_log (id, workspace_id, actor_kind, actor_id, action, target_kind, target_id,
                                       decision, details, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """)
                .params(id, workspaceId, actor.kind().name(), actor.id(), action, targetKind, targetId,
                        decision.name(), detailsJson, prev, hash)
                .update();
    }

    /** Recomputes the chain for a workspace; returns the seq of the first broken entry, or -1 if intact. */
    public long verify(String workspaceId) {
        var rows = jdbc.sql("SELECT * FROM audit_log WHERE workspace_id = ? ORDER BY seq").param(workspaceId)
                .query().listOfRows();
        String prev = GENESIS;
        for (Map<String, Object> r : rows) {
            String details = json.writeValueAsString(json.readValue(r.get("details").toString(), Map.class));
            String expected = hash(prev, (String) r.get("id"), workspaceId, (String) r.get("actor_kind"),
                    (String) r.get("actor_id"), (String) r.get("action"), (String) r.get("target_kind"),
                    (String) r.get("target_id"), (String) r.get("decision"), details);
            if (!expected.equals(r.get("hash")) || !prev.equals(r.get("prev_hash"))) {
                return ((Number) r.get("seq")).longValue();
            }
            prev = expected;
        }
        return -1;
    }

    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                digest.update((p == null ? "\u0000" : p).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1F);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

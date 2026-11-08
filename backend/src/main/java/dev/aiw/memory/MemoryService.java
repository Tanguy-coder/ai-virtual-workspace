package dev.aiw.memory;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project memory. Shared entries (decisions, architecture, contracts, conventions, glossary) are written only
 * through {@link #record}, which versions every change and keeps its source. Search combines full-text rank
 * and recency; code never goes into memory, it stays in Git.
 */
@Service
public class MemoryService {
    public static final Set<String> KINDS = Set.of("ARCHITECTURE", "DECISION", "CONTRACT", "CONVENTION", "GLOSSARY",
            "REQUIREMENT", "RISK", "NOTE");

    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final AuditLog audit;

    MemoryService(JdbcClient jdbc, EventPublisher events, AuditLog audit) {
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
    }

    /**
     * Records an entry. With an existing {@code entryKey} it becomes a new version of that entry;
     * with null a new entry is started.
     */
    @Transactional
    public MemoryEntry record(String workspaceId, String projectId, String agentId, String entryKey, String kind,
                              String title, String content, String sourceKind, String sourceId, Actor author) {
        if (!KINDS.contains(kind)) throw ApiException.badRequest("invalid_kind", "Unknown memory kind " + kind);
        String key = entryKey == null ? "mem_" + Ids.newId().toLowerCase() : entryKey;
        int version = 1;
        if (entryKey != null) {
            MemoryEntry latest = latest(projectId, entryKey).orElseThrow(() -> ApiException.notFound("Memory entry"));
            version = latest.version() + 1;
        }
        String id = Ids.newId();
        jdbc.sql("""
                INSERT INTO memory_entries (id, workspace_id, project_id, agent_id, entry_key, version, kind, title,
                                            content, source_kind, source_id, author_kind, author_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, workspaceId, projectId, agentId, key, version, kind, title.trim(), content.trim(),
                sourceKind, sourceId, author.kind().name(), author.id()).update();
        Map<String, Object> payload = new HashMap<>();
        payload.put("entryKey", key);
        payload.put("kind", kind);
        payload.put("title", title.trim());
        payload.put("version", version);
        payload.put("shared", agentId == null);
        payload.put("source", sourceKind);
        events.publish(workspaceId, projectId, "memory.recorded", author, new Subject("memory", key), payload);
        if (agentId == null) {
            audit.record(workspaceId, author, "memory.record", "memory", key, AuditLog.Decision.RECORDED,
                    Map.of("kind", kind, "version", String.valueOf(version)));
        }
        return jdbc.sql("SELECT * FROM memory_entries WHERE id = ?").param(id).query(this::map).single();
    }

    public Optional<MemoryEntry> latest(String projectId, String entryKey) {
        return jdbc.sql("SELECT * FROM memory_entries WHERE project_id = ? AND entry_key = ? ORDER BY version DESC LIMIT 1")
                .params(projectId, entryKey).query(this::map).optional();
    }

    public List<MemoryEntry> history(String projectId, String entryKey) {
        return jdbc.sql("SELECT * FROM memory_entries WHERE project_id = ? AND entry_key = ? ORDER BY version DESC")
                .params(projectId, entryKey).query(this::map).list();
    }

    /** Latest version of each shared entry, optionally filtered by kind and ranked by a text query. */
    public List<MemoryEntry> search(String projectId, String kind, String query, int limit) {
        String tsQuery = tsQuery(query);
        return jdbc.sql("""
                WITH latest AS (
                    SELECT DISTINCT ON (entry_key) * FROM memory_entries
                    WHERE project_id = :project AND agent_id IS NULL
                    ORDER BY entry_key, version DESC)
                SELECT * FROM latest
                WHERE (CAST(:kind AS TEXT) IS NULL OR kind = :kind)
                  AND (CAST(:q AS TEXT) IS NULL OR search @@ to_tsquery('simple', :q))
                ORDER BY CASE WHEN CAST(:q AS TEXT) IS NULL THEN 0
                              ELSE ts_rank(search, to_tsquery('simple', :q)) END DESC,
                         created_at DESC
                LIMIT :limit
                """).param("project", projectId).param("kind", kind).param("q", tsQuery)
                .param("limit", Math.min(Math.max(limit, 1), 200)).query(this::map).list();
    }

    /**
     * Shared memory for an agent's context: architecture first, then entries ranked by relevance to
     * {@code query} and by recency, cut at {@code maxChars}.
     */
    public String forContext(String projectId, String query, int maxChars) {
        String tsQuery = tsQuery(query);
        List<MemoryEntry> entries = jdbc.sql("""
                WITH latest AS (
                    SELECT DISTINCT ON (entry_key) * FROM memory_entries
                    WHERE project_id = :project AND agent_id IS NULL
                    ORDER BY entry_key, version DESC)
                SELECT * FROM latest
                ORDER BY (kind = 'ARCHITECTURE') DESC,
                         CASE WHEN CAST(:q AS TEXT) IS NULL THEN 0
                              ELSE ts_rank(search, to_tsquery('simple', :q)) END DESC,
                         created_at DESC
                LIMIT 100
                """).param("project", projectId).param("q", tsQuery).query(this::map).list();
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry e : entries) {
            String block = "<entry kind=\"" + e.kind() + "\" title=\"" + e.title().replace("\"", "'") + "\" version=\""
                    + e.version() + "\">\n" + e.content() + "\n</entry>\n";
            if (sb.length() + block.length() > maxChars) break;
            sb.append(block);
        }
        return sb.toString();
    }

    /** An agent's individual memory (working notes, past contributions), newest first, cut at {@code maxChars}. */
    public String forAgent(String agentId, int maxChars) {
        List<MemoryEntry> entries = jdbc.sql("""
                SELECT DISTINCT ON (entry_key) * FROM memory_entries WHERE agent_id = ?
                ORDER BY entry_key, version DESC
                """).param(agentId).query(this::map).list().stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt())).toList();
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry e : entries) {
            String block = "<note title=\"" + e.title().replace("\"", "'") + "\">\n" + e.content() + "\n</note>\n";
            if (sb.length() + block.length() > maxChars) break;
            sb.append(block);
        }
        return sb.toString();
    }

    public List<MemoryEntry> ofAgent(String agentId) {
        return jdbc.sql("""
                SELECT * FROM (SELECT DISTINCT ON (entry_key) * FROM memory_entries WHERE agent_id = ?
                               ORDER BY entry_key, version DESC) t ORDER BY created_at DESC
                """).param(agentId).query(this::map).list();
    }

    /** OR of the query's words, so any matching word counts; null when there is nothing to search for. */
    static String tsQuery(String query) {
        if (query == null) return null;
        String joined = Arrays.stream(query.toLowerCase().split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() > 2).distinct().limit(40).collect(Collectors.joining(" | "));
        return joined.isEmpty() ? null : joined;
    }

    private MemoryEntry map(ResultSet rs, int row) throws SQLException {
        return new MemoryEntry(rs.getString("id"), rs.getString("project_id"), rs.getString("agent_id"),
                rs.getString("entry_key"), rs.getInt("version"), rs.getString("kind"), rs.getString("title"),
                rs.getString("content"), rs.getString("source_kind"), rs.getString("source_id"),
                rs.getString("author_kind"), rs.getString("author_id"), rs.getTimestamp("created_at").toInstant());
    }
}

package dev.aiw.organization;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.modelgateway.Provider;
import dev.aiw.modelgateway.ProviderCreated;
import dev.aiw.modelgateway.ProviderKind;
import dev.aiw.modelgateway.ProviderService;
import dev.aiw.projects.ProjectCreated;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {
    static final String LEAD_TEMPLATE = "rt_cto";

    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final AuditLog audit;
    private final ProviderService providers;

    AgentService(JdbcClient jdbc, EventPublisher events, AuditLog audit, ProviderService providers) {
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
        this.providers = providers;
    }

    // ---- catalog ----

    public List<RoleTemplate> catalog(String workspaceId) {
        return jdbc.sql("SELECT * FROM role_templates WHERE workspace_id IS NULL OR workspace_id = ? ORDER BY family, title")
                .param(workspaceId).query(this::mapTemplate).list();
    }

    public Optional<RoleTemplate> template(String workspaceId, String templateId) {
        return jdbc.sql("SELECT * FROM role_templates WHERE id = ? AND (workspace_id IS NULL OR workspace_id = ?)")
                .params(templateId, workspaceId).query(this::mapTemplate).optional();
    }

    // ---- queries ----

    public List<Agent> list(String projectId) {
        return jdbc.sql("SELECT * FROM agents WHERE project_id = ? AND status <> 'DISMISSED' ORDER BY is_lead DESC, created_at")
                .param(projectId).query(this::map).list();
    }

    public Optional<Agent> find(String agentId) {
        return jdbc.sql("SELECT * FROM agents WHERE id = ?").param(agentId).query(this::map).optional();
    }

    public Map<String, Long> countByActivity(String projectId) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.sql("""
                SELECT CASE WHEN status = 'PAUSED' THEN 'PAUSED' ELSE activity END AS state, count(*)
                FROM agents WHERE project_id = ? AND status <> 'DISMISSED' GROUP BY 1
                """).param(projectId).query((ResultSet rs) -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
        return counts;
    }

    // ---- lifecycle ----

    /** Every new project starts with its AI Engineering Lead. */
    @EventListener
    @Transactional
    public void onProjectCreated(ProjectCreated event) {
        RoleTemplate t = template(event.workspaceId(), LEAD_TEMPLATE).orElseThrow();
        insert(event.workspaceId(), event.projectId(), t, "Lead", "AI Engineering Lead", "PRINCIPAL", 15,
                t.skills(), t.responsibilities(), true, Actor.system());
    }

    /** Binds the first Anthropic provider to agents that have no model yet. */
    @EventListener
    @Transactional
    public void onProviderCreated(ProviderCreated event) {
        if (event.kind() != ProviderKind.ANTHROPIC) return;
        List<String> unbound = jdbc.sql("SELECT id FROM agents WHERE workspace_id = ? AND provider_id IS NULL AND status <> 'DISMISSED'")
                .param(event.workspaceId()).query(String.class).list();
        for (String agentId : unbound) {
            jdbc.sql("UPDATE agents SET provider_id = ?, model = ? WHERE id = ?")
                    .params(event.providerId(), event.defaultModel(), agentId).update();
            Agent a = find(agentId).orElseThrow();
            events.publish(a.workspaceId(), a.projectId(), "agent.model_bound", Actor.system(), new Subject("agent", agentId),
                    Map.of("model", event.defaultModel()));
        }
    }

    public record HireOverrides(String name, Department department, String seniority, Integer simulatedYears,
                         List<String> skills, List<String> responsibilities, String providerId, String model) {}

    @Transactional
    public Agent hire(String workspaceId, String projectId, String userId, String templateId, HireOverrides o) {
        RoleTemplate t = template(workspaceId, templateId).orElseThrow(() -> ApiException.notFound("Role template"));
        String seniority = o.seniority() == null ? "SENIOR" : o.seniority();
        int years = o.simulatedYears() == null ? defaultYears(seniority) : o.simulatedYears();
        String name = o.name() == null || o.name().isBlank() ? nextName(projectId, t.title()) : o.name().trim();
        RoleTemplate placed = o.department() == null ? t
                : new RoleTemplate(t.id(), t.family(), t.title(), o.department(), t.skills(), t.responsibilities(), t.builtin());
        Agent agent = insert(workspaceId, projectId, placed, name, t.title(), seniority, years,
                o.skills() == null ? t.skills() : o.skills(),
                o.responsibilities() == null ? t.responsibilities() : o.responsibilities(), false, Actor.human(userId));
        if (o.providerId() != null) {
            return updateModel(agent.id(), userId, o.providerId(), o.model());
        }
        return agent;
    }

    @Transactional
    public Agent updateModel(String agentId, String userId, String providerId, String model) {
        Agent agent = find(agentId).orElseThrow(() -> ApiException.notFound("Agent"));
        Provider provider = providers.find(agent.workspaceId(), providerId)
                .orElseThrow(() -> ApiException.badRequest("provider_missing", "Unknown provider"));
        if (model == null || model.isBlank()) throw ApiException.badRequest("model_required", "A model is required");
        jdbc.sql("UPDATE agents SET provider_id = ?, model = ? WHERE id = ?").params(provider.id(), model.trim(), agentId).update();
        Actor actor = Actor.human(userId);
        events.publish(agent.workspaceId(), agent.projectId(), "agent.model_bound", actor, new Subject("agent", agentId),
                Map.of("provider", provider.name(), "model", model.trim()));
        audit.record(agent.workspaceId(), actor, "agent.bind_model", "agent", agentId, AuditLog.Decision.RECORDED,
                Map.of("providerId", provider.id(), "model", model.trim()));
        return find(agentId).orElseThrow();
    }

    @Transactional
    public Agent changeDepartment(String agentId, String userId, Department department) {
        Agent agent = find(agentId).orElseThrow(() -> ApiException.notFound("Agent"));
        if (agent.department() == department) return agent;
        jdbc.sql("UPDATE agents SET department = ? WHERE id = ?").params(department.name(), agentId).update();
        Actor actor = Actor.human(userId);
        Map<String, Object> change = Map.of("from", agent.department().name(), "to", department.name());
        events.publish(agent.workspaceId(), agent.projectId(), "agent.department_changed", actor,
                new Subject("agent", agentId), change);
        audit.record(agent.workspaceId(), actor, "agent.change_department", "agent", agentId,
                AuditLog.Decision.RECORDED, change);
        return find(agentId).orElseThrow();
    }

    @Transactional
    public RoleTemplate createTemplate(String workspaceId, String userId, String family, String title,
                                       Department department, List<String> skills, List<String> responsibilities) {
        String id = "rt_" + Ids.newId().toLowerCase();
        jdbc.sql("""
                INSERT INTO role_templates (id, workspace_id, family, title, department, skills, responsibilities)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(id, workspaceId, family, title.trim(), department.name(), skills.toArray(String[]::new),
                        responsibilities.toArray(String[]::new)).update();
        Actor actor = Actor.human(userId);
        events.publish(workspaceId, null, "role_template.created", actor, new Subject("role_template", id),
                Map.of("title", title.trim(), "department", department.name()));
        audit.record(workspaceId, actor, "role_template.create", "role_template", id, AuditLog.Decision.RECORDED,
                Map.of("title", title.trim()));
        return template(workspaceId, id).orElseThrow();
    }

    @Transactional
    public Agent updateInstructions(String agentId, String userId, String instructions) {
        Agent agent = find(agentId).orElseThrow(() -> ApiException.notFound("Agent"));
        jdbc.sql("UPDATE agents SET system_instructions = ? WHERE id = ?").params(instructions, agentId).update();
        Actor actor = Actor.human(userId);
        events.publish(agent.workspaceId(), agent.projectId(), "agent.instructions_updated", actor,
                new Subject("agent", agentId), Map.of());
        audit.record(agent.workspaceId(), actor, "agent.update_instructions", "agent", agentId,
                AuditLog.Decision.RECORDED, Map.of("length", String.valueOf(instructions.length())));
        return find(agentId).orElseThrow();
    }

    @Transactional
    public Agent setStatus(String agentId, String userId, String status) {
        Agent agent = find(agentId).orElseThrow(() -> ApiException.notFound("Agent"));
        if ("DISMISSED".equals(agent.status())) throw ApiException.conflict("dismissed", "This agent was dismissed");
        if (agent.lead() && "DISMISSED".equals(status)) {
            throw ApiException.conflict("lead_required", "The Lead cannot be dismissed");
        }
        jdbc.sql("UPDATE agents SET status = ? WHERE id = ?").params(status, agentId).update();
        Actor actor = Actor.human(userId);
        String type = switch (status) {
            case "PAUSED" -> "agent.paused";
            case "ACTIVE" -> "agent.resumed";
            default -> "agent.dismissed";
        };
        events.publish(agent.workspaceId(), agent.projectId(), type, actor, new Subject("agent", agentId),
                Map.of("name", agent.name()));
        audit.record(agent.workspaceId(), actor, type, "agent", agentId, AuditLog.Decision.RECORDED,
                Map.of("name", agent.name()));
        return find(agentId).orElseThrow();
    }

    /**
     * A model call starts for this agent. The displayed activity becomes THINKING while at least one call runs;
     * the resting activity (AVAILABLE, IN_MEETING, BLOCKED) is restored when the last call ends.
     */
    @Transactional
    public void beginThinking(String agentId, Map<String, ?> details) {
        int calls = jdbc.sql("UPDATE agents SET thinking_calls = thinking_calls + 1 WHERE id = ? RETURNING thinking_calls")
                .param(agentId).query(Integer.class).single();
        if (calls == 1) applyActivity(agentId, "THINKING", details);
    }

    @Transactional
    public void endThinking(String agentId) {
        int calls = jdbc.sql("UPDATE agents SET thinking_calls = greatest(thinking_calls - 1, 0) WHERE id = ? RETURNING thinking_calls")
                .param(agentId).query(Integer.class).single();
        if (calls == 0) {
            String resting = jdbc.sql("SELECT resting_activity FROM agents WHERE id = ?").param(agentId).query(String.class).single();
            applyActivity(agentId, resting, Map.of());
        }
    }

    /** Sets what the agent does when no model call runs: AVAILABLE, IN_MEETING or BLOCKED (with a reason). */
    @Transactional
    public void setResting(String agentId, String activity, String reason) {
        jdbc.sql("UPDATE agents SET resting_activity = ?, blocked_reason = ? WHERE id = ?")
                .params(activity, "BLOCKED".equals(activity) ? reason : null, agentId).update();
        int calls = jdbc.sql("SELECT thinking_calls FROM agents WHERE id = ?").param(agentId).query(Integer.class).single();
        if (calls == 0) {
            applyActivity(agentId, activity, reason == null ? Map.of() : Map.of("reason", reason));
        }
    }

    /** Clears activity left over by a restart: no model call can be running when the process starts. */
    @Transactional
    public void resetAfterRestart() {
        List<String> stale = jdbc.sql("SELECT id FROM agents WHERE thinking_calls > 0 OR activity <> resting_activity")
                .query(String.class).list();
        for (String agentId : stale) {
            jdbc.sql("UPDATE agents SET thinking_calls = 0 WHERE id = ?").param(agentId).update();
            String resting = jdbc.sql("SELECT resting_activity FROM agents WHERE id = ?").param(agentId).query(String.class).single();
            applyActivity(agentId, resting, Map.of("reason", "restart"));
        }
    }

    private void applyActivity(String agentId, String activity, Map<String, ?> details) {
        Agent agent = find(agentId).orElseThrow();
        if (activity.equals(agent.activity())) return;
        jdbc.sql("UPDATE agents SET activity = ? WHERE id = ?").params(activity, agentId).update();
        Map<String, Object> payload = new HashMap<>(details);
        payload.put("from", agent.activity());
        payload.put("to", activity);
        events.publish(agent.workspaceId(), agent.projectId(), "agent.state_changed", Actor.agent(agentId),
                new Subject("agent", agentId), payload);
    }

    /** Hires several agents in one transaction (accepted Lead proposal). */
    @Transactional
    public List<Agent> hireAll(String workspaceId, String projectId, String userId, List<HireRequest> requests) {
        return requests.stream().map(r -> hire(workspaceId, projectId, userId, r.templateId(), r.overrides())).toList();
    }

    public record HireRequest(String templateId, HireOverrides overrides) {}

    public Optional<Agent> lead(String projectId) {
        return jdbc.sql("SELECT * FROM agents WHERE project_id = ? AND is_lead AND status <> 'DISMISSED'")
                .param(projectId).query(this::map).optional();
    }

    private Agent insert(String workspaceId, String projectId, RoleTemplate t, String name, String title,
                         String seniority, int years, List<String> skills, List<String> responsibilities, boolean lead,
                         Actor actor) {
        String id = Ids.newId();
        Optional<Provider> provider = providers.defaultProvider(workspaceId);
        jdbc.sql("""
                INSERT INTO agents (id, workspace_id, project_id, role_template_id, name, title, department, seniority,
                                    simulated_years, skills, responsibilities, system_instructions, provider_id, model,
                                    is_lead)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, workspaceId, projectId, t.id(), name, title, t.department().name(), seniority, years,
                        skills.toArray(String[]::new), responsibilities.toArray(String[]::new),
                        AgentInstructions.build(name, title, seniority, years, skills, responsibilities, lead),
                        provider.map(Provider::id).orElse(null),
                        provider.map(p -> ProviderService.DEFAULT_ANTHROPIC_MODEL).orElse(null), lead)
                .update();
        events.publish(workspaceId, projectId, "agent.hired", actor, new Subject("agent", id),
                Map.of("name", name, "title", title, "department", t.department().name(), "lead", lead));
        audit.record(workspaceId, actor, "agent.hire", "agent", id, AuditLog.Decision.RECORDED,
                Map.of("name", name, "title", title, "templateId", t.id()));
        return find(id).orElseThrow();
    }

    private String nextName(String projectId, String title) {
        long n = jdbc.sql("SELECT count(*) FROM agents WHERE project_id = ? AND title = ?").params(projectId, title)
                .query(Long.class).single();
        return title + " " + String.format("%02d", n + 1);
    }

    private static int defaultYears(String seniority) {
        return switch (seniority) {
            case "JUNIOR" -> 2;
            case "MID" -> 5;
            case "PRINCIPAL" -> 15;
            default -> 10;
        };
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private RoleTemplate mapTemplate(ResultSet rs, int row) throws SQLException {
        return new RoleTemplate(rs.getString("id"), rs.getString("family"), rs.getString("title"),
                Department.valueOf(rs.getString("department")), strings(rs.getArray("skills")),
                strings(rs.getArray("responsibilities")), rs.getString("workspace_id") == null);
    }

    private Agent map(ResultSet rs, int row) throws SQLException {
        return new Agent(rs.getString("id"), rs.getString("workspace_id"), rs.getString("project_id"),
                rs.getString("role_template_id"), rs.getString("name"), rs.getString("title"),
                Department.valueOf(rs.getString("department")), rs.getString("seniority"), rs.getInt("simulated_years"),
                strings(rs.getArray("skills")), strings(rs.getArray("responsibilities")),
                rs.getString("system_instructions"), rs.getString("provider_id"), rs.getString("model"),
                rs.getBoolean("is_lead"), rs.getString("status"), rs.getString("activity"),
                rs.getString("blocked_reason"), rs.getTimestamp("created_at").toInstant());
    }
}

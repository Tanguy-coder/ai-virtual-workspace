package dev.aiw.organization;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.modelgateway.ChatTurn;
import dev.aiw.modelgateway.ModelCallException;
import dev.aiw.modelgateway.ModelGateway;
import dev.aiw.modelgateway.ModelRequest;
import dev.aiw.modelgateway.ModelResult;
import dev.aiw.projects.Project;
import dev.aiw.projects.ProjectDocument;
import dev.aiw.projects.ProjectService;
import dev.aiw.projects.SpecExcerpts;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Direct conversation between humans and an agent. Posting a message returns immediately; the agent's
 * reply is produced on a background virtual thread through the model gateway, one call at a time per agent.
 * Pending messages left by a restart are picked up again at startup.
 */
@Service
public class AgentChatService {
    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);
    /** Recent messages sent with each reply; older ones are dropped (docs/09). */
    private static final int HISTORY_LIMIT = 12;
    /** Specification excerpts in the chat prompt, chosen by the agent's role so the prefix stays cacheable. */
    private static final int SPEC_EXCERPTS = 5_000;
    private static final long MAX_TOKENS = 4_000;
    /** Orders the Lead may give in one reply (docs/12). */
    private static final int MAX_ORDERS = 10;
    private static final Pattern ORDERS = Pattern.compile("(?s)<orders>(.*?)</orders>");
    private static final TypeReference<List<LeadCommands.Outcome>> OUTCOMES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final AgentService agents;
    private final ProjectService projects;
    private final ModelGateway gateway;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final ObjectProvider<LeadCommands> commands;
    private final JsonMapper json;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    AgentChatService(JdbcClient jdbc, AgentService agents, ProjectService projects, ModelGateway gateway,
                     EventPublisher events, TransactionTemplate tx, ObjectProvider<LeadCommands> commands, JsonMapper json) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.projects = projects;
        this.gateway = gateway;
        this.events = events;
        this.tx = tx;
        this.commands = commands;
        this.json = json;
    }

    public List<AgentMessage> history(String agentId, int limit) {
        List<AgentMessage> latest = jdbc.sql("SELECT * FROM agent_messages WHERE agent_id = ? ORDER BY created_at DESC, id DESC LIMIT ?")
                .params(agentId, Math.min(Math.max(limit, 1), 500)).query(this::map).list();
        return latest.reversed();
    }

    public AgentMessage post(String agentId, String userId, String content) {
        Agent agent = agents.find(agentId).orElseThrow(() -> ApiException.notFound("Agent"));
        if (!"ACTIVE".equals(agent.status())) {
            throw ApiException.conflict("agent_inactive", "This agent is " + agent.status().toLowerCase());
        }
        String id = Ids.newId();
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    INSERT INTO agent_messages (id, workspace_id, agent_id, author_kind, author_id, content, status)
                    VALUES (?, ?, ?, 'HUMAN', ?, ?, 'PENDING')
                    """).params(id, agent.workspaceId(), agentId, userId, content).update();
            events.publish(agent.workspaceId(), agent.projectId(), "message.sent", Actor.human(userId),
                    new Subject("agent", agentId), Map.of("messageId", id, "to", agent.name()));
        });
        executor.submit(() -> process(agentId));
        return message(id);
    }

    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        agents.resetAfterRestart();
        List<String> waiting = jdbc.sql("SELECT DISTINCT agent_id FROM agent_messages WHERE status = 'PENDING'")
                .query(String.class).list();
        waiting.forEach(agentId -> executor.submit(() -> process(agentId)));
    }

    private void process(String agentId) {
        ReentrantLock lock = locks.computeIfAbsent(agentId, k -> new ReentrantLock());
        lock.lock();
        try {
            while (true) {
                List<String> pending = jdbc.sql("SELECT id FROM agent_messages WHERE agent_id = ? AND status = 'PENDING' ORDER BY created_at")
                        .param(agentId).query(String.class).list();
                if (pending.isEmpty()) return;
                answer(agentId, pending);
            }
        } catch (RuntimeException e) {
            log.error("Agent {} message processing failed", agentId, e);
        } finally {
            lock.unlock();
        }
    }

    private void answer(String agentId, List<String> pendingIds) {
        Agent agent = agents.find(agentId).orElseThrow();
        if (!"ACTIVE".equals(agent.status())) {
            fail(agent, pendingIds, "The agent is " + agent.status().toLowerCase() + "; the message was not processed.");
            return;
        }
        if (agent.providerId() == null || agent.model() == null) {
            fail(agent, pendingIds, "No AI model is configured for this agent. Add a provider in Settings, "
                    + "then choose a model in the agent's settings.");
            return;
        }
        agents.beginThinking(agentId, Map.of("model", agent.model()));
        // The Lead carries out the CTO's orders: it sees the recorded project state and may end its reply with orders.
        LeadCommands lead = agent.lead() ? commands.getIfAvailable() : null;
        List<ChatTurn> turns = turns(agentId);
        if (lead != null && !turns.isEmpty() && turns.getLast().role() == ChatTurn.Role.USER) {
            ChatTurn last = turns.removeLast();
            turns.add(new ChatTurn(ChatTurn.Role.USER, "<project_state recorded_by=\"platform\">\n" + lead.state(agent.projectId())
                    + "</project_state>\n\n" + last.text()));
        }
        ModelResult result;
        try {
            // Stable system prompt and cached conversation: each reply re-reads the history from the provider cache.
            result = gateway.complete(new ModelRequest(agent.workspaceId(), agent.projectId(), agentId, agent.providerId(),
                    agent.model(), systemPrompt(agent) + (lead == null ? "" : "\n" + lead.manual()), null, turns, MAX_TOKENS,
                    "medium", ModelRequest.Tier.STANDARD, ModelRequest.Cache.CONVERSATION, agent.need()));
        } catch (ModelCallException | ApiException e) {
            agents.endThinking(agentId);
            fail(agent, pendingIds, e.getMessage());
            return;
        }
        String reply = result.refused()
                ? "I can't help with this request (the model declined it)."
                : result.text().isBlank() ? "(The model returned an empty reply.)" : result.text();
        List<LeadCommands.Outcome> outcomes = List.of();
        if (lead != null && !result.refused()) {
            Matcher m = ORDERS.matcher(reply);
            if (m.find()) {
                outcomes = carryOut(lead, agent, pendingIds, m.group(1));
                reply = ORDERS.matcher(reply).replaceAll("").strip();
            }
        }
        String content = reply;
        String actions = outcomes.isEmpty() ? null : json.writeValueAsString(outcomes);
        String replyId = Ids.newId();
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    INSERT INTO agent_messages (id, workspace_id, agent_id, author_kind, author_id, content, status, actions)
                    VALUES (?, ?, ?, 'AGENT', ?, ?, 'FINAL', CAST(? AS jsonb))
                    """).params(replyId, agent.workspaceId(), agentId, agentId, content, actions).update();
            jdbc.sql("UPDATE agent_messages SET status = 'ANSWERED' WHERE id IN (:ids)").param("ids", pendingIds).update();
            events.publish(agent.workspaceId(), agent.projectId(), "message.sent", Actor.agent(agentId),
                    new Subject("agent", agentId), Map.of("messageId", replyId, "from", agent.name(),
                            "refused", result.refused()));
            agents.endThinking(agentId);
        });
    }

    /** Runs the orders of a Lead reply with the rights of the CTO who wrote the latest message. */
    private List<LeadCommands.Outcome> carryOut(LeadCommands lead, Agent agent, List<String> pendingIds, String block) {
        String userId = jdbc.sql("""
                SELECT author_id FROM agent_messages WHERE id IN (:ids) AND author_kind = 'HUMAN' ORDER BY created_at DESC LIMIT 1
                """).param("ids", pendingIds).query(String.class).optional().orElse(null);
        if (userId == null) return List.of();
        JsonNode node;
        try {
            node = json.readTree(block.strip().replaceAll("^```(?:json)?|```$", "").strip());
        } catch (RuntimeException e) {
            return List.of(new LeadCommands.Outcome("orders", false, "The orders could not be read; nothing was done"));
        }
        if (node.isObject() && node.has("orders")) node = node.path("orders");
        if (node.isObject()) node = json.createArrayNode().add(node);
        List<LeadCommands.Outcome> out = new ArrayList<>();
        for (JsonNode order : node) {
            if (out.size() == MAX_ORDERS) {
                out.add(new LeadCommands.Outcome("orders", false, "Only " + MAX_ORDERS + " orders per reply; the rest was ignored"));
                break;
            }
            out.add(lead.execute(agent.projectId(), userId, agent.id(), order));
        }
        return out;
    }

    private void fail(Agent agent, List<String> pendingIds, String error) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE agent_messages SET status = 'FAILED', error = :error WHERE id IN (:ids)")
                    .param("error", error).param("ids", pendingIds).update();
            events.publish(agent.workspaceId(), agent.projectId(), "message.failed", Actor.agent(agent.id()),
                    new Subject("agent", agent.id()), Map.of("error", error));
        });
    }

    /** Conversation for the model: answered and pending human messages plus agent replies, user-first, alternating. */
    private List<ChatTurn> turns(String agentId) {
        List<AgentMessage> history = jdbc.sql("""
                SELECT * FROM agent_messages WHERE agent_id = ? AND status IN ('PENDING', 'ANSWERED', 'FINAL')
                ORDER BY created_at DESC, id DESC LIMIT ?
                """).params(agentId, HISTORY_LIMIT).query(this::map).list().reversed();
        List<ChatTurn> turns = new ArrayList<>();
        for (AgentMessage m : history) {
            ChatTurn.Role role = "HUMAN".equals(m.authorKind()) ? ChatTurn.Role.USER : ChatTurn.Role.ASSISTANT;
            if (turns.isEmpty() && role == ChatTurn.Role.ASSISTANT) continue;
            String text = m.content();
            if (m.actions() != null && !m.actions().isEmpty()) {
                // The Lead learns what really happened to its orders.
                text += "\n[Outcome of your orders, recorded by the platform: " + m.actions().stream()
                        .map(o -> o.action() + " " + (o.ok() ? "done" : "refused") + ": " + o.detail())
                        .reduce((a, b) -> a + "; " + b).orElse("") + "]";
            }
            if (!turns.isEmpty() && turns.getLast().role() == role) {
                ChatTurn last = turns.removeLast();
                turns.add(new ChatTurn(role, last.text() + "\n\n" + text));
            } else {
                turns.add(new ChatTurn(role, text));
            }
        }
        return turns;
    }

    private String systemPrompt(Agent agent) {
        Project project = projects.find(agent.projectId()).orElseThrow();
        StringBuilder sb = new StringBuilder(agent.systemInstructions());
        sb.append("\n\n<project name=\"").append(project.name()).append("\">\n");
        if (!project.description().isBlank()) sb.append(project.description()).append("\n");
        List<ProjectDocument> specs = projects.specifications(agent.projectId());
        if (specs.isEmpty()) {
            sb.append("No specification has been imported yet.\n");
        }
        sb.append(SpecExcerpts.select(specs, agent.title() + " " + String.join(" ", agent.skills()) + " "
                + String.join(" ", agent.responsibilities()), SPEC_EXCERPTS));
        sb.append("</project>\nAnswer briefly and to the point; no preamble, no restating the question.");
        return sb.toString();
    }

    private AgentMessage message(String id) {
        return jdbc.sql("SELECT * FROM agent_messages WHERE id = ?").param(id).query(this::map).single();
    }

    private AgentMessage map(ResultSet rs, int row) throws SQLException {
        return new AgentMessage(rs.getString("id"), rs.getString("agent_id"), rs.getString("author_kind"),
                rs.getString("author_id"), rs.getString("content"), rs.getString("status"), rs.getString("error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("actions") == null ? null : json.readValue(rs.getString("actions"), OUTCOMES));
    }
}

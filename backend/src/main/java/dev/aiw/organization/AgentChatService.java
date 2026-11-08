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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Direct conversation between humans and an agent. Posting a message returns immediately; the agent's
 * reply is produced on a background virtual thread through the model gateway, one call at a time per agent.
 * Pending messages left by a restart are picked up again at startup.
 */
@Service
public class AgentChatService {
    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);
    private static final int HISTORY_LIMIT = 40;
    private static final long MAX_TOKENS = 16_000;

    private final JdbcClient jdbc;
    private final AgentService agents;
    private final ProjectService projects;
    private final ModelGateway gateway;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    AgentChatService(JdbcClient jdbc, AgentService agents, ProjectService projects, ModelGateway gateway,
                     EventPublisher events, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.projects = projects;
        this.gateway = gateway;
        this.events = events;
        this.tx = tx;
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
        ModelResult result;
        try {
            result = gateway.complete(new ModelRequest(agent.workspaceId(), agent.projectId(), agentId, agent.providerId(),
                    agent.model(), systemPrompt(agent), turns(agentId), MAX_TOKENS, "medium"));
        } catch (ModelCallException | ApiException e) {
            agents.endThinking(agentId);
            fail(agent, pendingIds, e.getMessage());
            return;
        }
        String reply = result.refused()
                ? "I can't help with this request (the model declined it)."
                : result.text().isBlank() ? "(The model returned an empty reply.)" : result.text();
        String replyId = Ids.newId();
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    INSERT INTO agent_messages (id, workspace_id, agent_id, author_kind, author_id, content, status)
                    VALUES (?, ?, ?, 'AGENT', ?, ?, 'FINAL')
                    """).params(replyId, agent.workspaceId(), agentId, agentId, reply).update();
            jdbc.sql("UPDATE agent_messages SET status = 'ANSWERED' WHERE id IN (:ids)").param("ids", pendingIds).update();
            events.publish(agent.workspaceId(), agent.projectId(), "message.sent", Actor.agent(agentId),
                    new Subject("agent", agentId), Map.of("messageId", replyId, "from", agent.name(),
                            "refused", result.refused()));
            agents.endThinking(agentId);
        });
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
            if (!turns.isEmpty() && turns.getLast().role() == role) {
                ChatTurn last = turns.removeLast();
                turns.add(new ChatTurn(role, last.text() + "\n\n" + m.content()));
            } else {
                turns.add(new ChatTurn(role, m.content()));
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
        for (ProjectDocument d : specs) {
            sb.append("<document title=\"").append(d.title()).append("\">\n").append(d.content()).append("\n</document>\n");
        }
        sb.append("</project>");
        return sb.toString();
    }

    private AgentMessage message(String id) {
        return jdbc.sql("SELECT * FROM agent_messages WHERE id = ?").param(id).query(this::map).single();
    }

    private AgentMessage map(ResultSet rs, int row) throws SQLException {
        return new AgentMessage(rs.getString("id"), rs.getString("agent_id"), rs.getString("author_kind"),
                rs.getString("author_id"), rs.getString("content"), rs.getString("status"), rs.getString("error"),
                rs.getTimestamp("created_at").toInstant());
    }
}

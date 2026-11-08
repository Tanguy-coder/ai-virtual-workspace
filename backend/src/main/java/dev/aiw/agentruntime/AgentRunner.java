package dev.aiw.agentruntime;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.memory.MemoryService;
import dev.aiw.modelgateway.ChatTurn;
import dev.aiw.modelgateway.ModelCallException;
import dev.aiw.modelgateway.ModelGateway;
import dev.aiw.modelgateway.ModelRequest;
import dev.aiw.modelgateway.ModelResult;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.projects.Project;
import dev.aiw.projects.ProjectDocument;
import dev.aiw.projects.ProjectService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one model step for an agent: builds its context (instructions, project, specification, shared memory),
 * shows the agent as thinking while the call runs, and records the step. All calls go through the model gateway.
 */
@Service
public class AgentRunner {
    /** Raised by output validators; the runner asks the model once to correct its output. */
    public static class InvalidOutput extends RuntimeException {
        public InvalidOutput(String message) {
            super(message);
        }
    }

    private static final int SPEC_CHAR_BUDGET = 120_000;
    private static final int MEMORY_CHAR_BUDGET = 20_000;

    private final ModelGateway gateway;
    private final AgentService agents;
    private final ProjectService projects;
    private final MemoryService memory;
    private final EventPublisher events;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    AgentRunner(ModelGateway gateway, AgentService agents, ProjectService projects, MemoryService memory,
                EventPublisher events, JdbcClient jdbc, TransactionTemplate tx, JsonMapper json) {
        this.gateway = gateway;
        this.agents = agents;
        this.projects = projects;
        this.memory = memory;
        this.events = events;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    public Agent agent(AgentJob job) {
        Agent agent = agents.find(job.agentId()).orElseThrow(() -> new JobFailure("The agent no longer exists"));
        if (!"ACTIVE".equals(agent.status())) {
            throw new JobFailure(agent.name() + " is " + agent.status().toLowerCase());
        }
        if (agent.providerId() == null || agent.model() == null) {
            throw new JobFailure("No AI model is configured for " + agent.name()
                    + ". Add a provider in Settings, then choose a model in the agent's settings.");
        }
        return agent;
    }

    /**
     * System prompt for structured work: the agent's instructions, the project, its specification and the shared
     * memory entries most relevant to {@code memoryQuery}, each section bounded in size.
     */
    public String context(Agent agent, String memoryQuery) {
        Project project = projects.find(agent.projectId()).orElseThrow();
        StringBuilder sb = new StringBuilder(agent.systemInstructions());
        sb.append("\n\n<project name=\"").append(escape(project.name())).append("\">\n");
        if (!project.description().isBlank()) sb.append(project.description()).append("\n");
        int budget = SPEC_CHAR_BUDGET;
        List<ProjectDocument> specs = projects.specifications(agent.projectId());
        if (specs.isEmpty()) sb.append("No specification has been imported yet.\n");
        for (ProjectDocument d : specs) {
            String content = d.content().length() > budget ? d.content().substring(0, Math.max(budget, 0))
                    + "\n[... truncated]" : d.content();
            budget -= content.length();
            sb.append("<document title=\"").append(escape(d.title())).append("\">\n").append(content).append("\n</document>\n");
        }
        sb.append("</project>\n");
        String shared = memory.forContext(agent.projectId(), memoryQuery, MEMORY_CHAR_BUDGET);
        if (!shared.isBlank()) {
            sb.append("\n<shared_memory>\nDecisions and knowledge recorded for this project (most relevant first):\n")
                    .append(shared).append("</shared_memory>\n");
        }
        String own = memory.forAgent(agent.id(), MEMORY_CHAR_BUDGET / 4);
        if (!own.isBlank()) {
            sb.append("\n<your_notes>\nYour own working notes from earlier work:\n").append(own).append("</your_notes>\n");
        }
        return sb.toString();
    }

    /** One model call recorded as a step. */
    public String complete(AgentJob job, Agent agent, String system, List<ChatTurn> turns, long maxTokens,
                           String effort, String summary) {
        return call(job, agent, system, turns, maxTokens, effort, summary).text();
    }

    /**
     * Asks for a single JSON object. The output is parsed and passed to {@code validator}; if parsing or
     * validation fails, the model gets one chance to correct it.
     */
    public JsonNode completeJson(AgentJob job, Agent agent, String system, String prompt, long maxTokens, String effort,
                                 String summary, Consumer<JsonNode> validator) {
        List<ChatTurn> turns = new ArrayList<>(List.of(new ChatTurn(ChatTurn.Role.USER, prompt
                + "\n\nReply with a single JSON object and nothing else: no prose, no Markdown code fence.")));
        String error = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ModelResult result = call(job, agent, system, turns, maxTokens, effort,
                    attempt == 0 ? summary : summary + " (correction)");
            try {
                if ("max_tokens".equals(result.stopReason())) {
                    throw new InvalidOutput("The output was cut off by the token limit; be more concise.");
                }
                JsonNode node = parse(result.text());
                validator.accept(node);
                return node;
            } catch (InvalidOutput e) {
                error = e.getMessage();
                turns.add(new ChatTurn(ChatTurn.Role.ASSISTANT, result.text().isBlank() ? "(empty)" : result.text()));
                turns.add(new ChatTurn(ChatTurn.Role.USER, "Your reply could not be used: " + e.getMessage()
                        + "\nReply again with only the corrected JSON object."));
            }
        }
        throw new JobFailure("The model did not produce a usable answer: " + error);
    }

    private ModelResult call(AgentJob job, Agent agent, String system, List<ChatTurn> turns, long maxTokens,
                             String effort, String summary) {
        agents.beginThinking(agent.id(), Map.of("model", agent.model(), "step", summary));
        ModelResult result = null;
        String error = null;
        long start = System.nanoTime();
        try {
            result = gateway.complete(new ModelRequest(agent.workspaceId(), agent.projectId(), agent.id(),
                    agent.providerId(), agent.model(), system, turns, maxTokens, effort));
        } catch (ModelCallException e) {
            error = e.getMessage();
            throw e;
        } catch (ApiException e) {
            error = e.getMessage();
            throw new JobFailure(e.getMessage());
        } finally {
            long duration = (System.nanoTime() - start) / 1_000_000;
            ModelResult r = result;
            String err = error;
            tx.executeWithoutResult(s -> {
                record(job, agent, summary, r, duration, err);
                agents.endThinking(agent.id());
            });
        }
        if (result.refused()) throw new JobFailure("The model declined this request");
        return result;
    }

    private void record(AgentJob job, Agent agent, String summary, ModelResult r, long duration, String error) {
        String id = Ids.newId();
        String outcome = error != null ? "ERROR" : r.refused() ? "REFUSED" : "OK";
        BigDecimal cost = r == null ? null : r.costUsd();
        jdbc.sql("""
                INSERT INTO agent_steps (id, workspace_id, project_id, agent_id, job_id, kind, summary, model,
                                         input_tokens, output_tokens, cost_usd, duration_ms, outcome, error)
                VALUES (?, ?, ?, ?, ?, 'MODEL_CALL', ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, agent.workspaceId(), agent.projectId(), agent.id(), job == null ? null : job.id(),
                summary, r == null ? agent.model() : r.servedModel(), r == null ? 0 : r.inputTokens(),
                r == null ? 0 : r.outputTokens(), cost, duration, outcome, error).update();
        Map<String, Object> payload = new HashMap<>();
        payload.put("stepId", id);
        payload.put("summary", summary);
        payload.put("outcome", outcome);
        payload.put("durationMs", duration);
        if (job != null) payload.put("jobId", job.id());
        if (cost != null) payload.put("costUsd", cost.toPlainString());
        events.publish(agent.workspaceId(), agent.projectId(), "agent.step_completed", Actor.agent(agent.id()),
                new Subject("agent", agent.id()), payload);
    }

    JsonNode parse(String text) {
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int fence = t.lastIndexOf("```");
            if (firstNewline > 0 && fence > firstNewline) t = t.substring(firstNewline + 1, fence).strip();
        }
        int open = t.indexOf('{');
        int close = t.lastIndexOf('}');
        if (open < 0 || close < open) throw new InvalidOutput("No JSON object was found in the reply.");
        try {
            JsonNode node = json.readTree(t.substring(open, close + 1));
            if (!node.isObject()) throw new InvalidOutput("The reply is not a JSON object.");
            return node;
        } catch (RuntimeException e) {
            if (e instanceof InvalidOutput io) throw io;
            throw new InvalidOutput("The JSON is malformed: " + e.getMessage());
        }
    }

    private static String escape(String s) {
        return s.replace("\"", "'");
    }
}

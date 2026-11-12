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
import dev.aiw.projects.SpecExcerpts;
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
import tools.jackson.core.json.JsonReadFeature;

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

    /** The model's reply could not be used even after its correction; {@code excerpt}: the start of its last reply. */
    public static class UnusableAnswer extends JobFailure {
        private final String excerpt;

        public UnusableAnswer(String message, String excerpt) {
            super(message);
            this.excerpt = excerpt;
        }

        public String excerpt() { return excerpt; }
    }

    /**
     * Lenient reading of model JSON: small models often put raw newlines in strings (file contents), trailing commas,
     * single quotes or comments. None of these change the meaning, so they are accepted.
     */
    private static final JsonMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS, JsonReadFeature.ALLOW_TRAILING_COMMA,
                    JsonReadFeature.ALLOW_SINGLE_QUOTES, JsonReadFeature.ALLOW_JAVA_COMMENTS,
                    JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    /** Whole specification, for the one step that must read all of it (the Lead's analysis). */
    public static final int FULL_SPEC = 120_000;
    /** Default excerpt budget: about 1.5 k tokens of the most relevant passages (docs/09). */
    public static final int SPEC_EXCERPTS = 6_000;
    private static final int MEMORY_CHAR_BUDGET = 6_000;
    private static final int NOTES_CHAR_BUDGET = 1_500;

    /**
     * System prompt in two parts (docs/09): {@code stable} (instructions, project, specification excerpts) is
     * identical across an agent's calls for the same purpose and is cached by the provider; {@code dynamic}
     * (shared memory, own notes) changes as work is recorded.
     */
    /** Tokens and cost of the calls made while it is {@linkplain #measure installed}, for per-task budgets. */
    public static final class Usage {
        public long input, output, cacheRead;
        public BigDecimal cost = BigDecimal.ZERO;
    }

    private static final ThreadLocal<Usage> USAGE = new ThreadLocal<>();

    /** Runs {@code work} and adds the usage of every model call it makes to {@code usage}. */
    public <T> T measure(Usage usage, java.util.function.Supplier<T> work) {
        Usage previous = USAGE.get();
        USAGE.set(usage);
        try {
            return work.get();
        } finally {
            USAGE.set(previous);
        }
    }

    public record Context(String stable, String dynamic, ModelRequest.Cache cache) {
        /** The same context with more stable text appended (tool manuals...), still cacheable. */
        public Context withStable(String more) {
            return new Context(stable + "\n" + more, dynamic, cache);
        }

        /** The same context for a one-shot call: not worth a cache write. */
        public Context oneShot() {
            return new Context(stable, dynamic, ModelRequest.Cache.NONE);
        }
    }

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
    public Context context(Agent agent, String query) {
        return context(agent, query, SPEC_EXCERPTS, MEMORY_CHAR_BUDGET);
    }

    /**
     * Context bounded per section: up to {@code specChars} of the specification (the passages most relevant to
     * {@code query}, whole when it fits) and up to {@code memoryChars} of shared memory. 0 leaves a section out.
     */
    public Context context(Agent agent, String query, int specChars, int memoryChars) {
        Project project = projects.find(agent.projectId()).orElseThrow();
        StringBuilder sb = new StringBuilder(agent.systemInstructions());
        sb.append("\n\n<project name=\"").append(escape(project.name())).append("\">\n");
        if (!project.description().isBlank()) sb.append(project.description()).append("\n");
        if (specChars > 0) {
            List<ProjectDocument> specs = projects.specifications(agent.projectId());
            if (specs.isEmpty()) sb.append("No specification has been imported yet.\n");
            else sb.append(SpecExcerpts.select(specs, query, specChars));
        }
        sb.append("</project>\n");
        StringBuilder dyn = new StringBuilder();
        String shared = memoryChars > 0 ? memory.forContext(agent.projectId(), query, memoryChars) : "";
        if (!shared.isBlank()) {
            dyn.append("<shared_memory>\nDecisions and knowledge recorded for this project (most relevant first):\n")
                    .append(shared).append("</shared_memory>\n");
        }
        String own = memoryChars > 0 ? memory.forAgent(agent.id(), NOTES_CHAR_BUDGET) : "";
        if (!own.isBlank()) {
            dyn.append("<your_notes>\nYour own working notes from earlier work:\n").append(own).append("</your_notes>\n");
        }
        return new Context(sb.toString(), dyn.toString(), ModelRequest.Cache.PREFIX);
    }

    /** One model call recorded as a step. */
    public String complete(AgentJob job, Agent agent, Context context, List<ChatTurn> turns, long maxTokens,
                           String effort, String summary) {
        return call(job, agent, context, turns, maxTokens, effort, ModelRequest.Tier.STANDARD, summary).text();
    }

    /**
     * Asks for a single JSON object. The output is parsed and passed to {@code validator}; if parsing or
     * validation fails, the model gets one chance to correct it.
     */
    public JsonNode completeJson(AgentJob job, Agent agent, Context context, String prompt, long maxTokens, String effort,
                                 String summary, Consumer<JsonNode> validator) {
        return completeJson(job, agent, context, prompt, maxTokens, effort, ModelRequest.Tier.STANDARD, summary, validator);
    }

    /** As above; {@code tier} LIGHT runs the call on the provider's economy model (extraction, minutes). */
    public JsonNode completeJson(AgentJob job, Agent agent, Context context, String prompt, long maxTokens, String effort,
                                 ModelRequest.Tier tier, String summary, Consumer<JsonNode> validator) {
        List<ChatTurn> turns = new ArrayList<>(List.of(new ChatTurn(ChatTurn.Role.USER, prompt
                + "\n\nReply with a single JSON object and nothing else: no prose, no Markdown code fence.")));
        String error = null;
        String last = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            ModelResult result = call(job, agent, context, turns, maxTokens, effort, tier,
                    attempt == 0 ? summary : summary + " (correction)");
            try {
                if ("max_tokens".equals(result.stopReason())) {
                    throw new InvalidOutput("The output was cut off by the token limit; be more concise.");
                }
                JsonNode node = parse(result.text());
                validator.accept(node);
                return node;
            } catch (InvalidOutput e) {
                gateway.forget(result);
                error = e.getMessage();
                last = result.text();
                // Only the start of the faulty reply goes back: the correction must fit small per-minute allowances too.
                String echo = result.text().length() > 2_000 ? result.text().substring(0, 2_000) + "…" : result.text();
                turns.add(new ChatTurn(ChatTurn.Role.ASSISTANT, echo.isBlank() ? "(empty)" : echo));
                turns.add(new ChatTurn(ChatTurn.Role.USER, "Your reply could not be used: " + e.getMessage()
                        + "\nReply again with only the corrected JSON object."));
            }
        }
        throw new UnusableAnswer("The model did not produce a usable answer: " + error,
                last.length() > 1_500 ? last.substring(0, 1_500) + "…" : last);
    }

    private ModelResult call(AgentJob job, Agent agent, Context context, List<ChatTurn> turns, long maxTokens,
                             String effort, ModelRequest.Tier tier, String summary) {
        agents.beginThinking(agent.id(), Map.of("model", agent.model(), "step", summary));
        ModelResult result = null;
        String error = null;
        long start = System.nanoTime();
        try {
            result = gateway.complete(new ModelRequest(agent.workspaceId(), agent.projectId(), agent.id(),
                    agent.providerId(), agent.model(), context.stable(), context.dynamic(), turns, maxTokens, effort,
                    tier, context.cache()));
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
        Usage usage = USAGE.get();
        if (usage != null) {
            usage.input += result.inputTokens() + result.cacheWriteTokens();
            usage.output += result.outputTokens();
            usage.cacheRead += result.cacheReadTokens();
            if (result.costUsd() != null) usage.cost = usage.cost.add(result.costUsd());
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
        if (open < 0 || close < open) {
            throw new InvalidOutput(text == null || text.isBlank() ? "The reply was empty." : "No JSON object was found in the reply.");
        }
        try {
            JsonNode node = LENIENT.readTree(t.substring(open, close + 1));
            if (!node.isObject()) throw new InvalidOutput("The reply is not a JSON object.");
            return node;
        } catch (RuntimeException e) {
            if (e instanceof InvalidOutput io) throw io;
            // Jackson appends "at [Source: REDACTED ...]": useless to the model and to the CTO.
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\n at \\[Source")[0].strip();
            throw new InvalidOutput("The JSON is malformed: " + why);
        }
    }

    private static String escape(String s) {
        return s.replace("\"", "'");
    }
}

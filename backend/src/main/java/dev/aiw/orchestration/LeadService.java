package dev.aiw.orchestration;

import dev.aiw.agentruntime.AgentJob;
import dev.aiw.agentruntime.AgentJobs;
import dev.aiw.agentruntime.AgentRunner;
import dev.aiw.agentruntime.JobHandler;
import dev.aiw.collaboration.CollaborationService;
import dev.aiw.collaboration.Meeting;
import dev.aiw.collaboration.MeetingEnded;
import dev.aiw.collaboration.MeetingService;
import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.memory.MemoryService;
import dev.aiw.modelgateway.PricingService;
import dev.aiw.modelgateway.Provider;
import dev.aiw.modelgateway.ProviderService;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.organization.Department;
import dev.aiw.organization.RoleTemplate;
import dev.aiw.projects.ProjectService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mode A, steps 2 to 8 of UC-1: the Lead analyzes the specification and asks questions, proposes an architecture
 * and a justified team, the CTO decides, the platform hires, the Lead runs the kickoff meeting and then breaks the
 * project down into assigned tasks. Every Lead step is an agent job; every human decision is audited.
 */
@Service
public class LeadService {
    static final String ANALYZE = "LEAD_ANALYZE";
    static final String ARCHITECT = "LEAD_ARCHITECT";
    static final String PLAN = "LEAD_PLAN";
    /** Assumed daily volume per agent for the cost estimate shown with a proposal. */
    static final long EST_INPUT_TOKENS = 200_000;
    static final long EST_OUTPUT_TOKENS = 20_000;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AgentService agents;
    private final AgentJobs jobs;
    private final AgentRunner runner;
    private final ProjectService projects;
    private final MemoryService memory;
    private final MeetingService meetings;
    private final CollaborationService collaboration;
    private final TaskService tasks;
    private final ProviderService providers;
    private final PricingService pricing;
    private final EventPublisher events;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    LeadService(JdbcClient jdbc, JsonMapper json, AgentService agents, AgentJobs jobs, AgentRunner runner,
                ProjectService projects, MemoryService memory, MeetingService meetings,
                CollaborationService collaboration, TaskService tasks, ProviderService providers,
                PricingService pricing, EventPublisher events, AuditLog audit, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.agents = agents;
        this.jobs = jobs;
        this.runner = runner;
        this.projects = projects;
        this.memory = memory;
        this.meetings = meetings;
        this.collaboration = collaboration;
        this.tasks = tasks;
        this.providers = providers;
        this.pricing = pricing;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
    }

    // ---- queries ----

    public List<Briefing> briefings(String projectId) {
        return jdbc.sql("SELECT * FROM briefings WHERE project_id = ? ORDER BY created_at DESC").param(projectId)
                .query(this::mapBriefing).list();
    }

    public Optional<Briefing> briefing(String briefingId) {
        return jdbc.sql("SELECT * FROM briefings WHERE id = ?").param(briefingId).query(this::mapBriefing).optional();
    }

    public List<HiringProposal> proposals(String briefingId) {
        return jdbc.sql("SELECT * FROM hiring_proposals WHERE briefing_id = ? ORDER BY version DESC").param(briefingId)
                .query(this::mapProposal).list();
    }

    public Optional<HiringProposal> proposal(String proposalId) {
        return jdbc.sql("SELECT * FROM hiring_proposals WHERE id = ?").param(proposalId).query(this::mapProposal).optional();
    }

    /** Briefings waiting for the CTO in a workspace: questions to answer or a team to review. */
    public List<Briefing> waiting(String workspaceId) {
        return jdbc.sql("""
                SELECT * FROM briefings WHERE workspace_id = ? AND status IN ('AWAITING_ANSWERS', 'PROPOSED')
                ORDER BY updated_at DESC
                """).param(workspaceId).query(this::mapBriefing).list();
    }

    // ---- step 1-2: brief the Lead ----

    @Transactional
    public Briefing start(String workspaceId, String projectId, String userId) {
        if (projects.specifications(projectId).isEmpty()) {
            throw ApiException.badRequest("no_specification", "Import a specification before briefing the Lead");
        }
        long open = jdbc.sql("""
                SELECT count(*) FROM briefings WHERE project_id = ?
                AND status IN ('ANALYZING', 'AWAITING_ANSWERS', 'ARCHITECTING', 'PROPOSED')
                """).param(projectId).query(Long.class).single();
        if (open > 0) throw ApiException.conflict("briefing_open", "A briefing is already in progress for this project");
        Agent lead = agents.lead(projectId).orElseThrow(() -> ApiException.conflict("no_lead", "The project has no Lead"));
        String id = Ids.newId();
        jdbc.sql("""
                INSERT INTO briefings (id, workspace_id, project_id, lead_id, status, created_by)
                VALUES (?, ?, ?, ?, 'ANALYZING', ?)
                """).params(id, workspaceId, projectId, lead.id(), userId).update();
        Actor actor = Actor.human(userId);
        events.publish(workspaceId, projectId, "briefing.started", actor, new Subject("briefing", id),
                Map.of("leadId", lead.id()));
        audit.record(workspaceId, actor, "briefing.start", "briefing", id, AuditLog.Decision.RECORDED, Map.of());
        jobs.enqueue(workspaceId, projectId, lead.id(), ANALYZE, Map.of("briefingId", id), id);
        return briefing(id).orElseThrow();
    }

    void analyze(AgentJob job) {
        Briefing b = briefing(job.string("briefingId")).orElseThrow();
        if (!"ANALYZING".equals(b.status())) return;
        Agent lead = runner.agent(job);
        String prompt = """
                The CTO asks you to analyze the project specification (in the system prompt) before anything is built.

                Produce:
                - summary: what is being built, for whom, in 3-6 sentences;
                - functional: the functional requirements, one sentence each;
                - nonFunctional: non-functional requirements (performance, security, compliance, availability...);
                - domains: the business domains or bounded contexts;
                - risks: the main risks with impact and mitigation;
                - questions: up to 8 clarification questions whose answers would change the architecture or the \
                team. Ask only what the specification does not answer. Each question says why it matters.

                JSON shape:
                {"summary": "...", "functional": ["..."], "nonFunctional": ["..."], "domains": ["..."],
                 "risks": [{"risk": "...", "impact": "LOW|MEDIUM|HIGH", "mitigation": "..."}],
                 "questions": [{"text": "...", "why": "..."}]}""";
        JsonNode out = runner.completeJson(job, lead, runner.context(lead, "specification analysis requirements risks"),
                prompt, 12_000, "high", "Specification analysis", node -> {
                    if (!node.path("summary").isString() || node.path("summary").asString().isBlank()) {
                        throw new AgentRunner.InvalidOutput("\"summary\" is required.");
                    }
                    if (!node.path("functional").isArray()) throw new AgentRunner.InvalidOutput("\"functional\" must be an array.");
                    if (node.path("questions").size() > 8) throw new AgentRunner.InvalidOutput("Ask at most 8 questions.");
                });
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("summary", out.path("summary").asString());
        analysis.put("functional", strings(out.path("functional")));
        analysis.put("nonFunctional", strings(out.path("nonFunctional")));
        analysis.put("domains", strings(out.path("domains")));
        List<Map<String, Object>> risks = new ArrayList<>();
        for (JsonNode r : out.path("risks")) {
            risks.add(Map.of("risk", text(r, "risk"), "impact", text(r, "impact"), "mitigation", text(r, "mitigation")));
        }
        analysis.put("risks", risks);
        List<Map<String, Object>> questions = new ArrayList<>();
        int n = 1;
        for (JsonNode q : out.path("questions")) {
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("id", "q" + n++);
            question.put("text", text(q, "text"));
            question.put("why", text(q, "why"));
            question.put("answer", null);
            questions.add(question);
        }
        tx.executeWithoutResult(s -> {
            String next = questions.isEmpty() ? "ARCHITECTING" : "AWAITING_ANSWERS";
            int updated = jdbc.sql("""
                    UPDATE briefings SET status = ?, analysis = ?::jsonb, questions = ?::jsonb, updated_at = now()
                    WHERE id = ? AND status = 'ANALYZING'
                    """).params(next, json.writeValueAsString(analysis), json.writeValueAsString(questions), b.id()).update();
            if (updated == 0) return;
            String thread = collaboration.thread(job.workspaceId(), b.projectId(), "BRIEFING", b.id(), "Lead briefing");
            for (Map<String, Object> q : questions) {
                collaboration.post(thread, Actor.agent(lead.id()), null, "QUESTION", q.get("text") + "\n\nWhy: " + q.get("why"));
            }
            events.publish(job.workspaceId(), b.projectId(), "briefing.analyzed", Actor.agent(lead.id()),
                    new Subject("briefing", b.id()), Map.of("questions", questions.size(), "risks", risks.size()));
            if (questions.isEmpty()) {
                jobs.enqueue(job.workspaceId(), b.projectId(), lead.id(), ARCHITECT, Map.of("briefingId", b.id()), b.id());
            }
        });
    }

    // ---- step 3: the CTO answers ----

    @Transactional
    public Briefing answer(String briefingId, String userId, Map<String, String> answers) {
        Briefing b = briefing(briefingId).orElseThrow(() -> ApiException.notFound("Briefing"));
        if (!"AWAITING_ANSWERS".equals(b.status())) {
            throw ApiException.conflict("not_waiting", "The Lead is not waiting for answers");
        }
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Map<String, Object> q : b.questions()) {
            String a = answers.get((String) q.get("id"));
            if (a == null || a.isBlank()) {
                throw ApiException.badRequest("missing_answer", "Answer every question (write \"no preference\" if needed)");
            }
            Map<String, Object> copy = new LinkedHashMap<>(q);
            copy.put("answer", a.trim());
            questions.add(copy);
        }
        String ws = workspaceOf(b);
        jdbc.sql("UPDATE briefings SET status = 'ARCHITECTING', questions = ?::jsonb, updated_at = now() WHERE id = ?")
                .params(json.writeValueAsString(questions), briefingId).update();
        Actor actor = Actor.human(userId);
        String thread = collaboration.thread(ws, b.projectId(), "BRIEFING", b.id(), "Lead briefing");
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> q : questions) sb.append(q.get("text")).append("\n→ ").append(q.get("answer")).append("\n\n");
        collaboration.post(thread, actor, b.leadId(), "ANSWER", sb.toString().strip());
        events.publish(ws, b.projectId(), "briefing.answered", actor, new Subject("briefing", b.id()),
                Map.of("answers", questions.size()));
        audit.record(ws, actor, "briefing.answer", "briefing", b.id(), AuditLog.Decision.RECORDED, Map.of());
        jobs.enqueue(ws, b.projectId(), b.leadId(), ARCHITECT, Map.of("briefingId", b.id()), b.id());
        return briefing(briefingId).orElseThrow();
    }

    // ---- step 4: architecture and team proposal ----

    void architect(AgentJob job) {
        Briefing b = briefing(job.string("briefingId")).orElseThrow();
        if (!"ARCHITECTING".equals(b.status())) return;
        String revisionOf = job.string("revisionOf");
        if (revisionOf != null && proposal(revisionOf).map(p -> !"REVISING".equals(p.status())).orElse(true)) return;
        Agent lead = runner.agent(job);
        Map<String, RoleTemplate> catalog = agents.catalog(job.workspaceId()).stream()
                .filter(t -> !t.id().equals("rt_cto"))
                .collect(Collectors.toMap(RoleTemplate::id, Function.identity(), (a, c) -> a, LinkedHashMap::new));
        StringBuilder roles = new StringBuilder();
        catalog.values().forEach(t -> roles.append("- ").append(t.id()).append(": ").append(t.title()).append(" [")
                .append(t.department()).append("] ").append(String.join(", ", t.skills())).append("\n"));
        StringBuilder qa = new StringBuilder();
        for (Map<String, Object> q : b.questions()) {
            qa.append("Q: ").append(q.get("text")).append("\nA (CTO): ").append(q.get("answer")).append("\n\n");
        }
        String revision = "";
        if (revisionOf != null) {
            HiringProposal previous = proposal(revisionOf).orElseThrow();
            revision = """

                    The CTO asked you to revise your previous proposal (version %d).
                    <previous_proposal>
                    %s
                    </previous_proposal>
                    <cto_comment>
                    %s
                    </cto_comment>
                    Address the comment; keep what the CTO did not question.""".formatted(previous.version(),
                    json.writeValueAsString(previous.lines()), previous.revisionComment());
        }
        String prompt = """
                Your analysis of the specification:
                <analysis>
                %s
                </analysis>

                Clarifications from the CTO:
                <answers>
                %s</answers>

                Now propose:
                1. architecture: an architecture note in Markdown (components, data, integrations, technology choices \
                with reasons, security, deployment). Be concrete and proportionate to the project.
                2. rationale: two or three sentences explaining why this team fits this project.
                3. roles: the team to hire. You are the Lead and already on the team; do not include yourself. \
                Each role must use a templateId from the catalog below and be justified by a need of this project; \
                quote the specification passage that motivates it when there is one (exact excerpt, under 200 \
                characters). Hire only what the project needs. At most 12 agents in total.

                <catalog>
                %s</catalog>
                %s
                JSON shape:
                {"architecture": "markdown", "rationale": "...",
                 "roles": [{"templateId": "rt_...", "count": 1, "department": "BACKEND", "seniority": "SENIOR",
                            "justification": "...", "quote": "... or null", "skills": ["..."],
                            "responsibilities": ["..."]}]}
                Departments: DIRECTION, ARCHITECTURE, PRODUCT, BACKEND, FRONTEND, QA, SECURITY, DEVOPS, OTHER.
                Seniority: JUNIOR, MID, SENIOR, PRINCIPAL.""".formatted(json.writeValueAsString(b.analysis()),
                qa.isEmpty() ? "(no questions were needed)\n" : qa.toString(), roles, revision);
        JsonNode out = runner.completeJson(job, lead, runner.context(lead, "architecture team"), prompt, 24_000, "high",
                revisionOf == null ? "Architecture and team proposal" : "Revised team proposal", node -> {
                    if (!node.path("architecture").isString() || node.path("architecture").asString().isBlank()) {
                        throw new AgentRunner.InvalidOutput("\"architecture\" is required.");
                    }
                    if (!node.path("roles").isArray() || node.path("roles").isEmpty()) {
                        throw new AgentRunner.InvalidOutput("\"roles\" must list at least one role.");
                    }
                    int total = 0;
                    for (JsonNode r : node.path("roles")) {
                        String id = text(r, "templateId");
                        if (!catalog.containsKey(id)) throw new AgentRunner.InvalidOutput("Unknown templateId \"" + id + "\".");
                        int count = r.path("count").asInt(1);
                        if (count < 1 || count > 4) throw new AgentRunner.InvalidOutput("count must be between 1 and 4.");
                        total += count;
                        if (department(text(r, "department")) == null) {
                            throw new AgentRunner.InvalidOutput("Unknown department \"" + text(r, "department") + "\".");
                        }
                        if (text(r, "justification").isBlank()) throw new AgentRunner.InvalidOutput("Every role needs a justification.");
                    }
                    if (total > 12) throw new AgentRunner.InvalidOutput("At most 12 agents in total.");
                });
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode r : out.path("roles")) {
            RoleTemplate t = catalog.get(text(r, "templateId"));
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("templateId", t.id());
            line.put("title", t.title());
            line.put("count", r.path("count").asInt(1));
            line.put("department", department(text(r, "department")).name());
            String seniority = text(r, "seniority");
            line.put("seniority", Set.of("JUNIOR", "MID", "SENIOR", "PRINCIPAL").contains(seniority) ? seniority : "SENIOR");
            line.put("justification", text(r, "justification"));
            String quote = r.path("quote").isString() ? r.path("quote").asString() : null;
            line.put("quote", quote != null && quoteFound(b.projectId(), quote) ? quote : null);
            List<String> skills = strings(r.path("skills"));
            line.put("skills", skills.isEmpty() ? t.skills() : skills);
            List<String> resp = strings(r.path("responsibilities"));
            line.put("responsibilities", resp.isEmpty() ? t.responsibilities() : resp);
            lines.add(line);
        }
        BigDecimal estimate = estimateDaily(job.workspaceId(), lines);
        tx.executeWithoutResult(s -> {
            Briefing current = briefing(b.id()).orElseThrow();
            if (!"ARCHITECTING".equals(current.status())) return;
            int version = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM hiring_proposals WHERE briefing_id = ?")
                    .param(b.id()).query(Integer.class).single();
            if (revisionOf != null) {
                jdbc.sql("UPDATE hiring_proposals SET status = 'SUPERSEDED' WHERE id = ?").param(revisionOf).update();
            }
            String id = Ids.newId();
            jdbc.sql("""
                    INSERT INTO hiring_proposals (id, workspace_id, project_id, briefing_id, version, status, rationale,
                                                  lines, estimated_daily_usd)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?::jsonb, ?)
                    """).params(id, job.workspaceId(), b.projectId(), b.id(), version, text(out, "rationale"),
                    json.writeValueAsString(lines), estimate).update();
            jdbc.sql("UPDATE briefings SET status = 'PROPOSED', architecture = ?, updated_at = now() WHERE id = ?")
                    .params(out.path("architecture").asString(), b.id()).update();
            int agentsProposed = lines.stream().mapToInt(l -> (Integer) l.get("count")).sum();
            events.publish(job.workspaceId(), b.projectId(), "hiring_proposal.created", Actor.agent(lead.id()),
                    new Subject("hiring_proposal", id), Map.of("briefingId", b.id(), "version", version,
                            "roles", lines.size(), "agents", agentsProposed));
        });
    }

    /** The cited passage must really appear in the specification; otherwise the citation is dropped. */
    private boolean quoteFound(String projectId, String quote) {
        String needle = quote.replaceAll("\\s+", " ").trim().toLowerCase();
        if (needle.length() < 8) return false;
        return projects.specifications(projectId).stream()
                .anyMatch(d -> d.content().replaceAll("\\s+", " ").toLowerCase().contains(needle));
    }

    private BigDecimal estimateDaily(String workspaceId, List<Map<String, Object>> lines) {
        Optional<Provider> provider = providers.defaultProvider(workspaceId);
        if (provider.isEmpty()) return null;
        return pricing.find(workspaceId, provider.get().kind(), ProviderService.DEFAULT_ANTHROPIC_MODEL)
                .map(p -> PricingService.cost(p, EST_INPUT_TOKENS, EST_OUTPUT_TOKENS)
                        .multiply(BigDecimal.valueOf(lines.stream().mapToInt(l -> (Integer) l.get("count")).sum()))
                        .setScale(2, RoundingMode.HALF_UP))
                .orElse(null);
    }

    // ---- step 5: the CTO decides ----

    public record Line(String templateId, int count, Department department, String seniority, List<String> skills,
                       List<String> responsibilities) {}

    /**
     * Accepts the proposal with the CTO's edits: hires the agents, records the architecture in shared memory and,
     * unless declined, has the Lead open the kickoff meeting with the whole team.
     */
    @Transactional
    public List<Agent> accept(String proposalId, String userId, List<Line> lines, boolean kickoff) {
        HiringProposal p = proposal(proposalId).orElseThrow(() -> ApiException.notFound("Proposal"));
        if (!"PENDING".equals(p.status())) throw ApiException.conflict("not_pending", "This proposal is no longer pending");
        Briefing b = briefing(p.briefingId()).orElseThrow();
        String ws = workspaceOf(b);
        List<AgentService.HireRequest> requests = new ArrayList<>();
        for (Line l : lines) {
            if (l.count() < 1) continue;
            if (l.count() > 4) throw ApiException.badRequest("invalid_count", "At most 4 agents per role");
            RoleTemplate t = agents.template(ws, l.templateId())
                    .orElseThrow(() -> ApiException.badRequest("invalid_role", "Unknown role " + l.templateId()));
            for (int i = 0; i < l.count(); i++) {
                requests.add(new AgentService.HireRequest(t.id(), new AgentService.HireOverrides(null,
                        l.department() == null ? t.department() : l.department(),
                        l.seniority() == null ? "SENIOR" : l.seniority(), null,
                        l.skills() == null || l.skills().isEmpty() ? null : l.skills(),
                        l.responsibilities() == null || l.responsibilities().isEmpty() ? null : l.responsibilities(),
                        null, null)));
            }
        }
        if (requests.isEmpty()) throw ApiException.badRequest("empty_team", "Select at least one role to hire");
        if (requests.size() > 16) throw ApiException.badRequest("team_too_large", "At most 16 agents at once");
        List<Agent> hired = agents.hireAll(ws, b.projectId(), userId, requests);
        jdbc.sql("UPDATE hiring_proposals SET status = 'ACCEPTED', decided_by = ?, decided_at = now() WHERE id = ?")
                .params(userId, proposalId).update();
        jdbc.sql("UPDATE briefings SET status = 'APPROVED', updated_at = now() WHERE id = ?").param(b.id()).update();
        Actor actor = Actor.human(userId);
        memory.record(ws, b.projectId(), null, null, "ARCHITECTURE", "Architecture (approved by the CTO)",
                b.architecture(), "BRIEFING", b.id(), actor);
        Map<String, Object> a = b.analysis();
        memory.record(ws, b.projectId(), null, null, "REQUIREMENT", "Requirements from the specification analysis",
                requirements(a, b.questions()), "BRIEFING", b.id(), Actor.agent(b.leadId()));
        Object risks = a.get("risks");
        if (risks instanceof List<?> list && !list.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object r : list) {
                if (r instanceof Map<?, ?> m) {
                    sb.append("- ").append(m.get("risk")).append(" (impact ").append(m.get("impact")).append("): ")
                            .append(m.get("mitigation")).append("\n");
                }
            }
            memory.record(ws, b.projectId(), null, null, "RISK", "Project risks", sb.toString(), "BRIEFING", b.id(),
                    Actor.agent(b.leadId()));
        }
        events.publish(ws, b.projectId(), "hiring_proposal.accepted", actor, new Subject("hiring_proposal", proposalId),
                Map.of("hired", hired.size(), "version", p.version()));
        events.publish(ws, b.projectId(), "briefing.approved", actor, new Subject("briefing", b.id()), Map.of());
        audit.record(ws, actor, "hiring_proposal.accept", "hiring_proposal", proposalId, AuditLog.Decision.APPROVED,
                Map.of("hired", String.valueOf(hired.size()), "edited", String.valueOf(!sameAsProposed(p, lines))));
        if (kickoff) kickoff(ws, b, actor);
        return hired;
    }

    private boolean sameAsProposed(HiringProposal p, List<Line> lines) {
        if (p.lines().size() != lines.size()) return false;
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> o = p.lines().get(i);
            Line l = lines.get(i);
            if (!o.get("templateId").equals(l.templateId()) || !o.get("count").equals(l.count())) return false;
        }
        return true;
    }

    private static String requirements(Map<String, Object> a, List<Map<String, Object>> questions) {
        StringBuilder sb = new StringBuilder(String.valueOf(a.get("summary"))).append("\n\nFunctional:\n");
        if (a.get("functional") instanceof List<?> f) f.forEach(x -> sb.append("- ").append(x).append("\n"));
        sb.append("\nNon-functional:\n");
        if (a.get("nonFunctional") instanceof List<?> f) f.forEach(x -> sb.append("- ").append(x).append("\n"));
        if (!questions.isEmpty()) {
            sb.append("\nClarifications from the CTO:\n");
            questions.forEach(q -> sb.append("- ").append(q.get("text")).append(" → ").append(q.get("answer")).append("\n"));
        }
        return sb.toString();
    }

    @Transactional
    public HiringProposal requestRevision(String proposalId, String userId, String comment) {
        HiringProposal p = proposal(proposalId).orElseThrow(() -> ApiException.notFound("Proposal"));
        if (!"PENDING".equals(p.status())) throw ApiException.conflict("not_pending", "This proposal is no longer pending");
        Briefing b = briefing(p.briefingId()).orElseThrow();
        String ws = workspaceOf(b);
        jdbc.sql("UPDATE hiring_proposals SET status = 'REVISING', revision_comment = ?, decided_by = ?, decided_at = now() WHERE id = ?")
                .params(comment.trim(), userId, proposalId).update();
        jdbc.sql("UPDATE briefings SET status = 'ARCHITECTING', updated_at = now() WHERE id = ?").param(b.id()).update();
        Actor actor = Actor.human(userId);
        String thread = collaboration.thread(ws, b.projectId(), "BRIEFING", b.id(), "Lead briefing");
        collaboration.post(thread, actor, b.leadId(), "RECOMMENDATION", comment.trim());
        events.publish(ws, b.projectId(), "hiring_proposal.revision_requested", actor,
                new Subject("hiring_proposal", proposalId), Map.of("version", p.version()));
        audit.record(ws, actor, "hiring_proposal.revise", "hiring_proposal", proposalId, AuditLog.Decision.RECORDED,
                Map.of("comment", comment.trim()));
        jobs.enqueue(ws, b.projectId(), b.leadId(), ARCHITECT, Map.of("briefingId", b.id(), "revisionOf", proposalId), b.id());
        return proposal(proposalId).orElseThrow();
    }

    @Transactional
    public HiringProposal reject(String proposalId, String userId, String reason) {
        HiringProposal p = proposal(proposalId).orElseThrow(() -> ApiException.notFound("Proposal"));
        if (!"PENDING".equals(p.status())) throw ApiException.conflict("not_pending", "This proposal is no longer pending");
        Briefing b = briefing(p.briefingId()).orElseThrow();
        String ws = workspaceOf(b);
        jdbc.sql("UPDATE hiring_proposals SET status = 'REJECTED', revision_comment = ?, decided_by = ?, decided_at = now() WHERE id = ?")
                .params(reason, userId, proposalId).update();
        jdbc.sql("UPDATE briefings SET status = 'CANCELLED', updated_at = now() WHERE id = ?").param(b.id()).update();
        Actor actor = Actor.human(userId);
        events.publish(ws, b.projectId(), "hiring_proposal.rejected", actor, new Subject("hiring_proposal", proposalId),
                Map.of("version", p.version()));
        audit.record(ws, actor, "hiring_proposal.reject", "hiring_proposal", proposalId, AuditLog.Decision.REJECTED,
                Map.of("reason", reason == null ? "" : reason));
        return proposal(proposalId).orElseThrow();
    }

    // ---- step 7: kickoff ----

    @Transactional
    public Meeting kickoff(String workspaceId, Briefing b, Actor actor) {
        Agent lead = agents.lead(b.projectId()).orElseThrow();
        List<String> team = agents.list(b.projectId()).stream()
                .filter(a -> "ACTIVE".equals(a.status()) && !a.lead()).map(Agent::id).toList();
        if (team.isEmpty()) throw ApiException.conflict("no_team", "Hire at least one agent before the kickoff");
        String agenda = """
                1. Goal of the project and the approved architecture (see shared memory).
                2. What each of you will own, and how your work depends on the others'.
                3. Risks and open questions.
                4. First steps: what must be built first.""";
        return meetings.convene(workspaceId, b.projectId(), "KICKOFF", "Kickoff meeting", agenda, lead.id(), team, 1, actor);
    }

    /** Step 8: once the kickoff minutes are published, the Lead breaks the project down into tasks. */
    @EventListener
    void onMeetingEnded(MeetingEnded event) {
        if (!"KICKOFF".equals(event.purpose())) return;
        agents.lead(event.projectId()).ifPresent(lead -> jobs.enqueue(event.workspaceId(), event.projectId(), lead.id(),
                PLAN, Map.of("meetingId", event.meetingId()), event.meetingId()));
    }

    void plan(AgentJob job) {
        String meetingId = job.string("meetingId");
        if (tasks.hasPlanFrom(job.projectId(), meetingId)) return;
        Agent lead = runner.agent(job);
        Meeting kickoff = meetings.find(meetingId).orElseThrow();
        List<Agent> team = agents.list(job.projectId()).stream().filter(a -> "ACTIVE".equals(a.status())).toList();
        Map<String, Agent> byName = team.stream().collect(Collectors.toMap(a -> a.name().toLowerCase(), Function.identity(), (a, c) -> a));
        StringBuilder people = new StringBuilder();
        team.forEach(a -> people.append("- ").append(a.name()).append(": ").append(a.title()).append(" [")
                .append(a.department()).append("] skills: ").append(String.join(", ", a.skills())).append("\n"));
        String prompt = """
                The kickoff meeting is over. Break the project down into the tasks needed to deliver it, then assign each \
                task to the team member best suited for it.

                <kickoff_minutes>
                %s
                </kickoff_minutes>

                <team>
                %s</team>

                Rules:
                - 5 to 30 tasks, each deliverable by one agent in at most 5 days, ordered so foundations come first.
                - Each task has a short imperative title, a description, and 2 to 6 objective, verifiable acceptance \
                criteria (tests that pass, endpoints that respond, documents that exist...).
                - dependsOn lists the refs of tasks that must be finished first. No cycles.
                - assignee is the exact name of a team member (you may assign yourself coordination tasks only).
                - brief is your message to the assignee: what matters, what to watch, who to coordinate with.
                - estimateDays is your estimate in working days; priority is LOW, MEDIUM, HIGH or CRITICAL.

                JSON shape:
                {"tasks": [{"ref": "t1", "title": "...", "description": "...", "acceptanceCriteria": ["..."],
                            "dependsOn": [], "assignee": "Name", "estimateDays": 2, "priority": "HIGH", "brief": "..."}]}"""
                .formatted(json.writeValueAsString(kickoff.minutes()), people);
        JsonNode out = runner.completeJson(job, lead, runner.context(lead, "architecture decisions plan tasks"), prompt,
                32_000, "high", "Project plan and task assignment", node -> validatePlan(node, byName));
        tx.executeWithoutResult(s -> {
            if (tasks.hasPlanFrom(job.projectId(), meetingId)) return;
            Actor actor = Actor.agent(lead.id());
            Map<String, Task> created = new LinkedHashMap<>();
            Map<String, String> briefs = new HashMap<>();
            for (JsonNode t : out.path("tasks")) {
                Agent assignee = byName.get(text(t, "assignee").toLowerCase());
                String priority = text(t, "priority");
                BigDecimal estimate = t.path("estimateDays").isNumber() ? t.path("estimateDays").decimalValue() : null;
                Task task = tasks.create(job.workspaceId(), job.projectId(), new TaskService.Draft(text(t, "title"),
                        text(t, "description"), strings(t.path("acceptanceCriteria")),
                        Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(priority) ? priority : "MEDIUM",
                        estimate, assignee.id()), "PLAN", meetingId, actor);
                created.put(text(t, "ref"), task);
                briefs.put(text(t, "ref"), text(t, "brief"));
            }
            for (JsonNode t : out.path("tasks")) {
                List<String> deps = strings(t.path("dependsOn")).stream().map(r -> created.get(r).id()).toList();
                Task task = created.get(text(t, "ref"));
                if (!deps.isEmpty()) tasks.addDependencies(task.id(), deps, actor);
                else tasks.settle(task.id(), actor);
            }
            for (Map.Entry<String, Task> e : created.entrySet()) {
                Task task = tasks.find(e.getValue().id()).orElseThrow();
                String brief = briefs.get(e.getKey());
                if (!brief.isBlank() && !task.assigneeId().equals(lead.id())) {
                    tasks.message(task, actor, task.assigneeId(), "ASSIGNMENT", brief);
                }
            }
            events.publish(job.workspaceId(), job.projectId(), "plan.created", actor, new Subject("meeting", meetingId),
                    Map.of("tasks", created.size()));
        });
    }

    private void validatePlan(JsonNode node, Map<String, Agent> byName) {
        JsonNode list = node.path("tasks");
        if (!list.isArray() || list.size() < 1) throw new AgentRunner.InvalidOutput("\"tasks\" must list tasks.");
        if (list.size() > 40) throw new AgentRunner.InvalidOutput("At most 30 tasks.");
        Set<String> refs = new HashSet<>();
        for (JsonNode t : list) {
            String ref = text(t, "ref");
            if (ref.isBlank() || !refs.add(ref)) throw new AgentRunner.InvalidOutput("Every task needs a unique ref.");
            if (text(t, "title").isBlank()) throw new AgentRunner.InvalidOutput("Task " + ref + " has no title.");
            if (strings(t.path("acceptanceCriteria")).isEmpty()) {
                throw new AgentRunner.InvalidOutput("Task " + ref + " has no acceptance criteria.");
            }
            if (!byName.containsKey(text(t, "assignee").toLowerCase())) {
                throw new AgentRunner.InvalidOutput("Task " + ref + ": unknown assignee \"" + text(t, "assignee") + "\".");
            }
        }
        Map<String, List<String>> graph = new HashMap<>();
        for (JsonNode t : list) {
            List<String> deps = strings(t.path("dependsOn"));
            for (String d : deps) {
                if (!refs.contains(d)) throw new AgentRunner.InvalidOutput("Task " + text(t, "ref") + " depends on unknown ref " + d + ".");
            }
            graph.put(text(t, "ref"), deps);
        }
        Map<String, Integer> state = new HashMap<>();
        for (String ref : refs) {
            if (cyclic(ref, graph, state)) throw new AgentRunner.InvalidOutput("The dependencies contain a cycle around " + ref + ".");
        }
    }

    private static boolean cyclic(String node, Map<String, List<String>> graph, Map<String, Integer> state) {
        Integer s = state.get(node);
        if (s != null) return s == 1;
        state.put(node, 1);
        for (String next : graph.getOrDefault(node, List.of())) {
            if (cyclic(next, graph, state)) return true;
        }
        state.put(node, 2);
        return false;
    }

    void failed(AgentJob job, String error) {
        String briefingId = job.string("briefingId");
        if (briefingId == null) return;
        jdbc.sql("""
                UPDATE briefings SET status = 'FAILED', error = ?, updated_at = now()
                WHERE id = ? AND status IN ('ANALYZING', 'ARCHITECTING')
                """).params(error, briefingId).update();
        briefing(briefingId).ifPresent(b -> events.publish(job.workspaceId(), b.projectId(), "briefing.failed",
                Actor.agent(job.agentId()), new Subject("briefing", b.id()), Map.of("error", error)));
    }

    void retried(AgentJob job) {
        String briefingId = job.string("briefingId");
        if (briefingId == null) return;
        String status = ANALYZE.equals(job.type()) ? "ANALYZING" : "ARCHITECTING";
        jdbc.sql("UPDATE briefings SET status = ?, error = NULL, updated_at = now() WHERE id = ? AND status = 'FAILED'")
                .params(status, briefingId).update();
    }

    // ---- helpers ----

    private String workspaceOf(Briefing b) {
        return jdbc.sql("SELECT workspace_id FROM briefings WHERE id = ?").param(b.id()).query(String.class).single();
    }

    private static Department department(String name) {
        try {
            return Department.valueOf(name.trim().toUpperCase());
        } catch (RuntimeException e) {
            return null;
        }
    }

    static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            String s = n.isString() ? n.asString() : n.toString();
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isString() ? v.asString().trim() : v.isMissingNode() || v.isNull() ? "" : v.toString();
    }

    private Briefing mapBriefing(ResultSet rs, int row) throws SQLException {
        String analysis = rs.getString("analysis");
        return new Briefing(rs.getString("id"), rs.getString("project_id"), rs.getString("lead_id"),
                rs.getString("status"), analysis == null ? null : json.readValue(analysis, MAP),
                json.readValue(rs.getString("questions"), LIST), rs.getString("architecture"), rs.getString("error"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private HiringProposal mapProposal(ResultSet rs, int row) throws SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new HiringProposal(rs.getString("id"), rs.getString("project_id"), rs.getString("briefing_id"),
                rs.getInt("version"), rs.getString("status"), rs.getString("rationale"),
                json.readValue(rs.getString("lines"), LIST), rs.getBigDecimal("estimated_daily_usd"),
                rs.getString("revision_comment"), rs.getString("decided_by"),
                decided == null ? null : decided.toInstant(), rs.getTimestamp("created_at").toInstant());
    }

    // ---- job handlers ----

    @Component
    static class AnalyzeHandler implements JobHandler {
        private final LeadService lead;
        AnalyzeHandler(LeadService lead) { this.lead = lead; }
        @Override public String type() { return ANALYZE; }
        @Override public void handle(AgentJob job) { lead.analyze(job); }
        @Override public void onFailed(AgentJob job, String error) { lead.failed(job, error); }
        @Override public void onRetry(AgentJob job) { lead.retried(job); }
    }

    @Component
    static class ArchitectHandler implements JobHandler {
        private final LeadService lead;
        ArchitectHandler(LeadService lead) { this.lead = lead; }
        @Override public String type() { return ARCHITECT; }
        @Override public void handle(AgentJob job) { lead.architect(job); }
        @Override public void onFailed(AgentJob job, String error) { lead.failed(job, error); }
        @Override public void onRetry(AgentJob job) { lead.retried(job); }
    }

    @Component
    static class PlanHandler implements JobHandler {
        private final LeadService lead;
        PlanHandler(LeadService lead) { this.lead = lead; }
        @Override public String type() { return PLAN; }
        @Override public void handle(AgentJob job) { lead.plan(job); }
    }
}

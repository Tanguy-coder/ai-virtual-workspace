package dev.aiw.collaboration;

import dev.aiw.agentruntime.AgentJob;
import dev.aiw.agentruntime.AgentJobs;
import dev.aiw.agentruntime.AgentRunner;
import dev.aiw.agentruntime.JobHandler;
import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.memory.MemoryService;
import dev.aiw.modelgateway.ChatTurn;
import dev.aiw.modelgateway.ModelRequest;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Meetings as a workflow: each participant contributes in turn (one agent job per turn, so a crash resumes at the
 * last completed turn), the CTO can speak between turns, then the organizer writes the minutes. Decisions go to
 * shared memory; action items can become tasks. Participants are shown in the meeting room while it is open.
 */
@Service
public class MeetingService {
    /** A participant with nothing new to add answers this and the meeting moves on. */
    static final String PASS = "PASS";
    /** Earlier contributions are clipped to this many characters in later turns' prompts. */
    private static final int CLIPPED_TURN = 300;

    static final String TURN = "MEETING_TURN";
    static final String SYNTHESIS = "MEETING_SYNTHESIS";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AgentService agents;
    private final AgentJobs jobs;
    private final AgentRunner runner;
    private final CollaborationService collaboration;
    private final MemoryService memory;
    private final EventPublisher events;
    private final AuditLog audit;
    private final ApplicationEventPublisher appEvents;
    private final TransactionTemplate tx;

    MeetingService(JdbcClient jdbc, JsonMapper json, AgentService agents, AgentJobs jobs, AgentRunner runner,
                   CollaborationService collaboration, MemoryService memory, EventPublisher events, AuditLog audit,
                   ApplicationEventPublisher appEvents, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.agents = agents;
        this.jobs = jobs;
        this.runner = runner;
        this.collaboration = collaboration;
        this.memory = memory;
        this.events = events;
        this.audit = audit;
        this.appEvents = appEvents;
        this.tx = tx;
    }

    // ---- queries ----

    public List<Meeting> list(String projectId) {
        return jdbc.sql("SELECT * FROM meetings WHERE project_id = ? ORDER BY started_at DESC LIMIT 100")
                .param(projectId).query(this::map).list();
    }

    public Optional<Meeting> find(String meetingId) {
        return jdbc.sql("SELECT * FROM meetings WHERE id = ?").param(meetingId).query(this::map).optional();
    }

    public List<String> participants(String meetingId) {
        return jdbc.sql("SELECT agent_id FROM meeting_participants WHERE meeting_id = ? ORDER BY turn_order")
                .param(meetingId).query(String.class).list();
    }

    // ---- commands ----

    /**
     * Opens a meeting. The organizer speaks first, then each participant; {@code rounds} full rounds are held
     * before the organizer writes the minutes.
     */
    @Transactional
    public Meeting convene(String workspaceId, String projectId, String purpose, String title, String agenda,
                           String organizerId, List<String> participantIds, int rounds, Actor convener) {
        Agent organizer = agents.find(organizerId).filter(a -> a.projectId().equals(projectId))
                .orElseThrow(() -> ApiException.badRequest("invalid_organizer", "The organizer is not in this project"));
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.add(organizer.id());
        ordered.addAll(participantIds);
        List<Agent> people = new ArrayList<>();
        for (String id : ordered) {
            Agent a = agents.find(id).filter(x -> x.projectId().equals(projectId) && "ACTIVE".equals(x.status()))
                    .orElseThrow(() -> ApiException.badRequest("invalid_participant",
                            "Participants must be active agents of this project"));
            people.add(a);
        }
        if (people.size() < 2) throw ApiException.badRequest("too_few", "A meeting needs at least two agents");
        int boundedRounds = Math.min(Math.max(rounds, 1), 3);
        String id = Ids.newId();
        String threadId = collaboration.thread(workspaceId, projectId, "MEETING", id, title);
        jdbc.sql("""
                INSERT INTO meetings (id, workspace_id, project_id, purpose, title, agenda, organizer_agent_id,
                                      convened_by_kind, convened_by_id, rounds, thread_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, workspaceId, projectId, purpose, title, agenda, organizer.id(), convener.kind().name(),
                convener.id(), boundedRounds, threadId).update();
        for (int i = 0; i < people.size(); i++) {
            jdbc.sql("INSERT INTO meeting_participants (meeting_id, agent_id, turn_order) VALUES (?, ?, ?)")
                    .params(id, people.get(i).id(), i).update();
            agents.setResting(people.get(i).id(), "IN_MEETING", null);
        }
        events.publish(workspaceId, projectId, "meeting.started", convener, new Subject("meeting", id), Map.of(
                "title", title, "purpose", purpose, "organizerId", organizer.id(),
                "participantIds", people.stream().map(Agent::id).toList(), "rounds", boundedRounds));
        audit.record(workspaceId, convener, "meeting.convene", "meeting", id, AuditLog.Decision.RECORDED,
                Map.of("title", title, "participants", String.valueOf(people.size())));
        jobs.enqueue(workspaceId, projectId, organizer.id(), TURN, Map.of("meetingId", id, "round", 1, "index", 0), id);
        return find(id).orElseThrow();
    }

    /** The CTO speaks in an open meeting; following turns see it. */
    @Transactional
    public Message speak(String meetingId, String userId, String content) {
        Meeting m = find(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        if (!"IN_PROGRESS".equals(m.status())) throw ApiException.conflict("meeting_closed", "The meeting is not in progress");
        return collaboration.post(m.threadId(), Actor.human(userId), null, "CONTRIBUTION", content);
    }

    @Transactional
    public Meeting cancel(String meetingId, String userId) {
        Meeting m = find(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        if (!List.of("IN_PROGRESS", "SYNTHESIZING", "FAILED").contains(m.status())) {
            throw ApiException.conflict("meeting_closed", "The meeting is already closed");
        }
        jdbc.sql("UPDATE meetings SET status = 'CANCELLED', ended_at = now() WHERE id = ?").param(meetingId).update();
        jobs.cancelOpen(m.projectId(), TURN, "meetingId", meetingId);
        jobs.cancelOpen(m.projectId(), SYNTHESIS, "meetingId", meetingId);
        release(m);
        Actor actor = Actor.human(userId);
        events.publish(m.workspaceId(), m.projectId(), "meeting.ended", actor, new Subject("meeting", meetingId),
                Map.of("title", m.title(), "outcome", "CANCELLED"));
        audit.record(m.workspaceId(), actor, "meeting.cancel", "meeting", meetingId, AuditLog.Decision.RECORDED, Map.of());
        return find(meetingId).orElseThrow();
    }

    /** Records that an action item became a task (called by orchestration). */
    @Transactional
    public void linkActionItem(String meetingId, int index, String taskId) {
        jdbc.sql("""
                UPDATE meetings SET minutes = jsonb_set(minutes, ARRAY['actionItems', CAST(? AS TEXT), 'taskId'], to_jsonb(CAST(? AS TEXT)))
                WHERE id = ? AND minutes IS NOT NULL
                """).params(index, taskId, meetingId).update();
    }

    // ---- workflow ----

    void turn(AgentJob job) {
        Meeting m = find(job.string("meetingId")).orElseThrow();
        int round = job.integer("round", 1);
        int index = job.integer("index", 0);
        if (!"IN_PROGRESS".equals(m.status()) || m.currentRound() != round || m.turnIndex() != index) return;
        Agent agent = runner.agent(job);
        List<String> people = participants(m.id());
        Map<String, Agent> byId = people.stream().map(id -> agents.find(id).orElseThrow())
                .collect(Collectors.toMap(Agent::id, Function.identity()));
        String prompt = """
                You are in the meeting "%s", organized by %s.

                <agenda>
                %s
                </agenda>

                Participants: %s.

                <transcript>
                %s</transcript>

                It is your turn (round %d of %d). Contribute from your role as %s: what you will own, the concerns, \
                risks and dependencies you see, proposals, and questions for specific people. Disagree openly when you \
                disagree. Do not repeat what was already said. %s Keep it under 200 words, plain text, no preamble.""".formatted(
                m.title(), byId.get(m.organizerId()).name(), m.agenda(),
                people.stream().map(id -> byId.get(id).name() + " (" + byId.get(id).title() + ")")
                        .collect(Collectors.joining(", ")),
                transcript(m.threadId(), byId, people.size()), round, m.rounds(), agent.title(),
                agent.id().equals(m.organizerId()) && round == 1
                        ? "As the organizer, open the meeting: state its goal and what you expect from each person."
                        : round > 1 ? "If you have nothing new to add, reply with exactly " + PASS + "." : "");
        // Each participant repeats the same context every round: cache it only when there are several rounds.
        AgentRunner.Context context = runner.context(agent, m.title() + " " + m.agenda());
        String reply = runner.complete(job, agent, m.rounds() > 1 ? context : context.oneShot(),
                List.of(new ChatTurn(ChatTurn.Role.USER, prompt)), 1_000, "low",
                "Meeting turn — " + m.title()).strip();
        boolean passed = reply.equalsIgnoreCase(PASS) || reply.equalsIgnoreCase(PASS + ".");
        String text = passed ? "(nothing to add)" : reply;
        tx.executeWithoutResult(s -> {
            Meeting current = find(m.id()).orElseThrow();
            if (!"IN_PROGRESS".equals(current.status()) || current.currentRound() != round || current.turnIndex() != index) {
                return;
            }
            collaboration.post(m.threadId(), Actor.agent(agent.id()), null, "CONTRIBUTION", text);
            if (!passed) {
                memory.record(m.workspaceId(), m.projectId(), agent.id(), null, "NOTE", "My contribution to " + m.title(),
                        text, "MEETING", m.id(), Actor.agent(agent.id()));
            }
            boolean lastInRound = index + 1 >= people.size();
            if (!lastInRound) {
                advance(m, round, index + 1, people.get(index + 1), TURN);
            } else if (round < m.rounds()) {
                advance(m, round + 1, 0, people.getFirst(), TURN);
            } else {
                jdbc.sql("UPDATE meetings SET status = 'SYNTHESIZING' WHERE id = ?").param(m.id()).update();
                events.publish(m.workspaceId(), m.projectId(), "meeting.turn", Actor.agent(agent.id()),
                        new Subject("meeting", m.id()), Map.of("round", round, "index", index, "agentId", agent.id(),
                                "next", "minutes"));
                jobs.enqueue(m.workspaceId(), m.projectId(), m.organizerId(), SYNTHESIS, Map.of("meetingId", m.id()), m.id());
            }
        });
    }

    private void advance(Meeting m, int round, int index, String nextAgentId, String type) {
        jdbc.sql("UPDATE meetings SET current_round = ?, turn_index = ? WHERE id = ?").params(round, index, m.id()).update();
        events.publish(m.workspaceId(), m.projectId(), "meeting.turn", Actor.system(), new Subject("meeting", m.id()),
                Map.of("round", round, "index", index, "nextAgentId", nextAgentId));
        jobs.enqueue(m.workspaceId(), m.projectId(), nextAgentId, type,
                Map.of("meetingId", m.id(), "round", round, "index", index), m.id());
    }

    void synthesize(AgentJob job) {
        Meeting m = find(job.string("meetingId")).orElseThrow();
        if (!"SYNTHESIZING".equals(m.status())) return;
        Agent organizer = runner.agent(job);
        List<String> people = participants(m.id());
        Map<String, Agent> byId = people.stream().map(id -> agents.find(id).orElseThrow())
                .collect(Collectors.toMap(Agent::id, Function.identity()));
        Map<String, String> byName = byId.values().stream()
                .collect(Collectors.toMap(a -> a.name().toLowerCase(), Agent::id, (a, b) -> a));
        String prompt = """
                You organized the meeting "%s". Write its minutes from the transcript below. Record only what was \
                actually said; do not invent decisions.

                <agenda>
                %s
                </agenda>

                <transcript>
                %s</transcript>

                JSON shape:
                {"summary": "3-6 sentences",
                 "decisions": [{"title": "short", "detail": "what was decided and why"}],
                 "disagreements": [{"topic": "...", "positions": "who thinks what"}],
                 "actionItems": [{"title": "imperative sentence", "owner": "exact participant name or null", "due": "free text or null"}]}
                Owners must be one of: %s.""".formatted(m.title(), m.agenda(), transcript(m.threadId(), byId, Integer.MAX_VALUE),
                byId.values().stream().map(Agent::name).collect(Collectors.joining(", ")));
        // Extraction from the transcript: the economy model, no specification (docs/09).
        JsonNode minutes = runner.completeJson(job, organizer, runner.context(organizer, m.title() + " " + m.agenda(), 0, 0).oneShot(),
                prompt, 4_000, "low", ModelRequest.Tier.LIGHT, "Minutes — " + m.title(), node -> {
                    if (!node.path("summary").isString()) throw new AgentRunner.InvalidOutput("\"summary\" must be a string.");
                    for (JsonNode item : node.path("actionItems")) {
                        String owner = item.path("owner").isString() ? item.path("owner").asString() : null;
                        if (owner != null && !byName.containsKey(owner.toLowerCase())) {
                            throw new AgentRunner.InvalidOutput("Unknown action item owner \"" + owner + "\".");
                        }
                    }
                });
        Map<String, Object> stored = new HashMap<>();
        stored.put("summary", minutes.path("summary").asString());
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (JsonNode d : minutes.path("decisions")) {
            decisions.add(Map.of("title", text(d, "title"), "detail", text(d, "detail")));
        }
        List<Map<String, Object>> disagreements = new ArrayList<>();
        for (JsonNode d : minutes.path("disagreements")) {
            disagreements.add(Map.of("topic", text(d, "topic"), "positions", text(d, "positions")));
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (JsonNode a : minutes.path("actionItems")) {
            Map<String, Object> item = new HashMap<>();
            item.put("title", text(a, "title"));
            String owner = a.path("owner").isString() ? a.path("owner").asString() : null;
            item.put("ownerAgentId", owner == null ? null : byName.get(owner.toLowerCase()));
            item.put("due", a.path("due").isString() ? a.path("due").asString() : null);
            item.put("taskId", null);
            actions.add(item);
        }
        stored.put("decisions", decisions);
        stored.put("disagreements", disagreements);
        stored.put("actionItems", actions);
        tx.executeWithoutResult(s -> {
            int updated = jdbc.sql("""
                    UPDATE meetings SET status = 'ENDED', minutes = ?::jsonb, ended_at = now()
                    WHERE id = ? AND status = 'SYNTHESIZING'
                    """).params(json.writeValueAsString(stored), m.id()).update();
            if (updated == 0) return;
            for (Map<String, Object> d : decisions) {
                memory.record(m.workspaceId(), m.projectId(), null, null, "DECISION", (String) d.get("title"),
                        (String) d.get("detail"), "MEETING", m.id(), Actor.agent(organizer.id()));
            }
            release(m);
            events.publish(m.workspaceId(), m.projectId(), "meeting.minutes_published", Actor.agent(organizer.id()),
                    new Subject("meeting", m.id()), Map.of("title", m.title(), "decisions", decisions.size(),
                            "actionItems", actions.size()));
            events.publish(m.workspaceId(), m.projectId(), "meeting.ended", Actor.agent(organizer.id()),
                    new Subject("meeting", m.id()), Map.of("title", m.title(), "outcome", "ENDED"));
            appEvents.publishEvent(new MeetingEnded(m.workspaceId(), m.projectId(), m.id(), m.purpose()));
        });
    }

    void failed(AgentJob job, String error) {
        find(job.string("meetingId")).ifPresent(m -> {
            if (!List.of("IN_PROGRESS", "SYNTHESIZING").contains(m.status())) return;
            jdbc.sql("UPDATE meetings SET status = 'FAILED', error = ? WHERE id = ?").params(error, m.id()).update();
            for (String id : participants(m.id())) {
                if (!id.equals(job.agentId())) releaseOne(m, id);
            }
            events.publish(m.workspaceId(), m.projectId(), "meeting.failed", Actor.system(), new Subject("meeting", m.id()),
                    Map.of("title", m.title(), "error", error));
        });
    }

    void retried(AgentJob job) {
        find(job.string("meetingId")).ifPresent(m -> {
            if (!"FAILED".equals(m.status())) return;
            String status = SYNTHESIS.equals(job.type()) ? "SYNTHESIZING" : "IN_PROGRESS";
            jdbc.sql("UPDATE meetings SET status = ?, error = NULL WHERE id = ?").params(status, m.id()).update();
            for (String id : participants(m.id())) agents.setResting(id, "IN_MEETING", null);
            events.publish(m.workspaceId(), m.projectId(), "meeting.resumed", Actor.system(), new Subject("meeting", m.id()),
                    Map.of("title", m.title()));
        });
    }

    private void release(Meeting m) {
        for (String id : participants(m.id())) releaseOne(m, id);
    }

    /** Back to available unless the agent sits in another open meeting. */
    private void releaseOne(Meeting m, String agentId) {
        boolean elsewhere = jdbc.sql("""
                SELECT count(*) FROM meeting_participants p JOIN meetings x ON x.id = p.meeting_id
                WHERE p.agent_id = ? AND x.id <> ? AND x.status IN ('IN_PROGRESS', 'SYNTHESIZING')
                """).params(agentId, m.id()).query(Long.class).single() > 0;
        Agent agent = agents.find(agentId).orElseThrow();
        if (!elsewhere && !"BLOCKED".equals(agent.activity())) agents.setResting(agentId, "AVAILABLE", null);
    }

    /**
     * Meeting transcript. The last {@code fullTurns} contributions (the latest round) are kept whole; earlier ones are
     * clipped to their opening, which keeps each turn's prompt from growing with the whole meeting (docs/09).
     */
    private String transcript(String threadId, Map<String, Agent> byId, int fullTurns) {
        StringBuilder sb = new StringBuilder();
        List<Message> all = collaboration.messages(threadId);
        for (int i = 0; i < all.size(); i++) {
            Message msg = all.get(i);
            String who = switch (msg.authorKind()) {
                case "AGENT" -> {
                    Agent a = byId.get(msg.authorId());
                    yield a == null ? "Agent" : a.name() + " (" + a.title() + ")";
                }
                case "HUMAN" -> "CTO (human)";
                default -> "Platform";
            };
            String content = msg.content();
            boolean recent = i >= all.size() - fullTurns || "HUMAN".equals(msg.authorKind());
            if (!recent && content.length() > CLIPPED_TURN) content = content.substring(0, CLIPPED_TURN) + " […]";
            sb.append("[").append(who).append("]\n").append(content).append("\n\n");
        }
        return sb.isEmpty() ? "(nobody has spoken yet)\n" : sb.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isString() ? v.asString() : v.isMissingNode() || v.isNull() ? "" : v.toString();
    }

    private Meeting map(ResultSet rs, int row) throws SQLException {
        Timestamp ended = rs.getTimestamp("ended_at");
        String minutes = rs.getString("minutes");
        return new Meeting(rs.getString("id"), rs.getString("workspace_id"), rs.getString("project_id"),
                rs.getString("purpose"), rs.getString("title"), rs.getString("agenda"),
                rs.getString("organizer_agent_id"), participants(rs.getString("id")), rs.getString("convened_by_kind"),
                rs.getString("status"),
                rs.getInt("rounds"), rs.getInt("current_round"), rs.getInt("turn_index"), rs.getString("thread_id"),
                minutes == null ? null : json.readValue(minutes, MAP), rs.getString("error"),
                rs.getTimestamp("started_at").toInstant(), ended == null ? null : ended.toInstant());
    }

    // ---- job handlers ----

    @org.springframework.stereotype.Component
    static class TurnHandler implements JobHandler {
        private final MeetingService meetings;

        TurnHandler(MeetingService meetings) {
            this.meetings = meetings;
        }

        @Override public String type() { return TURN; }
        @Override public void handle(AgentJob job) { meetings.turn(job); }
        @Override public void onFailed(AgentJob job, String error) { meetings.failed(job, error); }
        @Override public void onRetry(AgentJob job) { meetings.retried(job); }
    }

    @org.springframework.stereotype.Component
    static class SynthesisHandler implements JobHandler {
        private final MeetingService meetings;

        SynthesisHandler(MeetingService meetings) {
            this.meetings = meetings;
        }

        @Override public String type() { return SYNTHESIS; }
        @Override public void handle(AgentJob job) { meetings.synthesize(job); }
        @Override public void onFailed(AgentJob job, String error) { meetings.failed(job, error); }
        @Override public void onRetry(AgentJob job) { meetings.retried(job); }
    }
}

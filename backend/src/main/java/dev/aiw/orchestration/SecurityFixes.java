package dev.aiw.orchestration;

import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.quality.FixRequested;
import dev.aiw.quality.SecurityService;
import dev.aiw.shared.Actor;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a finding into a fix task (docs/18), by code: the finding's proof and recommendation become the task, the
 * best-fitting agent of the project gets it (department first, then the least loaded). The task then runs like any
 * other, and its submission must make the finding disappear ({@link TaskExecution} reruns the tool).
 */
@Component
class SecurityFixes {
    private static final Logger log = LoggerFactory.getLogger(SecurityFixes.class);

    private final TaskService tasks;
    private final AgentService agents;
    private final SecurityService security;
    private final TransactionTemplate tx;

    SecurityFixes(TaskService tasks, AgentService agents, SecurityService security, TransactionTemplate tx) {
        this.tasks = tasks;
        this.agents = agents;
        this.security = security;
        this.tx = tx;
    }

    @EventListener
    void onFixRequested(FixRequested e) {
        try {
            Optional<Agent> assignee = pick(e.projectId(), e.departments());
            Actor actor = e.requestedBy() != null ? Actor.human(e.requestedBy())
                    : agents.lead(e.projectId()).map(a -> Actor.agent(a.id())).orElse(Actor.system());
            Task task = tx.execute(s -> {
                Task t = tasks.create(e.workspaceId(), e.projectId(), new TaskService.Draft(e.title(), e.description(), e.criteria(),
                        e.priority(), null, assignee.map(Agent::id).orElse(null)), "FINDING", e.findingId(), actor);
                tasks.settle(t.id(), actor);
                return t;
            });
            security.linkTask(e.findingId(), task.id(), task.number());
        } catch (RuntimeException ex) {
            log.warn("Could not create the fix task of finding {}", e.findingId(), ex);
        }
    }

    /** The project's agent whose department fits first; among them, the one with the fewest open tasks. Never the Lead. */
    Optional<Agent> pick(String projectId, List<String> departments) {
        List<Agent> team = agents.list(projectId).stream().filter(a -> !a.lead()).toList();
        if (team.isEmpty()) return Optional.empty();
        Comparator<Agent> byFit = Comparator.comparingInt(a -> {
            int i = departments.indexOf(a.department().name());
            return i < 0 ? departments.size() : i;
        });
        return team.stream().min(byFit.thenComparingLong(a -> openTasks(projectId, a.id())));
    }

    private long openTasks(String projectId, String agentId) {
        return tasks.list(projectId).stream()
                .filter(t -> agentId.equals(t.assigneeId()) && !TaskService.CLOSED.contains(t.status())).count();
    }
}

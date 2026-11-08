package dev.aiw.dashboard;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.events.EventQueries;
import dev.aiw.events.EventRecord;
import dev.aiw.governance.ApprovalService;
import dev.aiw.modelgateway.BudgetService;
import dev.aiw.orchestration.TaskService;
import dev.aiw.organization.AgentService;
import dev.aiw.projects.ProjectService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * CTO dashboard figures. Every number comes from recorded data; features not built yet
 * (tests, vulnerabilities) are absent from the response rather than reported as zero.
 */
@RestController
class DashboardController {
    private final WorkspaceAccess access;
    private final ProjectService projects;
    private final AgentService agents;
    private final BudgetService budgets;
    private final ApprovalService approvals;
    private final EventQueries events;
    private final TaskService tasks;

    DashboardController(WorkspaceAccess access, ProjectService projects, AgentService agents, BudgetService budgets,
                        ApprovalService approvals, EventQueries events, TaskService tasks) {
        this.access = access;
        this.projects = projects;
        this.agents = agents;
        this.budgets = budgets;
        this.approvals = approvals;
        this.events = events;
        this.tasks = tasks;
    }

    record ProjectDashboard(Map<String, Long> agentsByState, long agentCount, BudgetService.Spend spend,
                            BigDecimal dailyLimitUsd, BigDecimal totalLimitUsd, long pendingApprovals,
                            long documents, Map<String, Long> tasksByStatus,
                            List<EventRecord> recentEvents) {}

    @GetMapping("/api/v1/projects/{projectId}/dashboard")
    ProjectDashboard project(@PathVariable String projectId) {
        String workspaceId = access.requireProject(projectId, WorkspaceRole.VIEWER);
        Map<String, Long> byState = agents.countByActivity(projectId);
        BudgetService.Budget budget = budgets.get(projectId);
        return new ProjectDashboard(byState, byState.values().stream().mapToLong(Long::longValue).sum(),
                budgets.spend(projectId), budget.dailyLimitUsd(), budget.totalLimitUsd(),
                approvals.countPending(workspaceId, projectId), projects.documents(projectId).size(),
                tasks.countByStatus(projectId),
                events.list(workspaceId, projectId, -1, 20));
    }

    record WorkspaceDashboard(long activeProjects, long pendingApprovals) {}

    @GetMapping("/api/v1/workspaces/{workspaceId}/dashboard")
    WorkspaceDashboard workspace(@PathVariable String workspaceId) {
        access.require(workspaceId, WorkspaceRole.VIEWER);
        return new WorkspaceDashboard(projects.countActive(workspaceId), approvals.countPending(workspaceId, null));
    }
}

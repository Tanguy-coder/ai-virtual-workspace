package dev.aiw.agentruntime;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.organization.Agent;
import dev.aiw.organization.AgentService;
import dev.aiw.shared.ApiException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RuntimeController {
    private final WorkspaceAccess access;
    private final AgentJobs jobs;
    private final AgentWorker worker;
    private final AgentService agents;

    RuntimeController(WorkspaceAccess access, AgentJobs jobs, AgentWorker worker, AgentService agents) {
        this.access = access;
        this.jobs = jobs;
        this.worker = worker;
        this.agents = agents;
    }

    @GetMapping("/api/v1/projects/{projectId}/jobs")
    List<JobView> list(@PathVariable String projectId, @RequestParam(defaultValue = "false") boolean open) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return jobs.list(projectId, open);
    }

    @PostMapping("/api/v1/jobs/{jobId}/retry")
    JobView retry(@PathVariable String jobId) {
        JobView job = jobs.find(jobId).orElseThrow(() -> ApiException.notFound("Job"));
        String workspaceId = access.requireProject(job.projectId(), WorkspaceRole.MEMBER);
        String userId = access.require(workspaceId, WorkspaceRole.MEMBER);
        return jobs.retry(jobId, userId, worker.handler(job.type()).orElse(null));
    }

    @GetMapping("/api/v1/agents/{agentId}/steps")
    List<AgentStep> steps(@PathVariable String agentId, @RequestParam(defaultValue = "100") int limit) {
        Agent agent = agents.find(agentId).orElseThrow(() -> ApiException.notFound("Agent"));
        access.requireProject(agent.projectId(), WorkspaceRole.VIEWER);
        return jobs.steps(agentId, limit);
    }
}

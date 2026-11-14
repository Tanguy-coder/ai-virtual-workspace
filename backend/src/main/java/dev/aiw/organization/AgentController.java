package dev.aiw.organization;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AgentController {
    private final WorkspaceAccess access;
    private final AgentService agents;
    private final AgentChatService chat;

    AgentController(WorkspaceAccess access, AgentService agents, AgentChatService chat) {
        this.access = access;
        this.agents = agents;
        this.chat = chat;
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/role-templates")
    List<RoleTemplate> catalog(@PathVariable String workspaceId) {
        access.require(workspaceId, WorkspaceRole.VIEWER);
        return agents.catalog(workspaceId);
    }

    record NewTemplate(@NotBlank @Pattern(regexp = "MANAGEMENT|BACKEND|FRONTEND_MOBILE|QUALITY_SECURITY|INFRASTRUCTURE|OTHER") String family,
                       @NotBlank @Size(max = 80) String title, @jakarta.validation.constraints.NotNull Department department,
                       @Size(max = 30) List<@NotBlank @Size(max = 80) String> skills,
                       @Size(max = 30) List<@NotBlank @Size(max = 300) String> responsibilities) {}

    @PostMapping("/api/v1/workspaces/{workspaceId}/role-templates")
    @ResponseStatus(HttpStatus.CREATED)
    RoleTemplate createTemplate(@PathVariable String workspaceId, @Valid @RequestBody NewTemplate body) {
        String userId = access.require(workspaceId, WorkspaceRole.ADMIN);
        return agents.createTemplate(workspaceId, userId, body.family(), body.title(), body.department(),
                body.skills() == null ? List.of() : body.skills(),
                body.responsibilities() == null ? List.of() : body.responsibilities());
    }

    @GetMapping("/api/v1/projects/{projectId}/agents")
    List<Agent> list(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return agents.list(projectId);
    }

    record Hire(@NotBlank String roleTemplateId, @Size(max = 80) String name, Department department,
                @Pattern(regexp = "JUNIOR|MID|SENIOR|PRINCIPAL") String seniority,
                @Min(0) @Max(40) Integer simulatedYears, @Size(max = 30) List<@Size(max = 80) String> skills,
                @Size(max = 30) List<@Size(max = 300) String> responsibilities, String providerId,
                @Size(max = 200) String model) {}

    @PostMapping("/api/v1/projects/{projectId}/agents")
    @ResponseStatus(HttpStatus.CREATED)
    Agent hire(@PathVariable String projectId, @Valid @RequestBody Hire body) {
        String workspaceId = access.requireProject(projectId, WorkspaceRole.MEMBER);
        String userId = access.require(workspaceId, WorkspaceRole.MEMBER);
        return agents.hire(workspaceId, projectId, userId, body.roleTemplateId(),
                new AgentService.HireOverrides(body.name(), body.department(), body.seniority(), body.simulatedYears(), body.skills(),
                        body.responsibilities(), body.providerId(), body.model()));
    }

    @GetMapping("/api/v1/agents/{agentId}")
    Agent get(@PathVariable String agentId) {
        Agent agent = load(agentId, WorkspaceRole.VIEWER);
        return agent;
    }

    record BindModel(@NotBlank String providerId, @NotBlank @Size(max = 200) String model) {}

    @PutMapping("/api/v1/agents/{agentId}/model")
    Agent bindModel(@PathVariable String agentId, @Valid @RequestBody BindModel body) {
        load(agentId, WorkspaceRole.MEMBER);
        return agents.updateModel(agentId, currentUser(agentId), body.providerId(), body.model());
    }

    /** The CTO hands the choice back: the Lead picks the model that fits this agent's mission (docs/15). */
    @PostMapping("/api/v1/agents/{agentId}/model/auto")
    Agent autoModel(@PathVariable String agentId) {
        load(agentId, WorkspaceRole.MEMBER);
        if (!agents.assignModel(agentId, true)) {
            throw dev.aiw.shared.ApiException.conflict("no_model", "No provider offers a usable model yet: add a provider with its key first");
        }
        return agents.find(agentId).orElseThrow();
    }

    record DepartmentChange(@jakarta.validation.constraints.NotNull Department department) {}

    @PutMapping("/api/v1/agents/{agentId}/department")
    Agent department(@PathVariable String agentId, @Valid @RequestBody DepartmentChange body) {
        load(agentId, WorkspaceRole.MEMBER);
        return agents.changeDepartment(agentId, currentUser(agentId), body.department());
    }

    record Instructions(@NotBlank @Size(max = 50_000) String systemInstructions) {}

    @PutMapping("/api/v1/agents/{agentId}/instructions")
    Agent instructions(@PathVariable String agentId, @Valid @RequestBody Instructions body) {
        load(agentId, WorkspaceRole.MEMBER);
        return agents.updateInstructions(agentId, currentUser(agentId), body.systemInstructions());
    }

    record Status(@Pattern(regexp = "ACTIVE|PAUSED|DISMISSED") @NotBlank String status) {}

    @PutMapping("/api/v1/agents/{agentId}/status")
    Agent status(@PathVariable String agentId, @Valid @RequestBody Status body) {
        load(agentId, WorkspaceRole.MEMBER);
        return agents.setStatus(agentId, currentUser(agentId), body.status());
    }

    @GetMapping("/api/v1/agents/{agentId}/messages")
    List<AgentMessage> messages(@PathVariable String agentId, @RequestParam(defaultValue = "200") int limit) {
        load(agentId, WorkspaceRole.VIEWER);
        return chat.history(agentId, limit);
    }

    record Post(@NotBlank @Size(max = 100_000) String content) {}

    @PostMapping("/api/v1/agents/{agentId}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AgentMessage post(@PathVariable String agentId, @Valid @RequestBody Post body) {
        load(agentId, WorkspaceRole.MEMBER);
        return chat.post(agentId, currentUser(agentId), body.content());
    }

    private Agent load(String agentId, WorkspaceRole role) {
        Agent agent = agents.find(agentId).orElseThrow(() -> ApiException.notFound("Agent"));
        try {
            access.require(agent.workspaceId(), role);
        } catch (ApiException e) {
            if ("not_found".equals(e.code())) throw ApiException.notFound("Agent");
            throw e;
        }
        return agent;
    }

    private String currentUser(String agentId) {
        return dev.aiw.shared.CurrentUser.id();
    }
}

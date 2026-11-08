package dev.aiw.collaboration;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.organization.AgentService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CollaborationController {
    private final WorkspaceAccess access;
    private final CollaborationService collaboration;
    private final MeetingService meetings;
    private final AgentService agents;

    CollaborationController(WorkspaceAccess access, CollaborationService collaboration, MeetingService meetings,
                            AgentService agents) {
        this.access = access;
        this.collaboration = collaboration;
        this.meetings = meetings;
        this.agents = agents;
    }

    @GetMapping("/api/v1/projects/{projectId}/threads")
    List<ThreadView> threads(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return collaboration.threads(projectId);
    }

    @GetMapping("/api/v1/threads/{threadId}/messages")
    List<Message> messages(@PathVariable String threadId) {
        access.requireProject(collaboration.projectOfThread(threadId), WorkspaceRole.VIEWER);
        return collaboration.messages(threadId);
    }

    record Post(@NotBlank @Size(max = 20_000) String content, String toAgentId, String type) {}

    /** The CTO writes in a thread (task or briefing discussion). */
    @PostMapping("/api/v1/threads/{threadId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    Message post(@PathVariable String threadId, @Valid @RequestBody Post body) {
        String projectId = collaboration.projectOfThread(threadId);
        String ws = access.requireProject(projectId, WorkspaceRole.MEMBER);
        String userId = access.require(ws, WorkspaceRole.MEMBER);
        if (body.toAgentId() != null && agents.find(body.toAgentId()).filter(a -> a.projectId().equals(projectId)).isEmpty()) {
            throw ApiException.badRequest("invalid_recipient", "The recipient is not in this project");
        }
        return collaboration.post(threadId, Actor.human(userId), body.toAgentId(),
                body.type() == null ? "RECOMMENDATION" : body.type(), body.content());
    }

    @GetMapping("/api/v1/projects/{projectId}/conversations")
    List<Message> conversations(@PathVariable String projectId, @RequestParam(defaultValue = "50") int limit) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return collaboration.recentAgentMessages(projectId, limit);
    }

    @GetMapping("/api/v1/projects/{projectId}/meetings")
    List<Meeting> meetings(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return meetings.list(projectId);
    }

    record MeetingDetail(Meeting meeting, List<String> participantIds, List<Message> messages) {}

    @GetMapping("/api/v1/meetings/{meetingId}")
    MeetingDetail meeting(@PathVariable String meetingId) {
        Meeting m = load(meetingId, WorkspaceRole.VIEWER);
        return new MeetingDetail(m, meetings.participants(meetingId), collaboration.messages(m.threadId()));
    }

    record Convene(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 10_000) String agenda,
                   @NotBlank String organizerId, @NotEmpty @Size(max = 12) List<String> participantIds,
                   @Min(1) @Max(3) Integer rounds) {}

    @PostMapping("/api/v1/projects/{projectId}/meetings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Meeting convene(@PathVariable String projectId, @Valid @RequestBody Convene body) {
        String ws = access.requireProject(projectId, WorkspaceRole.MEMBER);
        String userId = access.require(ws, WorkspaceRole.MEMBER);
        return meetings.convene(ws, projectId, "ADHOC", body.title().trim(), body.agenda().trim(), body.organizerId(),
                body.participantIds(), body.rounds() == null ? 1 : body.rounds(), Actor.human(userId));
    }

    record Speak(@NotBlank @Size(max = 10_000) String content) {}

    @PostMapping("/api/v1/meetings/{meetingId}/speak")
    @ResponseStatus(HttpStatus.CREATED)
    Message speak(@PathVariable String meetingId, @Valid @RequestBody Speak body) {
        Meeting m = load(meetingId, WorkspaceRole.MEMBER);
        return meetings.speak(meetingId, access.require(m.workspaceId(), WorkspaceRole.MEMBER), body.content());
    }

    @PostMapping("/api/v1/meetings/{meetingId}/cancel")
    Meeting cancel(@PathVariable String meetingId) {
        Meeting m = load(meetingId, WorkspaceRole.MEMBER);
        return meetings.cancel(meetingId, access.require(m.workspaceId(), WorkspaceRole.MEMBER));
    }

    private Meeting load(String meetingId, WorkspaceRole role) {
        Meeting m = meetings.find(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        access.requireProject(m.projectId(), role);
        return m;
    }
}

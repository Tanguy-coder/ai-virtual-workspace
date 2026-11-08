package dev.aiw.events;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/events")
class EventController {
    private final WorkspaceAccess access;
    private final EventQueries queries;

    EventController(WorkspaceAccess access, EventQueries queries) {
        this.access = access;
        this.queries = queries;
    }

    @GetMapping
    List<EventRecord> list(@PathVariable String workspaceId,
                           @RequestParam(required = false) String projectId,
                           @RequestParam(defaultValue = "-1") long afterSeq,
                           @RequestParam(defaultValue = "100") int limit,
                           @RequestParam(required = false) String subjectId) {
        access.require(workspaceId, WorkspaceRole.VIEWER);
        if (subjectId != null && !subjectId.isBlank()) return queries.forSubject(workspaceId, subjectId, limit);
        return queries.list(workspaceId, projectId, afterSeq, limit);
    }
}

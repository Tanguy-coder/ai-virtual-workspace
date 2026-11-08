package dev.aiw.memory;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.shared.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
class MemoryController {
    private final WorkspaceAccess access;
    private final MemoryService memory;

    MemoryController(WorkspaceAccess access, MemoryService memory) {
        this.access = access;
        this.memory = memory;
    }

    @GetMapping("/api/v1/projects/{projectId}/memory")
    List<MemoryEntry> search(@PathVariable String projectId, @RequestParam(required = false) String kind,
                             @RequestParam(required = false) String q) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return memory.search(projectId, kind == null || kind.isBlank() ? null : kind, q, 200);
    }

    @GetMapping("/api/v1/projects/{projectId}/memory/{entryKey}/history")
    List<MemoryEntry> history(@PathVariable String projectId, @PathVariable String entryKey) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return memory.history(projectId, entryKey);
    }

    record Entry(@NotBlank String kind, @NotBlank @Size(max = 200) String title,
                 @NotBlank @Size(max = 50_000) String content) {}

    @PostMapping("/api/v1/projects/{projectId}/memory")
    @ResponseStatus(HttpStatus.CREATED)
    MemoryEntry create(@PathVariable String projectId, @Valid @RequestBody Entry body) {
        String ws = access.requireProject(projectId, WorkspaceRole.MEMBER);
        String userId = access.require(ws, WorkspaceRole.MEMBER);
        return memory.record(ws, projectId, null, null, body.kind(), body.title(), body.content(), "HUMAN", null,
                Actor.human(userId));
    }

    @PutMapping("/api/v1/projects/{projectId}/memory/{entryKey}")
    MemoryEntry revise(@PathVariable String projectId, @PathVariable String entryKey, @Valid @RequestBody Entry body) {
        String ws = access.requireProject(projectId, WorkspaceRole.MEMBER);
        String userId = access.require(ws, WorkspaceRole.MEMBER);
        return memory.record(ws, projectId, null, entryKey, body.kind(), body.title(), body.content(), "HUMAN", null,
                Actor.human(userId));
    }
}

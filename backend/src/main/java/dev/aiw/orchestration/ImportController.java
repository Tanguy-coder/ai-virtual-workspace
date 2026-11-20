package dev.aiw.orchestration;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.governance.SecretVault;
import dev.aiw.projects.ProjectService;
import dev.aiw.scm.RepoImport;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Existing code for a project (docs/17): cloned from a Git repository or imported from a folder of the import
 * directory. The platform writes a map of the code as a specification document, so that the Lead plans changes to the
 * code rather than a rewrite, and agents read the map instead of exploring the repository.
 */
@RestController
class ImportController {
    private final WorkspaceAccess access;
    private final RepoImport imports;
    private final SecretVault vault;
    private final ProjectService projects;
    private final EventPublisher events;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    ImportController(WorkspaceAccess access, RepoImport imports, SecretVault vault, ProjectService projects,
                     EventPublisher events, AuditLog audit, TransactionTemplate tx) {
        this.access = access;
        this.imports = imports;
        this.vault = vault;
        this.projects = projects;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
    }

    /** Folders placed in the import directory (mounted from the host's ./imports with Docker Compose). */
    @GetMapping("/api/v1/workspaces/{workspaceId}/import-folders")
    Map<String, Object> folders(@PathVariable String workspaceId) {
        access.require(workspaceId, WorkspaceRole.MEMBER);
        return Map.of("directory", imports.importDir().toString(), "folders", imports.folders());
    }

    record ImportRequest(@NotNull RepoImport.Kind kind, @Size(max = 500) String url, @Size(max = 200) String branch,
                         @Size(max = 500) String token, @Size(max = 200) String folder) {}

    @PostMapping("/api/v1/projects/{projectId}/repo/import")
    Map<String, Object> importCode(@PathVariable String projectId, @Valid @RequestBody ImportRequest body) {
        String workspaceId = access.workspaceOfProject(projectId);
        String userId = access.require(workspaceId, WorkspaceRole.MEMBER);
        RepoImport.Imported done;
        try {
            done = imports.importInto(workspaceId, projectId,
                    new RepoImport.Source(body.kind(), body.url(), body.branch(), body.token(), body.folder()));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("import_failed", e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiException.conflict("import_refused", e.getMessage());
        }
        Actor actor = Actor.human(userId);
        String source = body.kind() == RepoImport.Kind.GIT ? body.url().strip() : "folder " + body.folder();
        tx.executeWithoutResult(s -> {
            if (body.kind() == RepoImport.Kind.GIT && body.token() != null && !body.token().isBlank()) {
                // Kept for a later push (with the CTO's approval); never in the repository or a log.
                imports.rememberToken(projectId, vault.store(workspaceId, projectId, "git-remote", body.token().strip()));
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("source", source);
            payload.put("branch", done.sourceBranch());
            payload.put("files", done.files());
            payload.put("head", done.head().substring(0, Math.min(7, done.head().length())));
            events.publish(workspaceId, projectId, "repository.imported", actor, new Subject("project", projectId), payload);
            audit.record(workspaceId, actor, "repository.import", "project", projectId, AuditLog.Decision.RECORDED,
                    Map.of("source", source, "files", String.valueOf(done.files())));
        });
        projects.addDocument(workspaceId, projectId, userId, "SPECIFICATION", "Existing code (repository map)", "text/markdown",
                done.map());
        return Map.of("files", done.files(), "branch", done.sourceBranch(), "head", done.head(), "map", done.map());
    }
}

package dev.aiw.quality;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Security Center API (docs/18): findings with their proof, scans, the CTO's decisions. */
@RestController
class SecurityController {
    private final WorkspaceAccess access;
    private final SecurityService security;

    SecurityController(WorkspaceAccess access, SecurityService security) {
        this.access = access;
        this.security = security;
    }

    @GetMapping("/api/v1/projects/{projectId}/security")
    Map<String, Object> summary(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return security.summary(projectId);
    }

    @GetMapping("/api/v1/projects/{projectId}/security/findings")
    List<Finding> findings(@PathVariable String projectId, @RequestParam(required = false) String status) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return security.findings(projectId, status == null || status.isBlank() ? null : status.toUpperCase());
    }

    @GetMapping("/api/v1/projects/{projectId}/security/scans")
    List<QualityScan> scans(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return security.scans(projectId, 100);
    }

    /** "Scan now": the static scans of develop, in the background. */
    @PostMapping("/api/v1/projects/{projectId}/security/scans")
    Map<String, Object> scan(@PathVariable String projectId) {
        String ws = access.requireProject(projectId, WorkspaceRole.MEMBER);
        if (!security.enabled()) throw ApiException.conflict("scans_disabled", "Scans are disabled on this platform (aiw.security.scans-enabled)");
        security.requestStaticScan(ws, projectId, "cto");
        return Map.of("requested", true);
    }

    @PostMapping("/api/v1/projects/{projectId}/security/findings/{findingId}/fix-task")
    Finding fixTask(@PathVariable String projectId, @PathVariable String findingId) {
        String ws = access.requireProject(projectId, WorkspaceRole.MEMBER);
        return security.requestFix(ws, projectId, findingId, Actor.human(CurrentUser.id()));
    }

    record AcceptRisk(String reason) {}

    /** Accepting a risk is a governance decision: admins and owners only, audited. */
    @PostMapping("/api/v1/projects/{projectId}/security/findings/{findingId}/accept-risk")
    Finding acceptRisk(@PathVariable String projectId, @PathVariable String findingId, @RequestBody AcceptRisk body) {
        String ws = access.requireProject(projectId, WorkspaceRole.ADMIN);
        return security.acceptRisk(ws, projectId, findingId, CurrentUser.id(), body == null ? null : body.reason());
    }
}

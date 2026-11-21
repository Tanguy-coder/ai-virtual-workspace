package dev.aiw.delivery;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.CurrentUser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deployment Center API (docs/18). Starting a deployment, rolling back, stopping, releasing and pushing are decisions:
 * admins and owners only, each click is the explicit approval and goes to the audit log.
 */
@RestController
class DeliveryController {
    private final WorkspaceAccess access;
    private final DeliveryService delivery;
    private final ReleaseService releases;

    DeliveryController(WorkspaceAccess access, DeliveryService delivery, ReleaseService releases) {
        this.access = access;
        this.delivery = delivery;
        this.releases = releases;
    }

    @GetMapping("/api/v1/projects/{projectId}/delivery")
    Map<String, Object> overview(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("environments", delivery.environments(projectId));
        out.put("deployments", delivery.deployments(projectId, 50));
        out.put("releases", releases.releases(projectId));
        out.put("remote", releases.remote(projectId).orElse(null));
        try {
            out.put("proposal", releases.proposal(projectId));
        } catch (ApiException | dev.aiw.scm.GitService.GitException e) {
            out.put("proposal", null); // no repository yet
        }
        return out;
    }

    @GetMapping("/api/v1/deployments/{deploymentId}/log")
    Map<String, Object> log(@PathVariable String deploymentId) {
        Deployment d = delivery.find(deploymentId).orElseThrow(() -> ApiException.notFound("Deployment"));
        access.requireProject(d.projectId(), WorkspaceRole.VIEWER);
        return Map.of("log", delivery.log(deploymentId).orElse(""));
    }

    record DeployBody(String ref) {}

    @PostMapping("/api/v1/projects/{projectId}/deployments")
    Deployment deploy(@PathVariable String projectId, @RequestBody(required = false) DeployBody body) {
        String ws = access.requireProject(projectId, WorkspaceRole.ADMIN);
        String ref = body == null || body.ref() == null || body.ref().isBlank() ? "main" : body.ref().strip();
        return delivery.request(ws, projectId, ref, null, Actor.human(CurrentUser.id()), null, null);
    }

    @PostMapping("/api/v1/projects/{projectId}/deployments/{deploymentId}/rollback")
    Deployment rollback(@PathVariable String projectId, @PathVariable String deploymentId) {
        String ws = access.requireProject(projectId, WorkspaceRole.ADMIN);
        return delivery.rollback(ws, projectId, deploymentId, CurrentUser.id());
    }

    @PostMapping("/api/v1/projects/{projectId}/environments/test/stop")
    Environment stop(@PathVariable String projectId) {
        String ws = access.requireProject(projectId, WorkspaceRole.ADMIN);
        return delivery.stop(ws, projectId, CurrentUser.id());
    }

    record ReleaseBody(String bump) {}

    @PostMapping("/api/v1/projects/{projectId}/releases")
    Release release(@PathVariable String projectId, @RequestBody ReleaseBody body) {
        String ws = access.requireProject(projectId, WorkspaceRole.ADMIN);
        return releases.create(ws, projectId, body == null || body.bump() == null ? "minor" : body.bump(), Actor.human(CurrentUser.id()));
    }

    record RemoteBody(String provider, String url, String token) {}

    @PutMapping("/api/v1/projects/{projectId}/remote")
    ReleaseService.Remote remote(@PathVariable String projectId, @RequestBody RemoteBody body) {
        String ws = access.requireProject(projectId, WorkspaceRole.ADMIN);
        return releases.setRemote(ws, projectId, body.provider() == null ? "GITLAB" : body.provider().toUpperCase(), body.url(), body.token(), CurrentUser.id());
    }

    @PostMapping("/api/v1/projects/{projectId}/remote/push")
    ReleaseService.Remote push(@PathVariable String projectId) {
        String ws = access.requireProject(projectId, WorkspaceRole.ADMIN);
        return releases.push(ws, projectId, CurrentUser.id(), "the CTO's push");
    }
}

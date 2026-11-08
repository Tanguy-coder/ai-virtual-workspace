package dev.aiw.governance;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
class GovernanceController {
    private final WorkspaceAccess access;
    private final ApprovalService approvals;
    private final AuditLog audit;

    GovernanceController(WorkspaceAccess access, ApprovalService approvals, AuditLog audit) {
        this.access = access;
        this.approvals = approvals;
        this.audit = audit;
    }

    @GetMapping("/approvals")
    List<Approval> list(@PathVariable String workspaceId, @RequestParam(defaultValue = "PENDING") String status) {
        access.require(workspaceId, WorkspaceRole.VIEWER);
        return approvals.list(workspaceId, status);
    }

    record Decision(boolean approve, @Size(max = 2000) String reason) {}

    @PostMapping("/approvals/{approvalId}/decision")
    Approval decide(@PathVariable String workspaceId, @PathVariable String approvalId, @RequestBody Decision body) {
        String userId = access.require(workspaceId, WorkspaceRole.ADMIN);
        return approvals.decide(workspaceId, approvalId, userId, body.approve(), body.reason());
    }

    @GetMapping("/audit/verify")
    Map<String, Object> verify(@PathVariable String workspaceId) {
        access.require(workspaceId, WorkspaceRole.ADMIN);
        long broken = audit.verify(workspaceId);
        return broken < 0 ? Map.of("intact", true) : Map.of("intact", false, "firstBrokenSeq", broken);
    }
}

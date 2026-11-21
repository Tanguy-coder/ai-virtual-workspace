package dev.aiw.identity;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.AuditLog;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.CurrentUser;
import dev.aiw.shared.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Several humans per workspace (docs/18). Owners and admins invite by link (no e-mail is sent: the inviter passes the
 * link on); the invitation is bound to an e-mail address and expires after 7 days. Roles: owner and admin decide
 * (approvals, members, providers), member works, viewer reads. A workspace always keeps an owner.
 */
@RestController
class MemberController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DAYS = 7;

    private final JdbcClient jdbc;
    private final WorkspaceAccess access;
    private final AuditLog audit;
    private final EventPublisher events;

    MemberController(JdbcClient jdbc, WorkspaceAccess access, AuditLog audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.events = events;
    }

    record Member(String userId, String email, String displayName, String role, Instant since) {}

    @GetMapping("/api/v1/workspaces/{workspaceId}/members")
    List<Member> members(@PathVariable String workspaceId) {
        access.require(workspaceId, WorkspaceRole.VIEWER);
        return jdbc.sql("""
                SELECT u.id, u.email, u.display_name, m.role, m.created_at FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.workspace_id = ?
                ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 WHEN 'MEMBER' THEN 2 ELSE 3 END, u.display_name
                """).param(workspaceId).query((rs, i) -> new Member(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant())).list();
    }

    // ---- invitations

    record InviteBody(String email, String role) {}

    record Invitation(String id, String email, String role, Instant expiresAt, Instant createdAt, String token) {}

    @PostMapping("/api/v1/workspaces/{workspaceId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Invitation invite(@PathVariable String workspaceId, @RequestBody InviteBody body) {
        String userId = access.require(workspaceId, WorkspaceRole.ADMIN);
        String email = body == null || body.email() == null ? "" : body.email().strip();
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || email.length() > 254) throw ApiException.badRequest("bad_email", "Give a valid e-mail address");
        WorkspaceRole role = role(body.role());
        if (role == WorkspaceRole.OWNER) throw ApiException.badRequest("bad_role", "Invite as admin, member or viewer; an owner can then promote");
        if (role == WorkspaceRole.ADMIN && myRole(workspaceId, userId) != WorkspaceRole.OWNER) throw ApiException.forbidden();
        boolean member = jdbc.sql("SELECT count(*) FROM memberships m JOIN users u ON u.id = m.user_id WHERE m.workspace_id = ? AND lower(u.email) = lower(?)")
                .params(workspaceId, email).query(Long.class).single() > 0;
        if (member) throw ApiException.conflict("already_member", "This person is already a member");
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String id = Ids.newId();
        Instant expires = Instant.now().plus(DAYS, ChronoUnit.DAYS);
        jdbc.sql("INSERT INTO invitations (id, workspace_id, email, role, token_hash, invited_by, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, workspaceId, email, role.name(), hash(token), userId, java.sql.Timestamp.from(expires)).update();
        Actor actor = Actor.human(userId);
        audit.record(workspaceId, actor, "member.invite", "invitation", id, AuditLog.Decision.RECORDED, Map.of("role", role.name()));
        events.publish(workspaceId, null, "member.invited", actor, new Subject("invitation", id), Map.of("role", role.name()));
        return new Invitation(id, email, role.name(), expires, Instant.now(), token);
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/invitations")
    List<Invitation> invitations(@PathVariable String workspaceId) {
        access.require(workspaceId, WorkspaceRole.ADMIN);
        return jdbc.sql("""
                SELECT id, email, role, expires_at, created_at FROM invitations
                WHERE workspace_id = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now() ORDER BY created_at DESC
                """).param(workspaceId).query((rs, i) -> new Invitation(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), null)).list();
    }

    @DeleteMapping("/api/v1/workspaces/{workspaceId}/invitations/{invitationId}")
    @Transactional
    Map<String, Object> revoke(@PathVariable String workspaceId, @PathVariable String invitationId) {
        String userId = access.require(workspaceId, WorkspaceRole.ADMIN);
        int n = jdbc.sql("UPDATE invitations SET revoked_at = now() WHERE id = ? AND workspace_id = ? AND accepted_at IS NULL AND revoked_at IS NULL")
                .params(invitationId, workspaceId).update();
        if (n == 0) throw ApiException.notFound("Invitation");
        audit.record(workspaceId, Actor.human(userId), "member.invite_revoke", "invitation", invitationId, AuditLog.Decision.RECORDED, Map.of());
        return Map.of("revoked", true);
    }

    record Preview(String workspaceName, String role, String email, boolean valid, String reason) {}

    /** What the link is for; readable before signing in (the token is the secret). */
    @GetMapping("/api/v1/invitations/{token}")
    Preview preview(@PathVariable String token) {
        return jdbc.sql("""
                SELECT w.name, i.role, i.email, i.expires_at, i.accepted_at, i.revoked_at FROM invitations i JOIN workspaces w ON w.id = i.workspace_id
                WHERE i.token_hash = ?
                """).param(hash(token)).query((rs, n) -> {
            String reason = rs.getTimestamp(6) != null ? "revoked" : rs.getTimestamp(5) != null ? "already used"
                    : rs.getTimestamp(4).toInstant().isBefore(Instant.now()) ? "expired" : null;
            return new Preview(rs.getString(1), rs.getString(2), rs.getString(3), reason == null, reason);
        }).optional().orElseThrow(() -> ApiException.notFound("Invitation"));
    }

    @PostMapping("/api/v1/invitations/{token}/accept")
    @Transactional
    Map<String, Object> accept(@PathVariable String token) {
        String userId = CurrentUser.id();
        record Row(String id, String workspaceId, String email, String role, Instant expires, Instant accepted, Instant revoked) {}
        Row inv = jdbc.sql("SELECT id, workspace_id, email, role, expires_at, accepted_at, revoked_at FROM invitations WHERE token_hash = ? FOR UPDATE")
                .param(hash(token)).query((rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), ts(rs.getTimestamp(6)), ts(rs.getTimestamp(7)))).optional()
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        if (inv.revoked() != null || inv.accepted() != null || inv.expires().isBefore(Instant.now())) {
            throw ApiException.conflict("invitation_unusable", "This invitation is " + (inv.revoked() != null ? "revoked" : inv.accepted() != null ? "already used" : "expired"));
        }
        String email = jdbc.sql("SELECT email FROM users WHERE id = ?").param(userId).query(String.class).single();
        if (!email.equalsIgnoreCase(inv.email())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "other_email", "This invitation is for another e-mail address: sign in with that address");
        }
        if (access.isMember(inv.workspaceId(), userId)) throw ApiException.conflict("already_member", "You are already a member of this workspace");
        jdbc.sql("INSERT INTO memberships (workspace_id, user_id, role) VALUES (?, ?, ?)").params(inv.workspaceId(), userId, inv.role()).update();
        jdbc.sql("UPDATE invitations SET accepted_by = ?, accepted_at = now() WHERE id = ?").params(userId, inv.id()).update();
        Actor actor = Actor.human(userId);
        audit.record(inv.workspaceId(), actor, "member.join", "workspace", inv.workspaceId(), AuditLog.Decision.RECORDED, Map.of("role", inv.role()));
        events.publish(inv.workspaceId(), null, "member.joined", actor, new Subject("user", userId), Map.of("role", inv.role()));
        return Map.of("workspaceId", inv.workspaceId(), "role", inv.role());
    }

    // ---- roles

    record RoleBody(String role) {}

    @PutMapping("/api/v1/workspaces/{workspaceId}/members/{userId}")
    @Transactional
    Map<String, Object> changeRole(@PathVariable String workspaceId, @PathVariable String userId, @RequestBody RoleBody body) {
        String me = access.require(workspaceId, WorkspaceRole.ADMIN);
        WorkspaceRole target = access.roleOf(workspaceId, userId).orElseThrow(() -> ApiException.notFound("Member"));
        WorkspaceRole next = role(body == null ? null : body.role());
        boolean owner = myRole(workspaceId, me) == WorkspaceRole.OWNER;
        if ((target == WorkspaceRole.OWNER || next == WorkspaceRole.OWNER || next == WorkspaceRole.ADMIN && target != WorkspaceRole.ADMIN) && !owner) {
            throw ApiException.forbidden();
        }
        if (target == WorkspaceRole.OWNER && next != WorkspaceRole.OWNER) keepAnOwner(workspaceId);
        jdbc.sql("UPDATE memberships SET role = ? WHERE workspace_id = ? AND user_id = ?").params(next.name(), workspaceId, userId).update();
        Actor actor = Actor.human(me);
        audit.record(workspaceId, actor, "member.role", "user", userId, AuditLog.Decision.RECORDED, Map.of("from", target.name(), "to", next.name()));
        events.publish(workspaceId, null, "member.role_changed", actor, new Subject("user", userId), Map.of("from", target.name(), "to", next.name()));
        return Map.of("userId", userId, "role", next.name());
    }

    /** Removes a member (admins: not an owner); anyone may leave, except the last owner. */
    @DeleteMapping("/api/v1/workspaces/{workspaceId}/members/{userId}")
    @Transactional
    Map<String, Object> remove(@PathVariable String workspaceId, @PathVariable String userId) {
        String me = CurrentUser.id();
        WorkspaceRole mine = access.roleOf(workspaceId, me).orElseThrow(() -> ApiException.notFound("Workspace"));
        WorkspaceRole target = access.roleOf(workspaceId, userId).orElseThrow(() -> ApiException.notFound("Member"));
        boolean self = me.equals(userId);
        if (!self && (!mine.atLeast(WorkspaceRole.ADMIN) || target == WorkspaceRole.OWNER && mine != WorkspaceRole.OWNER)) throw ApiException.forbidden();
        if (target == WorkspaceRole.OWNER) keepAnOwner(workspaceId);
        jdbc.sql("DELETE FROM memberships WHERE workspace_id = ? AND user_id = ?").params(workspaceId, userId).update();
        Actor actor = Actor.human(me);
        audit.record(workspaceId, actor, self ? "member.leave" : "member.remove", "user", userId, AuditLog.Decision.RECORDED, Map.of("role", target.name()));
        events.publish(workspaceId, null, "member.removed", actor, new Subject("user", userId), Map.of("self", self));
        return Map.of("removed", true);
    }

    private void keepAnOwner(String workspaceId) {
        long owners = jdbc.sql("SELECT count(*) FROM memberships WHERE workspace_id = ? AND role = 'OWNER'").param(workspaceId).query(Long.class).single();
        if (owners <= 1) throw ApiException.conflict("last_owner", "A workspace keeps at least one owner: promote someone first");
    }

    private WorkspaceRole myRole(String workspaceId, String userId) {
        return access.roleOf(workspaceId, userId).orElseThrow(() -> ApiException.notFound("Workspace"));
    }

    private static WorkspaceRole role(String name) {
        try {
            return WorkspaceRole.valueOf(name == null ? "" : name.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_role", "Role: OWNER, ADMIN, MEMBER or VIEWER");
        }
    }

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

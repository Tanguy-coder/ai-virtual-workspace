package dev.aiw.delivery;

import dev.aiw.events.EventPublisher;
import dev.aiw.events.Subject;
import dev.aiw.governance.ApprovalDecided;
import dev.aiw.governance.ApprovalService;
import dev.aiw.governance.AuditLog;
import dev.aiw.governance.SecretVault;
import dev.aiw.scm.GitService;
import dev.aiw.shared.Actor;
import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Releases and the project's GitHub/GitLab remote (docs/18). A release is a tag on main: its version is computed and its
 * notes are written by code from the merged tasks; a human approves it. Pushing is an outward action: it needs a
 * human too (his click, or the approval of the release that includes it). The token stays in the vault.
 */
@Service
public class ReleaseService {
    static final String RELEASE = "release.create";
    private static final Pattern SEMVER = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");
    private static final Pattern TASK_MERGE = Pattern.compile("^Merge !(\\d+): (.+)$");

    private final JdbcClient jdbc;
    private final GitService git;
    private final ApprovalService approvals;
    private final AuditLog audit;
    private final EventPublisher events;
    private final SecretVault vault;
    private final TransactionTemplate tx;

    ReleaseService(JdbcClient jdbc, GitService git, ApprovalService approvals, AuditLog audit, EventPublisher events,
                   SecretVault vault, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.git = git;
        this.approvals = approvals;
        this.audit = audit;
        this.events = events;
        this.vault = vault;
        this.tx = tx;
    }

    // ================================================================== versions

    public record Proposal(String current, Map<String, String> next, String commit, String notes, int tasks) {}

    /** What a release of main would be now: the next versions and the notes. */
    public Proposal proposal(String projectId) {
        Path repo = repo(projectId);
        String current = latestVersion(repo);
        String commit = git.revParse(repo, GitService.MAIN);
        List<String> notes = notes(repo, current);
        Map<String, String> next = new LinkedHashMap<>();
        for (String bump : List.of("patch", "minor", "major")) next.put(bump, bump(current, bump));
        return new Proposal(current, next, commit, String.join("\n", notes), notes.size());
    }

    static String bump(String current, String bump) {
        Matcher m = SEMVER.matcher(current == null ? "0.0.0" : current);
        int major = 0, minor = 0, patch = 0;
        if (m.matches()) {
            major = Integer.parseInt(m.group(1));
            minor = Integer.parseInt(m.group(2));
            patch = Integer.parseInt(m.group(3));
        }
        return switch (bump) {
            case "major" -> (major + 1) + ".0.0";
            case "minor" -> major + "." + (minor + 1) + ".0";
            default -> major + "." + minor + "." + (patch + 1);
        };
    }

    private String latestVersion(Path repo) {
        return git.tags(repo).stream().filter(t -> SEMVER.matcher(t).matches()).findFirst().map(t -> t.replaceFirst("^v", "")).orElse(null);
    }

    /** One line per task merged into main since the last version, from the merge commits. */
    private List<String> notes(Path repo, String since) {
        List<String> out = new ArrayList<>();
        for (String s : git.subjectsSince(repo, since == null ? null : "v" + since, GitService.MAIN, 500)) {
            Matcher m = TASK_MERGE.matcher(s);
            if (m.matches()) out.add("- " + m.group(2).strip() + " (!" + m.group(1) + ")");
        }
        return out;
    }

    /** A release of main's current commit. A human's request is his approval; the tag is made at once. */
    public Release create(String workspaceId, String projectId, String bump, Actor actor) {
        if (!List.of("patch", "minor", "major").contains(bump)) throw ApiException.badRequest("bad_bump", "Choose patch, minor or major");
        Proposal p = proposal(projectId);
        if (p.current() != null && git.revParse(repo(projectId), "v" + p.current() + "^{commit}").equals(p.commit())) {
            throw ApiException.conflict("nothing_new", "main has nothing new since v" + p.current());
        }
        String version = p.next().get(bump);
        String id = Ids.newId();
        boolean human = actor.kind() == Actor.Kind.HUMAN;
        String notes = p.notes().isBlank() ? "- Maintenance release" : p.notes();
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    INSERT INTO releases (id, workspace_id, project_id, version, commit_sha, notes, status, requested_by_kind, requested_by_id)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?)
                    """).params(id, workspaceId, projectId, version, p.commit(), notes, actor.kind().name(),
                    actor.id() == null ? "platform" : actor.id()).update();
            if (!human) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("version", version);
                details.put("commit", p.commit());
                details.put("notes", notes);
                details.put("push", remote(projectId).isPresent());
                String approval = approvals.request(workspaceId, projectId, actor, RELEASE, "release", id,
                        "Release v" + version + " of main (" + p.commit().substring(0, 7) + ")", details);
                jdbc.sql("UPDATE releases SET approval_id = ? WHERE id = ?").params(approval, id).update();
            }
            events.publish(workspaceId, projectId, "release.requested", actor, new Subject("release", id), Map.of("version", version));
        });
        if (human) release(workspaceId, id, actor.id());
        return find(id).orElseThrow();
    }

    @EventListener
    void onApproval(ApprovalDecided e) {
        if (!RELEASE.equals(e.action())) return;
        Release r = find(e.targetId()).orElse(null);
        if (r == null || !"PENDING_APPROVAL".equals(r.status())) return;
        if (!e.approved()) {
            jdbc.sql("UPDATE releases SET status = 'REJECTED' WHERE id = ?").param(r.id()).update();
            return;
        }
        release(e.workspaceId(), r.id(), e.userId());
    }

    /** Tags main and, when the project has a remote, pushes main, develop and the tags. */
    private void release(String workspaceId, String releaseId, String userId) {
        Release r = find(releaseId).orElseThrow();
        Path repo = repo(r.projectId());
        try {
            git.tag(repo, "v" + r.version(), r.commitSha(), "Release v" + r.version() + "\n\n" + r.notes(), GitService.Author.PLATFORM);
        } catch (GitService.GitException e) {
            jdbc.sql("UPDATE releases SET status = 'FAILED', error = ? WHERE id = ?").params(e.getMessage(), r.id()).update();
            throw ApiException.conflict("tag_failed", e.getMessage());
        }
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE releases SET status = 'RELEASED', released_at = now() WHERE id = ?").param(r.id()).update();
            audit.record(workspaceId, Actor.human(userId), RELEASE, "release", r.id(), AuditLog.Decision.APPROVED,
                    Map.of("version", r.version(), "commit", r.commitSha()));
            events.publish(workspaceId, r.projectId(), "release.created", Actor.human(userId), new Subject("release", r.id()),
                    Map.of("version", r.version(), "commit", r.commitSha()));
        });
        if (remote(r.projectId()).isPresent()) push(workspaceId, r.projectId(), userId, "release v" + r.version());
    }

    public Optional<Release> find(String id) {
        return jdbc.sql("SELECT * FROM releases WHERE id = ?").param(id).query(this::release).optional();
    }

    public List<Release> releases(String projectId) {
        return jdbc.sql("SELECT * FROM releases WHERE project_id = ? ORDER BY created_at DESC LIMIT 100").param(projectId).query(this::release).list();
    }

    // ================================================================== remote

    public record Remote(String provider, String url, boolean hasToken, java.time.Instant lastPushAt, Boolean lastPushOk, String lastPushInfo) {}

    public Optional<Remote> remote(String projectId) {
        return jdbc.sql("SELECT * FROM project_remotes WHERE project_id = ?").param(projectId).query((rs, i) -> {
            var at = rs.getTimestamp("last_push_at");
            return new Remote(rs.getString("provider"), rs.getString("url"), rs.getString("secret_name") != null,
                    at == null ? null : at.toInstant(), (Boolean) rs.getObject("last_push_ok"), rs.getString("last_push_info"));
        }).optional();
    }

    /** Sets the remote; a new token replaces the old one in the vault (it is never shown again). */
    public Remote setRemote(String workspaceId, String projectId, String provider, String url, String token, String userId) {
        if (!List.of("GITHUB", "GITLAB", "OTHER").contains(provider)) throw ApiException.badRequest("bad_provider", "GITHUB, GITLAB or OTHER");
        if (url == null || !url.strip().matches("https://[\\w.-]+(:\\d+)?/[\\w./~%-]+")) {
            throw ApiException.badRequest("bad_url", "Give the HTTPS URL of the repository (https://gitlab.com/owner/repo.git)");
        }
        tx.executeWithoutResult(s -> {
            String old = jdbc.sql("SELECT secret_name FROM project_remotes WHERE project_id = ?").param(projectId).query(String.class).optional().orElse(null);
            String secret = old;
            if (token != null && !token.isBlank()) {
                if (old != null) vault.delete(workspaceId, old);
                secret = vault.store(workspaceId, projectId, "remote-token", token.strip());
            }
            jdbc.sql("""
                    INSERT INTO project_remotes (project_id, workspace_id, provider, url, secret_name) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (project_id) DO UPDATE SET provider = EXCLUDED.provider, url = EXCLUDED.url,
                           secret_name = EXCLUDED.secret_name, updated_at = now()
                    """).params(projectId, workspaceId, provider, url.strip(), secret).update();
            audit.record(workspaceId, Actor.human(userId), "remote.set", "project", projectId, AuditLog.Decision.RECORDED,
                    Map.of("provider", provider, "url", url.strip(), "token", token != null && !token.isBlank() ? "replaced" : "kept"));
        });
        return remote(projectId).orElseThrow();
    }

    /** Pushes main, develop and the tags. {@code why}: the human's click or the release approval it comes from. */
    public Remote push(String workspaceId, String projectId, String userId, String why) {
        Remote remote = remote(projectId).orElseThrow(() -> ApiException.conflict("no_remote", "Set the project's GitHub or GitLab remote first"));
        String secret = jdbc.sql("SELECT secret_name FROM project_remotes WHERE project_id = ?").param(projectId).query(String.class).optional().orElse(null);
        String token = secret == null ? null : vault.reveal(workspaceId, secret);
        String user = switch (remote.provider()) {
            case "GITHUB" -> "x-access-token";
            case "GITLAB" -> "oauth2";
            default -> "git";
        };
        GitService.PushOutcome o = git.push(repo(projectId), remote.url(), token, user);
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE project_remotes SET last_push_at = now(), last_push_ok = ?, last_push_info = ? WHERE project_id = ?")
                    .params(o.ok(), o.detail(), projectId).update();
            audit.record(workspaceId, Actor.human(userId), "remote.push", "project", projectId,
                    o.ok() ? AuditLog.Decision.APPROVED : AuditLog.Decision.RECORDED, Map.of("url", remote.url(), "why", why, "ok", o.ok()));
            events.publish(workspaceId, projectId, o.ok() ? "remote.pushed" : "remote.push_failed", Actor.human(userId),
                    new Subject("project", projectId), Map.of("url", remote.url(), "detail", o.detail().length() > 300 ? o.detail().substring(0, 300) : o.detail()));
        });
        return remote(projectId).orElseThrow();
    }

    private Path repo(String projectId) {
        return git.repo(projectId).orElseThrow(() -> ApiException.conflict("no_repository", "The project has no repository yet"));
    }

    private Release release(ResultSet rs, int i) throws SQLException {
        var at = rs.getTimestamp("released_at");
        return new Release(rs.getString("id"), rs.getString("project_id"), rs.getString("version"), rs.getString("commit_sha"),
                rs.getString("notes"), rs.getString("status"), rs.getString("approval_id"), rs.getString("requested_by_kind"),
                rs.getString("requested_by_id"), rs.getString("error"), rs.getTimestamp("created_at").toInstant(), at == null ? null : at.toInstant());
    }
}

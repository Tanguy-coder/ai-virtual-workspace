package dev.aiw.scm;

import dev.aiw.shared.Processes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Starts a project from existing code (CTO requirement, 2026-11-18, docs/17): a Git repository cloned over HTTPS (with
 * an access token when private) or a folder placed in the import directory. The code lands in the project's repository
 * as {@code main}; agents branch from {@code develop} as usual. The token is passed to git for the clone only, in a
 * header: it is never written to the repository's configuration nor to a log. Nothing is pushed back to the remote
 * (a later increment, with the CTO's approval).
 */
@Service
public class RepoImport {
    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(10);
    private static final Map<String, String> ENV = Map.of("GIT_TERMINAL_PROMPT", "0", "LC_ALL", "C");
    /** Never copied from a folder, never committed by agents (kept out through .git/info/exclude). */
    static final List<String> IGNORED = List.of("node_modules/", ".venv/", "venv/", "__pycache__/", "target/", "dist/",
            "build/", ".next/", ".env");

    public enum Kind { GIT, FOLDER }

    /** {@code url}/{@code branch}/{@code token} for GIT, {@code folder} (a name inside the import directory) for FOLDER. */
    public record Source(Kind kind, String url, String branch, String token, String folder) {}

    /** What was imported: the branch it came from, its head, and a map of the code for the Lead. */
    public record Imported(Path repo, String sourceBranch, String head, int files, String map) {}

    private final JdbcClient jdbc;
    private final Path dataDir;
    private final Path importDir;

    RepoImport(JdbcClient jdbc, @Value("${aiw.data-dir}") String dataDir, @Value("${aiw.import-dir:}") String importDir) {
        this.jdbc = jdbc;
        this.dataDir = Path.of(dataDir).toAbsolutePath();
        this.importDir = (importDir == null || importDir.isBlank() ? this.dataDir.resolve("imports") : Path.of(importDir))
                .toAbsolutePath().normalize();
    }

    public Path importDir() {
        return importDir;
    }

    /** Folders the CTO can import: the subfolders of the import directory. */
    public List<String> folders() {
        if (!Files.isDirectory(importDir)) return List.of();
        try (Stream<Path> s = Files.list(importDir)) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(".")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Replaces the project's repository by the imported code. Refused once agents worked in it: only a repository the
     * platform just created (its initial commit, main and develop) can be replaced.
     */
    public Imported importInto(String workspaceId, String projectId, Source source) {
        Path repo = dataDir.resolve("repos").resolve(projectId);
        boolean imported = jdbc.sql("SELECT count(*) FROM repositories WHERE project_id = ? AND source_kind <> 'NEW'")
                .param(projectId).query(Long.class).single() > 0;
        if (imported || (Files.isDirectory(repo.resolve(".git")) && !pristine(repo))) {
            throw new IllegalStateException("The project's repository already has work in it; import into a new project.");
        }
        Path staging = dataDir.resolve("repos").resolve(projectId + ".import");
        delete(staging);
        String sourceBranch;
        try {
            Files.createDirectories(staging.getParent());
            sourceBranch = switch (source.kind()) {
                case GIT -> cloneGit(source, staging);
                case FOLDER -> fromFolder(source.folder(), staging);
            };
            // The platform's model: main is the imported branch; develop starts from it on the first run.
            run(staging, CLONE_TIMEOUT, "checkout", "-q", "-B", GitService.MAIN);
            Files.writeString(staging.resolve(".git/info/exclude"), String.join("\n", IGNORED) + "\n", StandardCharsets.UTF_8);
            delete(repo);
            Files.move(staging, repo);
        } catch (IOException e) {
            delete(staging);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            delete(staging);
            throw e;
        }
        jdbc.sql("""
                INSERT INTO repositories (project_id, workspace_id, path, default_branch, source_kind, remote_url, remote_branch, imported_at)
                VALUES (?, ?, ?, 'main', ?, ?, ?, now())
                ON CONFLICT (project_id) DO UPDATE SET path = EXCLUDED.path, source_kind = EXCLUDED.source_kind,
                    remote_url = EXCLUDED.remote_url, remote_branch = EXCLUDED.remote_branch, imported_at = now()
                """).params(projectId, workspaceId, repo.toString(), source.kind().name(),
                source.kind() == Kind.GIT ? source.url() : null, source.kind() == Kind.GIT ? sourceBranch : null).update();
        List<String> files = List.of(run(repo, Duration.ofSeconds(60), "ls-files").out().split("\n"));
        String head = run(repo, Duration.ofSeconds(10), "rev-parse", "HEAD").out().strip();
        return new Imported(repo, sourceBranch, head, files.size(), RepoMap.build(repo, files));
    }

    public void rememberToken(String projectId, String secretId) {
        jdbc.sql("UPDATE repositories SET remote_secret_id = ? WHERE project_id = ?").params(secretId, projectId).update();
    }

    private String cloneGit(Source source, Path staging) {
        String url = source.url() == null ? "" : source.url().strip();
        // HTTPS only: a file:// or ssh URL would read the server's own files or keys.
        if (!url.matches("https://[\\w.-]+(:\\d+)?/[\\w./~%-]+")) {
            throw new IllegalArgumentException("Give an HTTPS URL of a Git repository (https://host/owner/repo.git).");
        }
        List<String> args = new ArrayList<>();
        if (source.token() != null && !source.token().isBlank()) {
            // GitHub and GitLab accept a token as the password of any user name.
            String basic = Base64.getEncoder().encodeToString(("x-access-token:" + source.token().strip()).getBytes(StandardCharsets.UTF_8));
            args.addAll(List.of("-c", "http.extraHeader=Authorization: Basic " + basic));
        }
        args.addAll(List.of("clone", "-q", "--no-recurse-submodules"));
        if (source.branch() != null && !source.branch().isBlank()) args.addAll(List.of("--branch", source.branch().strip()));
        args.addAll(List.of(url, staging.toString()));
        Processes.Result r = git(staging.getParent(), CLONE_TIMEOUT, args.toArray(String[]::new));
        if (!r.ok()) {
            String why = r.combined().replaceAll("(?i)authorization: basic \\S+", "authorization: ***").strip();
            if (why.contains("Authentication failed") || why.contains("could not read Username") || why.contains("403")) {
                throw new IllegalArgumentException("The repository refused access: check the URL and the access token (read access to the code).");
            }
            throw new IllegalArgumentException("The clone failed: " + (why.length() > 400 ? why.substring(0, 400) + "…" : why));
        }
        return run(staging, Duration.ofSeconds(10), "rev-parse", "--abbrev-ref", "HEAD").out().strip();
    }

    private String fromFolder(String name, Path staging) throws IOException {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            throw new IllegalArgumentException("Give the name of a folder inside " + importDir + ".");
        }
        Path folder = importDir.resolve(name).normalize();
        if (!folder.startsWith(importDir) || !Files.isDirectory(folder)) {
            throw new IllegalArgumentException("No folder named " + name + " in " + importDir + ".");
        }
        if (Files.isDirectory(folder.resolve(".git"))) {
            // A repository: its history comes along (a local clone, the folder itself is never written to).
            Processes.Result r = git(staging.getParent(), CLONE_TIMEOUT, "clone", "-q", "--no-recurse-submodules", folder.toString(), staging.toString());
            if (!r.ok()) throw new IllegalArgumentException("The folder's repository could not be cloned: " + r.combined().strip());
            run(staging, Duration.ofSeconds(10), "remote", "remove", "origin");
            return run(staging, Duration.ofSeconds(10), "rev-parse", "--abbrev-ref", "HEAD").out().strip();
        }
        Files.createDirectories(staging);
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = folder.relativize(p);
                if (rel.toString().isEmpty() || ignored(rel)) continue;
                Path target = staging.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else if (Files.isRegularFile(p) && !Files.isSymbolicLink(p)) Files.copy(p, target);
            }
        }
        run(staging, Duration.ofSeconds(30), "init", "-q", "-b", GitService.MAIN);
        run(staging, Duration.ofSeconds(120), "add", "-A");
        Processes.Result c = git(staging, Duration.ofSeconds(120), "-c", "user.name=" + GitService.Author.PLATFORM.name(),
                "-c", "user.email=" + GitService.Author.PLATFORM.email(), "commit", "-q", "--no-verify", "--allow-empty",
                "-m", "Import of " + name);
        if (!c.ok()) throw new IllegalStateException("git commit failed: " + c.combined());
        return GitService.MAIN;
    }

    /** A path to leave out of a folder import: .git, dependencies and build output at any depth, .env files. */
    static boolean ignored(Path rel) {
        for (Path part : rel) {
            String name = part.toString();
            if (name.equals(".git") || IGNORED.stream().anyMatch(i -> i.endsWith("/") && name.equals(i.substring(0, i.length() - 1)))) return true;
        }
        return rel.getFileName().toString().equals(".env");
    }

    /** Only what the platform created: one commit, no branch but main and develop. */
    private boolean pristine(Path repo) {
        Processes.Result count = git(repo, Duration.ofSeconds(10), "rev-list", "--count", "--all");
        Processes.Result branches = git(repo, Duration.ofSeconds(10), "for-each-ref", "--format=%(refname:short)", "refs/heads");
        if (!count.ok() || !branches.ok()) return false;
        Set<String> allowed = Set.of(GitService.MAIN, GitService.DEVELOP);
        return Integer.parseInt(count.out().strip()) <= 1 && branches.out().lines().allMatch(allowed::contains);
    }

    private Processes.Result run(Path dir, Duration timeout, String... args) {
        Processes.Result r = git(dir, timeout, args);
        if (!r.ok()) throw new IllegalStateException("git " + args[0] + " failed: " + r.combined().strip());
        return r;
    }

    private static Processes.Result git(Path dir, Duration timeout, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return Processes.run(cmd, dir, timeout, ENV, null);
    }

    private static void delete(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A map of the code, built by the platform without a model call: what the Lead and the agents read first instead
     * of exploring the repository (token sobriety). Layout, languages, how to install and test, the README's start.
     */
    static final class RepoMap {
        private RepoMap() {}

        static String build(Path repo, List<String> files) {
            StringBuilder sb = new StringBuilder("# Existing code\n\nThis project starts from existing code, imported into the repository (")
                    .append(files.size()).append(" files). Plan changes to it, not a rewrite: each task names the files it changes.\n\n");
            Map<String, Integer> languages = new TreeMap<>();
            Map<String, Integer> folders = new TreeMap<>();
            for (String f : files) {
                if (f.isBlank()) continue;
                int dot = f.lastIndexOf('.');
                if (dot > f.lastIndexOf('/')) languages.merge(f.substring(dot + 1).toLowerCase(), 1, Integer::sum);
                String[] parts = f.split("/");
                folders.merge(parts.length > 2 ? parts[0] + "/" + parts[1] + "/" : parts.length == 2 ? parts[0] + "/" : "(root)", 1, Integer::sum);
            }
            sb.append("## Languages (files per extension)\n");
            languages.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10)
                    .forEach(e -> sb.append("- .").append(e.getKey()).append(": ").append(e.getValue()).append("\n"));
            sb.append("\n## Layout (files per folder)\n");
            folders.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(30)
                    .forEach(e -> sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n"));
            sb.append("\n## Build and tests\n").append(stack(repo)).append("\n");
            for (String readme : List.of("README.md", "README", "readme.md", "README.txt")) {
                Path p = repo.resolve(readme);
                if (Files.isRegularFile(p)) {
                    try {
                        String text = Files.readString(p, StandardCharsets.UTF_8).strip();
                        sb.append("## README (start)\n").append(text.length() > 2_500 ? text.substring(0, 2_500) + "\n…" : text).append("\n");
                    } catch (IOException | UncheckedIOException e) {
                        // not text: skip
                    }
                    break;
                }
            }
            return sb.toString();
        }

        private static String stack(Path repo) {
            List<String> out = new ArrayList<>();
            Path pkg = repo.resolve("package.json");
            if (Files.isRegularFile(pkg)) {
                try {
                    var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(Files.readString(pkg));
                    out.add("- Node.js (package.json" + (json.path("name").isString() ? ", " + json.path("name").asString() : "") + ")");
                    var scripts = json.path("scripts");
                    if (scripts.isObject()) {
                        scripts.propertyNames().forEach(k -> out.add("  - npm run " + k + ": " + scripts.path(k).asString("")));
                    }
                    if (!scripts.has("test")) out.add("  - No test script: add one (node --test) before relying on tests.");
                    List<String> deps = new ArrayList<>();
                    json.path("dependencies").propertyNames().forEach(deps::add);
                    json.path("devDependencies").propertyNames().forEach(deps::add);
                    if (!deps.isEmpty()) out.add("  - Dependencies: " + String.join(", ", deps.stream().limit(25).toList())
                            + " (install with npm install)");
                } catch (Exception e) {
                    out.add("- Node.js (package.json unreadable)");
                }
            }
            if (Files.isRegularFile(repo.resolve("pom.xml"))) out.add("- Java with Maven: mvn -q test");
            if (Files.isRegularFile(repo.resolve("build.gradle")) || Files.isRegularFile(repo.resolve("build.gradle.kts"))) out.add("- Java/Kotlin with Gradle");
            if (Files.isRegularFile(repo.resolve("pyproject.toml")) || Files.isRegularFile(repo.resolve("requirements.txt"))) {
                out.add("- Python: pip install -r requirements.txt (or the pyproject), tests with pytest");
            }
            if (Files.isRegularFile(repo.resolve("composer.json"))) out.add("- PHP with Composer");
            if (out.isEmpty()) out.add("- No build file recognized: the first task sets up a test command.");
            return String.join("\n", out);
        }
    }
}

package dev.aiw.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a JavaScript test file as soon as an agent writes it (CTO requirement, 2026-11-19, docs/17). On "To do list"
 * three agents wrote tests in three frameworks (Jest's expect imported from node:test, Mocha's setup and suite globals,
 * jsdom never installed): every later task failed on them, whatever it changed. The project's tests use node:test and
 * node:assert, plus only the packages its package.json declares.
 */
final class TestFileLint {
    private TestFileLint() {}

    private static final Pattern TEST_FILE = Pattern.compile("(^|/)(tests?|__tests__)/.*\\.(js|mjs|cjs)$|\\.(test|spec)\\.(js|mjs|cjs)$");
    /** What node:test exports (Node 22). */
    private static final Set<String> NODE_TEST = Set.of("test", "describe", "it", "suite", "before", "after", "beforeEach",
            "afterEach", "mock", "run", "only", "skip", "todo");
    /** Globals of other runners: they do not exist under node --test unless imported from node:test. */
    private static final List<String> GLOBALS = List.of("describe", "it", "suite", "test", "before", "after", "beforeEach",
            "afterEach", "setup", "teardown", "suiteSetup", "suiteTeardown", "expect", "jest");
    private static final Set<String> BUILTINS = Set.of("assert", "assert/strict", "fs", "fs/promises", "path", "url", "util",
            "os", "events", "child_process", "crypto", "http", "https", "stream", "buffer", "timers", "readline", "zlib", "vm",
            "worker_threads", "process", "querystring", "string_decoder", "net", "dns", "module", "perf_hooks");

    private static final Pattern IMPORT_FROM = Pattern.compile("import\\s+(.+?)\\s+from\\s+['\"]([^'\"]+)['\"]", Pattern.DOTALL);
    private static final Pattern BARE_IMPORT = Pattern.compile("import\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern REQUIRE = Pattern.compile("(?:(const|let|var)\\s+(\\{[^}]*\\}|\\w+)\\s*=\\s*)?require\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern DECLARED = Pattern.compile("(?:function\\s+|(?:const|let|var)\\s+)(\\w+)");

    static boolean isTestFile(String path) {
        return path != null && TEST_FILE.matcher(path).find();
    }

    /** Problems of the file, in words the agent can act on; empty when it is fine. */
    static List<String> problems(String source, Set<String> dependencies) {
        List<String> out = new ArrayList<>();
        Set<String> bound = new LinkedHashSet<>();
        String code = stripComments(source);

        Matcher m = IMPORT_FROM.matcher(code);
        while (m.find()) {
            String names = m.group(1), module = m.group(2);
            bound.addAll(names(names));
            if (module.equals("node:test") || module.equals("test")) {
                if (module.equals("test")) out.add("Import from 'node:test', not 'test'.");
                for (String n : named(names)) {
                    if (!NODE_TEST.contains(n)) {
                        out.add("'node:test' has no export '" + n + "'" + (n.equals("expect")
                                ? ": assert with node:assert (assert.strictEqual, assert.deepStrictEqual, assert.ok)." : "."));
                    }
                }
            }
            checkPackage(module, dependencies, out);
        }
        Matcher bare = BARE_IMPORT.matcher(code);
        while (bare.find()) checkPackage(bare.group(1), dependencies, out);
        Matcher r = REQUIRE.matcher(code);
        while (r.find()) {
            if (r.group(2) != null) bound.addAll(names(r.group(2)));
            checkPackage(r.group(3), dependencies, out);
        }
        Matcher d = DECLARED.matcher(code);
        while (d.find()) bound.add(d.group(1));

        for (String g : GLOBALS) {
            if (bound.contains(g)) continue;
            if (Pattern.compile("(?<![\\w.$])" + g + "\\s*\\(").matcher(code).find()) {
                out.add(NODE_TEST.contains(g)
                        ? "'" + g + "' is used but not imported: import { " + g + " } from 'node:test'."
                        : "'" + g + "' is a global of another test runner and does not exist here: use test() from "
                          + "'node:test' and node:assert" + (g.equals("setup") || g.equals("teardown") ? " (beforeEach/afterEach for setup)." : "."));
            }
        }
        if (!bound.contains("JSDOM") && code.matches("(?s).*\\bJSDOM\\b.*")) {
            out.add("'JSDOM' is used but not imported: import { JSDOM } from 'jsdom'.");
        }
        // Seen on T-16: jsdom without a url has an opaque origin, where localStorage throws "SecurityError".
        if (code.matches("(?s).*(JSDOM\\.fromFile|new\\s+JSDOM)\\s*\\(.*") && code.contains("localStorage")
                && !code.matches("(?s).*\\burl\\s*:.*")) {
            out.add("jsdom without a \"url\" option has no localStorage (opaque origin): pass { url: 'http://localhost/' }.");
        }
        return out.stream().distinct().toList();
    }

    private static final Pattern EXPORT_DECL = Pattern.compile("export\\s+(?:default\\s+)?(?:async\\s+)?(?:function\\*?|const|let|var|class)\\s+(\\w+)");
    private static final Pattern EXPORT_LIST = Pattern.compile("export\\s*\\{([^}]*)\\}");

    /**
     * Names a test imports from the project's own files that those files do not export (seen on T-21: a test imported
     * addTask from src/app.js, which had module.exports in an ES module project, then nothing at all). {@code read}
     * gives a file's content by its path from the repository root, or null when it does not exist.
     */
    /** Names a JavaScript file exports (declarations and export lists; "default" when it has a default export). */
    static Set<String> exportsOf(String content) {
        String body = stripComments(content);
        Set<String> exports = new LinkedHashSet<>();
        Matcher d = EXPORT_DECL.matcher(body);
        while (d.find()) exports.add(d.group(1));
        Matcher l = EXPORT_LIST.matcher(body);
        while (l.find()) {
            for (String part : l.group(1).split(",")) {
                String[] as = part.strip().split("\\s+as\\s+");
                if (!as[as.length - 1].isBlank()) exports.add(as[as.length - 1].strip());
            }
        }
        return exports;
    }

    /** Project files a test imports or requires (relative paths resolved from the repository root, ".js" added when missing). */
    static List<String> relativeImports(String testPath, String source) {
        String code = stripComments(source);
        List<String> modules = new ArrayList<>();
        Matcher m = IMPORT_FROM.matcher(code);
        while (m.find()) modules.add(m.group(2));
        Matcher b = BARE_IMPORT.matcher(code);
        while (b.find()) modules.add(b.group(1));
        Matcher r = REQUIRE.matcher(code);
        while (r.find()) modules.add(r.group(3));
        String dir = testPath.contains("/") ? testPath.substring(0, testPath.lastIndexOf('/')) : "";
        Set<String> out = new LinkedHashSet<>();
        for (String module : modules) {
            if (!module.startsWith(".")) continue;
            String target = java.nio.file.Path.of(dir.isEmpty() ? "." : dir).resolve(module).normalize().toString().replace('\\', '/');
            out.add(target.matches(".*\\.[cm]?js$") || target.matches(".*\\.\\w+$") ? target : target + ".js");
        }
        return new ArrayList<>(out);
    }

    static List<String> importProblems(String testPath, String source, java.util.function.Function<String, String> read, boolean esm) {
        List<String> out = new ArrayList<>();
        String code = stripComments(source);
        Matcher m = IMPORT_FROM.matcher(code);
        while (m.find()) {
            String clause = m.group(1), module = m.group(2);
            if (!module.startsWith(".")) continue;
            String dir = testPath.contains("/") ? testPath.substring(0, testPath.lastIndexOf('/')) : "";
            String target = java.nio.file.Path.of(dir.isEmpty() ? "." : dir).resolve(module).normalize().toString().replace('\\', '/');
            String content = read.apply(target);
            if (content == null && !target.matches(".*\\.[cm]?js$")) content = read.apply(target = target + ".js");
            if (content == null) {
                out.add("'" + module + "' does not exist (" + target + ").");
                continue;
            }
            String body = stripComments(content);
            Set<String> exports = new LinkedHashSet<>();
            Matcher d = EXPORT_DECL.matcher(body);
            while (d.find()) exports.add(d.group(1));
            Matcher l = EXPORT_LIST.matcher(body);
            while (l.find()) {
                for (String part : l.group(1).split(",")) {
                    String[] as = part.strip().split("\\s+as\\s+");
                    if (!as[as.length - 1].isBlank()) exports.add(as[as.length - 1].strip());
                }
            }
            boolean hasDefault = body.matches("(?s).*\\bexport\\s+default\\b.*");
            List<String> wanted = named(clause);
            if (esm && exports.isEmpty() && !hasDefault && body.matches("(?s).*\\b(module\\.exports|exports\\.\\w+)\\s*=.*")) {
                out.add(target + " uses module.exports, but package.json has \"type\": \"module\": nothing can be imported from it. "
                        + "Replace module.exports with: export { " + String.join(", ", wanted.isEmpty() ? List.of("...") : wanted) + " };");
                continue;
            }
            List<String> missing = wanted.stream().filter(n -> !exports.contains(n)).toList();
            if (!missing.isEmpty()) {
                out.add(target + " does not export " + String.join(", ", missing.stream().map(n -> "'" + n + "'").toList())
                        + (exports.isEmpty() ? ": it exports nothing. Add to it: export { " + String.join(", ", missing) + " }; "
                                + "(only functions it really defines), or test it through the page with jsdom."
                                : ": it exports " + String.join(", ", exports) + ". Import those, or add the export to " + target + "."));
            }
            String head = clause.contains("{") ? clause.substring(0, clause.indexOf('{')) : clause;
            if (head.strip().replace(",", "").matches("\\w+") && !hasDefault && !clause.strip().startsWith("*")) {
                out.add(target + " has no default export: import { name } from '" + module + "' instead.");
            }
        }
        return out;
    }

    private static void checkPackage(String module, Set<String> dependencies, List<String> out) {
        if (module.startsWith(".") || module.startsWith("/") || module.startsWith("node:") || module.startsWith("file:")) return;
        String pkg = module.startsWith("@") ? String.join("/", List.of(module.split("/")).subList(0, Math.min(2, module.split("/").length)))
                : module.split("/")[0];
        if (BUILTINS.contains(module) || BUILTINS.contains(pkg) || pkg.equals("test")) return;
        if (!dependencies.contains(pkg)) {
            out.add("'" + pkg + "' is not a dependency of this project: use node: modules only, or add it to package.json "
                    + "and run npm install before using it.");
        }
    }

    /** Every local name an import or destructuring binds: default, namespace and named (with "as" renames). */
    private static Set<String> names(String clause) {
        Set<String> out = new LinkedHashSet<>();
        String c = clause.strip();
        Matcher ns = Pattern.compile("\\*\\s+as\\s+(\\w+)").matcher(c);
        if (ns.find()) out.add(ns.group(1));
        int brace = c.indexOf('{');
        String head = brace >= 0 ? c.substring(0, brace) : c;
        for (String h : head.split(",")) if (h.strip().matches("\\w+")) out.add(h.strip());
        if (brace >= 0) {
            for (String part : c.substring(brace + 1, c.lastIndexOf('}') < 0 ? c.length() : c.lastIndexOf('}')).split(",")) {
                String p = part.strip();
                if (p.isEmpty()) continue;
                String[] as = p.split("\\s+as\\s+|\\s*:\\s*");
                out.add(as[as.length - 1].strip());
            }
        }
        return out;
    }

    /** The exported names asked for inside braces (before any "as"). */
    private static List<String> named(String clause) {
        int open = clause.indexOf('{'), close = clause.lastIndexOf('}');
        if (open < 0 || close < open) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : clause.substring(open + 1, close).split(",")) {
            String p = part.strip().split("\\s+as\\s+")[0].strip();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)(^|[^:\\\\])//.*$", "$1");
    }
}

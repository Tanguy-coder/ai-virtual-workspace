package dev.aiw.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The three test styles that broke every task of "To do list" (2026-11-19), and the style that runs. */
class TestFileLintTest {

    @Test
    void onlyTestFilesAreChecked() {
        assertThat(TestFileLint.isTestFile("tests/app.test.js")).isTrue();
        assertThat(TestFileLint.isTestFile("src/app.spec.mjs")).isTrue();
        assertThat(TestFileLint.isTestFile("test/helpers.js")).isTrue();
        assertThat(TestFileLint.isTestFile("src/app.js")).isFalse();
    }

    @Test
    void theStyleThatRunsPasses() {
        assertThat(TestFileLint.problems("""
                import { test, describe, beforeEach } from 'node:test';
                import assert from 'node:assert/strict';
                import fs from 'node:fs';
                import { add } from '../src/app.js';
                describe('add', () => { beforeEach(() => {}); test('adds', () => assert.strictEqual(add(1, 2), 3)); });
                """, Set.of())).isEmpty();
        assertThat(TestFileLint.problems("""
                const test = require('node:test');
                const assert = require('assert');
                // it('is a comment', ...) is not code
                test('works', () => assert.ok(true));
                """, Set.of())).isEmpty();
        assertThat(TestFileLint.problems("import { JSDOM } from 'jsdom';\nimport test from 'node:test';\ntest('x', () => {});",
                Set.of("jsdom"))).isEmpty();
    }

    @Test
    void jestExpectFromNodeTest() {
        assertThat(TestFileLint.problems("import { test, expect } from 'node:test';\ntest('x', () => expect(1).toBe(1));", Set.of()))
                .anyMatch(p -> p.contains("'node:test' has no export 'expect'") && p.contains("node:assert"));
    }

    @Test
    void mochaGlobals() {
        assertThat(TestFileLint.problems("setup(() => {});\nsuite('x', () => { test('y', () => {}); });", Set.of()))
                .anyMatch(p -> p.startsWith("'setup' is a global of another test runner"))
                .anyMatch(p -> p.equals("'suite' is used but not imported: import { suite } from 'node:test'."))
                .anyMatch(p -> p.equals("'test' is used but not imported: import { test } from 'node:test'."));
    }

    @Test
    void packagesThatAreNotInstalled() {
        assertThat(TestFileLint.problems("import { JSDOM } from 'jsdom';\nimport test from 'node:test';\ntest('x', () => {});", Set.of()))
                .containsExactly("'jsdom' is not a dependency of this project: use node: modules only, or add it to package.json "
                        + "and run npm install before using it.");
        assertThat(TestFileLint.problems("const x = require('@testing-library/dom');", Set.of("@testing-library/dom"))).isEmpty();
    }

    @Test
    void jsdomWithoutUrlHasNoLocalStorage() {
        String deps = "jsdom";
        String opaque = "import { JSDOM } from 'jsdom';\nconst dom = await JSDOM.fromFile('src/index.html');\nlocalStorage.getItem('t');";
        assertThat(TestFileLint.problems(opaque, Set.of(deps))).anyMatch(p -> p.startsWith("jsdom without a \"url\" option"));
        String withUrl = "import { JSDOM } from 'jsdom';\nconst dom = new JSDOM(html, { url: 'http://localhost/' });\nlocalStorage.clear();";
        assertThat(TestFileLint.problems(withUrl, Set.of(deps))).isEmpty();
    }

    /** T-21: the test imported names that src/app.js did not export (module.exports in an ES module project). */
    @Test
    void namesImportedFromTheProjectMustBeExported() {
        String test = "import { addTask, deleteTask } from '../src/app.js';\nimport { test } from 'node:test';";
        java.util.Map<String, String> files = new java.util.HashMap<>();
        files.put("src/app.js", "function addTask(t) {}\nmodule.exports = { addTask };\n");
        assertThat(TestFileLint.importProblems("tests/deleteTask.test.js", test, files::get, true))
                .containsExactly("src/app.js uses module.exports, but package.json has \"type\": \"module\": nothing can be "
                        + "imported from it. Replace module.exports with: export { addTask, deleteTask };");
        files.put("src/app.js", "export function addTask(t) {}\nconst x = 1;\nexport { x as tasks };\n");
        assertThat(TestFileLint.importProblems("tests/deleteTask.test.js", test, files::get, true))
                .containsExactly("src/app.js does not export 'deleteTask': it exports addTask, tasks. Import those, or add the "
                        + "export to src/app.js.");
        files.put("src/app.js", "document.addEventListener('DOMContentLoaded', () => {});\n");
        assertThat(TestFileLint.importProblems("tests/deleteTask.test.js", test, files::get, true))
                .anyMatch(p -> p.contains("it exports nothing"));
        assertThat(TestFileLint.importProblems("tests/x.test.js", "import { a } from '../src/nope.js';", files::get, true))
                .containsExactly("'../src/nope.js' does not exist (src/nope.js).");
        files.put("src/ok.js", "export const a = 1;\nexport default function main() {}\n");
        assertThat(TestFileLint.importProblems("tests/x.test.js", "import main, { a } from '../src/ok';", files::get, true)).isEmpty();
    }

    @Test
    void jsdomUsedWithoutImport() {
        assertThat(TestFileLint.problems("import { test } from 'node:test';\nconst dom = new JSDOM('<p></p>');", Set.of("jsdom")))
                .contains("'JSDOM' is used but not imported: import { JSDOM } from 'jsdom'.");
    }
}

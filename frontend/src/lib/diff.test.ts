import { describe, expect, it } from "vitest";
import { parseDiff } from "./diff";

const DIFF = `diff --git a/src/sum.js b/src/sum.js
index 1111111..2222222 100644
--- a/src/sum.js
+++ b/src/sum.js
@@ -1,3 +1,3 @@
 // sum
-exports.sum = (a, b) => a - b;
+exports.sum = (a, b) => a + b;
 module.exports.x = 1;
diff --git a/tests/sum.test.js b/tests/sum.test.js
new file mode 100644
--- /dev/null
+++ b/tests/sum.test.js
@@ -0,0 +1,2 @@
+const test = require('node:test');
+test('adds', () => {});`;

describe("parseDiff", () => {
  it("numbers old and new lines per file", () => {
    const [a, b] = parseDiff(DIFF);
    expect(a.path).toBe("src/sum.js");
    expect([a.added, a.removed]).toEqual([1, 1]);
    expect(a.lines.map((l) => l.kind)).toEqual(["hunk", "ctx", "del", "add", "ctx"]);
    expect(a.lines[2]).toMatchObject({ oldNo: 2, newNo: null, text: "exports.sum = (a, b) => a - b;" });
    expect(a.lines[3]).toMatchObject({ oldNo: null, newNo: 2 });
    expect(b.path).toBe("tests/sum.test.js");
    expect(b.lines.filter((l) => l.kind === "add")).toHaveLength(2);
  });

  it("returns nothing for an empty diff", () => {
    expect(parseDiff("")).toEqual([]);
  });
});

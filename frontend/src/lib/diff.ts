/** Unified diff (git diff -U3) → files of numbered lines, for the Code and Merge request views. */
export interface DiffLine { kind: "add" | "del" | "ctx" | "hunk"; oldNo: number | null; newNo: number | null; text: string }
export interface DiffFile { path: string; added: number; removed: number; lines: DiffLine[] }

export function parseDiff(text: string): DiffFile[] {
  const files: DiffFile[] = [];
  let file: DiffFile | null = null;
  let oldNo = 0, newNo = 0;
  for (const raw of text.split("\n")) {
    if (raw.startsWith("diff --git ")) {
      const m = raw.match(/ b\/(.+)$/);
      file = { path: m ? m[1] : raw.slice(11), added: 0, removed: 0, lines: [] };
      files.push(file);
      continue;
    }
    if (!file) continue;
    if (raw.startsWith("+++ ") || raw.startsWith("--- ") || raw.startsWith("index ") || raw.startsWith("new file") || raw.startsWith("deleted file") || raw.startsWith("similarity") || raw.startsWith("rename ")) {
      if (raw.startsWith("+++ b/")) file.path = raw.slice(6);
      continue;
    }
    const h = raw.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$/);
    if (h) {
      oldNo = Number(h[1]);
      newNo = Number(h[2]);
      file.lines.push({ kind: "hunk", oldNo: null, newNo: null, text: raw });
      continue;
    }
    if (raw.startsWith("+")) {
      file.lines.push({ kind: "add", oldNo: null, newNo: newNo++, text: raw.slice(1) });
      file.added++;
    } else if (raw.startsWith("-")) {
      file.lines.push({ kind: "del", oldNo: oldNo++, newNo: null, text: raw.slice(1) });
      file.removed++;
    } else if (raw.startsWith(" ")) {
      file.lines.push({ kind: "ctx", oldNo: oldNo++, newNo: newNo++, text: raw.slice(1) });
    }
  }
  return files;
}

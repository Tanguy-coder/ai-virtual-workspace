import type { ReactNode } from "react";
import type { DiffFile } from "../lib/diff";

/** One file of a unified diff, with old/new line numbers. {@code after(newNo)} inserts review threads under a line. */
export function DiffView({ file, after }: { file: DiffFile; after?: (newNo: number | null, oldNo: number | null) => ReactNode }) {
  return (
    <div className="overflow-x-auto bg-panel font-mono text-[12px] leading-5">
      {file.lines.map((l, i) => (
        <div key={i}>
          <div className={`grid grid-cols-[44px_44px_18px_1fr] ${l.kind === "add" ? "bg-ok/10" : l.kind === "del" ? "bg-danger/10" : l.kind === "hunk" ? "bg-panel-2 text-faint" : ""}`}>
            <span className="select-none pr-2 text-right text-faint">{l.oldNo ?? ""}</span>
            <span className="select-none pr-2 text-right text-faint">{l.newNo ?? ""}</span>
            <span className={`select-none ${l.kind === "add" ? "text-ok" : l.kind === "del" ? "text-danger" : "text-faint"}`}>{l.kind === "add" ? "+" : l.kind === "del" ? "−" : ""}</span>
            <span className="whitespace-pre pr-4">{l.text || " "}</span>
          </div>
          {after?.(l.newNo, l.oldNo)}
        </div>
      ))}
      {file.lines.length === 0 && <p className="px-3 py-2 text-faint">Binary or empty change.</p>}
    </div>
  );
}

/** A plain file with line numbers (Code view, no diff). */
export function SourceView({ content }: { content: string }) {
  const lines = content.split("\n");
  if (lines.at(-1) === "") lines.pop();
  return (
    <div className="overflow-x-auto bg-panel font-mono text-[12px] leading-5">
      {lines.map((l, i) => (
        <div key={i} className="grid grid-cols-[48px_1fr]">
          <span className="select-none pr-3 text-right text-faint">{i + 1}</span>
          <span className="whitespace-pre pr-4">{l || " "}</span>
        </div>
      ))}
    </div>
  );
}

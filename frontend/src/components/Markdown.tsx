import type { ReactNode } from "react";

/**
 * Small, safe Markdown renderer for agent-written notes (headings, lists, code, bold, inline code, tables as text).
 * It builds React elements and never injects HTML.
 */
export function Markdown({ text, className = "" }: { text: string; className?: string }) {
  const blocks: ReactNode[] = [];
  const lines = text.replace(/\r\n/g, "\n").split("\n");
  let i = 0;
  let key = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.startsWith("```")) {
      const code: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith("```")) code.push(lines[i++]);
      i++;
      blocks.push(
        <pre key={key++} className="overflow-x-auto rounded-lg border border-line bg-panel-2 p-3 font-mono text-[12px] leading-relaxed">{code.join("\n")}</pre>,
      );
      continue;
    }
    const heading = /^(#{1,4})\s+(.*)$/.exec(line);
    if (heading) {
      const level = heading[1].length;
      const cls = level === 1 ? "text-[17px] font-semibold" : level === 2 ? "text-[15px] font-semibold" : "text-[13px] font-semibold";
      blocks.push(<p key={key++} className={`${cls} mt-4 tracking-tight first:mt-0`}>{inline(heading[2])}</p>);
      i++;
      continue;
    }
    if (/^\s*([-*]|\d+[.)])\s+/.test(line)) {
      const ordered = /^\s*\d+[.)]/.test(line);
      const items: ReactNode[] = [];
      while (i < lines.length && /^\s*([-*]|\d+[.)])\s+/.test(lines[i])) {
        const nested = /^\s{2,}/.test(lines[i]);
        items.push(
          <li key={items.length} className={nested ? "ml-4" : ""}>{inline(lines[i].replace(/^\s*([-*]|\d+[.)])\s+/, ""))}</li>,
        );
        i++;
      }
      blocks.push(ordered
        ? <ol key={key++} className="list-decimal space-y-1 pl-5">{items}</ol>
        : <ul key={key++} className="list-disc space-y-1 pl-5 marker:text-faint">{items}</ul>);
      continue;
    }
    if (line.trim() === "") {
      i++;
      continue;
    }
    const para: string[] = [];
    while (i < lines.length && lines[i].trim() !== "" && !/^(#{1,4}\s|```|\s*([-*]|\d+[.)])\s+)/.test(lines[i])) para.push(lines[i++]);
    blocks.push(
      <p key={key++} className={para[0].startsWith("|") ? "whitespace-pre font-mono text-[12px]" : "whitespace-pre-wrap"}>
        {inline(para.join("\n"))}
      </p>,
    );
  }
  return <div className={`space-y-2.5 text-[13px] leading-relaxed ${className}`}>{blocks}</div>;
}

function inline(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = pattern.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index));
    const token = m[0];
    out.push(token.startsWith("**")
      ? <strong key={m.index} className="font-semibold">{token.slice(2, -2)}</strong>
      : <code key={m.index} className="rounded bg-panel-2 px-1 py-0.5 font-mono text-[12px]">{token.slice(1, -1)}</code>);
    last = m.index + token.length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

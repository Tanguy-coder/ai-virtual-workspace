import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from "react";

/*
 * Studio Model primitives. Ink buttons, hairlines instead of boxes, no gradients,
 * shadows only on what floats. Signal orange is reserved for what unblocks a flow.
 */

type ButtonVariant = "primary" | "ghost" | "subtle" | "danger" | "signal";

export function Button({
  variant = "primary",
  size = "md",
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: "sm" | "md" }) {
  const styles: Record<ButtonVariant, string> = {
    primary: "bg-ink text-ink-fg hover:opacity-90",
    signal: "bg-accent text-accent-fg hover:brightness-105",
    ghost: "border border-line-strong bg-panel text-fg hover:bg-panel-2",
    subtle: "text-muted hover:bg-panel-2 hover:text-fg",
    danger: "border border-line-strong bg-panel text-danger hover:border-danger/50 hover:bg-danger/5",
  };
  const sizes = { sm: "h-7 px-2.5 text-[12px]", md: "h-8 px-3 text-[13px]" }[size];
  return (
    <button
      {...props}
      className={`inline-flex shrink-0 items-center justify-center gap-1.5 rounded-sm font-medium transition-colors duration-[120ms] disabled:cursor-not-allowed disabled:opacity-40 ${sizes} ${styles[variant]} ${className}`}
    />
  );
}

const fieldBase =
  "w-full rounded-sm border border-line-strong bg-panel text-[13px] text-fg outline-none transition-colors placeholder:text-faint hover:border-faint focus:border-accent";

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={`h-8 px-2.5 ${fieldBase} ${props.className ?? ""}`} />;
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={`h-8 px-2 ${fieldBase} ${props.className ?? ""}`} />;
}

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...props} className={`px-2.5 py-1.5 leading-relaxed ${fieldBase} ${props.className ?? ""}`} />;
}

export function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <label className="block space-y-1">
      <span className="text-[12px] font-medium text-muted">{label}</span>
      {children}
      {hint && <span className="block text-[11px] leading-4 text-faint">{hint}</span>}
    </label>
  );
}

/**
 * A region of a page. Rendered as a titled block separated by a hairline, not as a floating card;
 * pass `framed` when the content needs a sheet behind it (forms, editors).
 */
export function Card({ title, subtitle, action, children, className = "", padded = true, framed = true }: {
  title?: ReactNode; subtitle?: string; action?: ReactNode; children: ReactNode; className?: string; padded?: boolean; framed?: boolean;
}) {
  return (
    <section className={`${framed ? "rounded-md border border-line bg-panel" : ""} ${className}`}>
      {(title || action) && (
        <header className={`flex items-center justify-between gap-3 ${framed ? "border-b border-line px-4 py-2.5" : "pb-2"}`}>
          <div className="min-w-0">
            <h2 className="text-[13px] font-semibold">{title}</h2>
            {subtitle && <p className="text-[12px] leading-4 text-faint">{subtitle}</p>}
          </div>
          {action}
        </header>
      )}
      <div className={padded && framed ? "p-4" : ""}>{children}</div>
    </section>
  );
}

/** Section title used directly on the paper, with a hairline below. */
export function SectionTitle({ children, action, count }: { children: ReactNode; action?: ReactNode; count?: number }) {
  return (
    <div className="mb-2 flex items-center justify-between gap-3 border-b border-line pb-2">
      <h2 className="text-[15px] font-semibold leading-[22px]">
        {children}
        {count !== undefined && <span className="tabular ml-2 font-mono text-[12px] font-normal text-faint">{count}</span>}
      </h2>
      {action}
    </div>
  );
}

export type Tone = "muted" | "ok" | "warn" | "danger" | "active" | "accent" | "review" | "testing";

const TONE_DOT: Record<Tone, string> = {
  muted: "bg-faint", ok: "bg-ok", warn: "bg-warn", danger: "bg-danger", active: "bg-active", accent: "bg-accent", review: "bg-review", testing: "bg-testing",
};

export function Dot({ tone, live = false, className = "" }: { tone: Tone; live?: boolean; className?: string }) {
  if (tone === "danger") return <span className={`inline-block h-[7px] w-[7px] shrink-0 rotate-45 bg-danger ${className}`} />;
  return <span className={`inline-block h-[7px] w-[7px] shrink-0 rounded-full ${TONE_DOT[tone]} ${live ? "aiw-pulse" : ""} ${className}`} />;
}

export function Badge({ tone = "muted", children, className = "", dot = false }: { tone?: Tone; children: ReactNode; className?: string; dot?: boolean }) {
  const text: Record<Tone, string> = {
    muted: "text-muted", ok: "text-ok", warn: "text-warn", danger: "text-danger", active: "text-active", accent: "text-accent-2", review: "text-review", testing: "text-testing",
  };
  return (
    <span className={`inline-flex h-5 shrink-0 items-center gap-1.5 rounded-full bg-panel-2 px-2 text-[11px] font-medium ${dot ? "text-fg" : text[tone]} ${className}`}>
      {dot && <Dot tone={tone} />}
      {children}
    </span>
  );
}

export function Empty({ icon, title, children, action }: { icon?: ReactNode; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-start border-t border-line py-10">
      {icon && <div className="mb-3 text-faint">{icon}</div>}
      <p className="text-[15px] font-semibold">{title}</p>
      {children && <div className="mt-1 max-w-lg text-[13px] text-muted">{children}</div>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ErrorText({ error }: { error: unknown }) {
  if (!error) return null;
  return <p className="text-[12px] text-danger">{error instanceof Error ? error.message : String(error)}</p>;
}

export function PageHeader({ eyebrow, title, subtitle, action }: { eyebrow?: string; title: string; subtitle?: string; action?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
      <div className="min-w-0">
        {eyebrow && <p className="label mb-1">{eyebrow}</p>}
        <h1 className="text-[20px] font-semibold leading-[26px] tracking-[-0.01em]">{title}</h1>
        {subtitle && <p className="mt-0.5 max-w-2xl text-[13px] text-muted">{subtitle}</p>}
      </div>
      {action && <div className="flex flex-wrap items-center gap-2">{action}</div>}
    </div>
  );
}

/** Neutral icon holder; colour only when the icon itself carries meaning. */
export function IconChip({ children, tone = "muted" }: { children: ReactNode; tone?: Tone }) {
  const color = tone === "muted" || tone === "accent" ? "text-muted" : { ok: "text-ok", warn: "text-warn", danger: "text-danger", active: "text-active", review: "text-review", testing: "text-testing" }[tone];
  return <span className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-sm bg-panel-2 ${color}`}>{children}</span>;
}

export function Tabs<T extends string>({ value, onChange, items }: { value: T; onChange: (v: T) => void; items: { value: T; label: string; count?: number }[] }) {
  return (
    <div className="flex items-center gap-4" role="tablist">
      {items.map((i) => (
        <button
          key={i.value}
          role="tab"
          aria-selected={value === i.value}
          onClick={() => onChange(i.value)}
          className={`relative h-8 text-[13px] font-medium transition-colors ${value === i.value ? "text-fg after:absolute after:inset-x-0 after:-bottom-px after:h-[2px] after:bg-accent" : "text-faint hover:text-fg"}`}
        >
          {i.label}
          {i.count !== undefined && <span className="tabular ml-1.5 font-mono text-[11px] text-faint">{i.count}</span>}
        </button>
      ))}
    </div>
  );
}

/** Segmented control for view switches inside a toolbar. */
export function Segmented<T extends string>({ value, onChange, items }: { value: T; onChange: (v: T) => void; items: { value: T; label: string }[] }) {
  return (
    <div className="inline-flex h-8 rounded-sm border border-line-strong bg-panel p-0.5">
      {items.map((i) => (
        <button key={i.value} onClick={() => onChange(i.value)}
          className={`rounded-[3px] px-2.5 text-[12px] font-medium transition-colors ${value === i.value ? "bg-ink text-ink-fg" : "text-muted hover:text-fg"}`}>
          {i.label}
        </button>
      ))}
    </div>
  );
}

export function Skeleton({ className = "" }: { className?: string }) {
  return <div className={`skeleton ${className}`} />;
}

/** Loading placeholder for lists and tables. */
export function SkeletonRows({ rows = 5 }: { rows?: number }) {
  return (
    <div className="space-y-3 py-2">
      {Array.from({ length: rows }, (_, i) => <Skeleton key={i} className={`h-4 ${i % 3 === 2 ? "w-2/3" : "w-full"}`} />)}
    </div>
  );
}

/** Dense table shell: column headers on a hairline, rows separated by hairlines. */
export function Table({ head, children, className = "" }: { head: ReactNode[]; children: ReactNode; className?: string }) {
  return (
    <div className={`overflow-x-auto ${className}`}>
      <table className="w-full border-collapse text-[13px]">
        <thead>
          <tr className="border-b border-line-strong text-left">
            {head.map((h, i) => <th key={i} className="h-8 whitespace-nowrap px-3 text-[11px] font-medium text-faint first:pl-0 last:pr-0">{h}</th>)}
          </tr>
        </thead>
        <tbody className="[&>tr]:border-b [&>tr]:border-line [&_td]:px-3 [&_td]:py-2 [&_td:first-child]:pl-0 [&_td:last-child]:pr-0">{children}</tbody>
      </table>
    </div>
  );
}

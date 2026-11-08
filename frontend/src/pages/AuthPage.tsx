import { useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { api } from "../api/client";
import { Button, ErrorText, Field, Input } from "../components/ui";

export function AuthPage({ mode }: { mode: "login" | "register" }) {
  const queryClient = useQueryClient();
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === "register") await api.post("/api/v1/auth/register", { email, displayName, password });
      else await api.post("/api/v1/auth/login", { email, password });
      await queryClient.invalidateQueries();
      window.history.replaceState(null, "", "/");
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-full">
      <BrandPanel />
      <div className="flex flex-1 items-center justify-center px-6">
      <form onSubmit={submit} className="w-full max-w-sm space-y-4">
        <div className="mb-6">
          <h1 className="text-[20px] font-semibold leading-[26px]">{mode === "login" ? "Sign in" : "Create your account"}</h1>
          <p className="mt-1 text-[13px] text-muted">{mode === "login" ? "Back to your engineering company." : "You will be the CTO of your company."}</p>
        </div>
        <Field label="Email">
          <Input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        {mode === "register" && (
          <Field label="Display name">
            <Input required value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </Field>
        )}
        <Field label="Password" hint={mode === "register" ? "At least 12 characters." : undefined}>
          <Input
            type="password"
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            required
            minLength={mode === "register" ? 12 : undefined}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        <ErrorText error={error} />
        <Button type="submit" disabled={busy} className="w-full">
          {mode === "login" ? "Sign in" : "Create account"}
        </Button>
        <p className="pt-2 text-[13px] text-muted">
          {mode === "login" ? (
            <>No account? <Link className="font-medium text-fg underline" to="/register">Create one</Link></>
          ) : (
            <>Already registered? <Link className="font-medium text-fg underline" to="/">Sign in</Link></>
          )}
        </p>
      </form>
      </div>
    </div>
  );
}

/** Graphite brand panel shared by sign-in and onboarding. */
export function BrandPanel() {
  return (
    <aside className="hidden w-[44%] max-w-[620px] flex-col justify-between bg-graphite p-10 text-graphite-fg lg:flex">
      <p className="flex items-center gap-3 text-[13px] font-semibold"><span className="h-3 w-3 rotate-45 bg-accent" />AI Engineering Workspace</p>
      <div>
        <p className="max-w-md text-[28px] font-medium leading-[36px] tracking-[-0.01em]">
          A software engineering company of AI agents. You lead it as its CTO.
        </p>
        <ul className="mt-8 space-y-3 font-mono text-[12px] text-graphite-muted">
          <li>Lead · analyzes the brief, proposes the team, plans the work</li>
          <li>Office · every agent visible, every move a recorded event</li>
          <li>Validation · nothing is done until tests and review say so</li>
        </ul>
      </div>
      <p className="font-mono text-[11px] text-graphite-muted">Self-hosted · your keys · your code</p>
    </aside>
  );
}

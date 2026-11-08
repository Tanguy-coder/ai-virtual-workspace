import { useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { api } from "../api/client";
import type { Workspace } from "../api/types";
import { BrandPanel } from "./AuthPage";
import { Button, ErrorText, Field, Input } from "../components/ui";

export function OnboardingPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [error, setError] = useState<unknown>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      const ws = await api.post<Workspace>("/api/v1/workspaces", { name });
      await queryClient.invalidateQueries({ queryKey: ["workspaces"] });
      navigate(`/w/${ws.id}/settings?welcome=1`);
    } catch (err) {
      setError(err);
    }
  }

  return (
    <div className="flex min-h-full">
      <BrandPanel />
      <div className="flex flex-1 items-center justify-center px-6">
      <form onSubmit={submit} className="w-full max-w-md space-y-4">
        <h1 className="text-[20px] font-semibold">Create your company</h1>
        <p className="text-[13px] text-muted">
          A workspace is your virtual engineering company. You are its CTO: you will add AI providers, create
          projects and lead the agents.
        </p>
        <Field label="Workspace name">
          <Input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Acme Engineering" />
        </Field>
        <ErrorText error={error} />
        <Button type="submit" className="w-full">Create workspace</Button>
      </form>
      </div>
    </div>
  );
}

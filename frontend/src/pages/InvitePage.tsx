import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { useNavigate, useParams } from "react-router";
import { api } from "../api/client";
import type { InvitationPreview } from "../api/types";
import { Button, ErrorText } from "../components/ui";
import { AuthPage } from "./AuthPage";

const PENDING = "aiw.pendingInvite";

/** The token of an invitation opened before signing in, so it can be accepted once signed in. */
export function pendingInvite(): string | null {
  try {
    return sessionStorage.getItem(PENDING);
  } catch {
    return null;
  }
}

function remember(token: string | null) {
  try {
    if (token) sessionStorage.setItem(PENDING, token);
    else sessionStorage.removeItem(PENDING);
  } catch {
    /* storage unavailable: the link can be opened again */
  }
}

/** An invitation link (docs/18): what it is for, then sign in (or create the account) with the invited address and join. */
export function InvitePage({ signedIn }: { signedIn: boolean }) {
  const { token = "" } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const preview = useQuery({ queryKey: ["invitation", token], queryFn: () => api.get<InvitationPreview>(`/api/v1/invitations/${token}`), retry: false });
  const accept = useMutation({
    mutationFn: () => api.post<{ workspaceId: string }>(`/api/v1/invitations/${token}/accept`),
    onSuccess: async (r) => {
      remember(null);
      await qc.invalidateQueries({ queryKey: ["workspaces"] });
      navigate(`/w/${r.workspaceId}/projects`);
    },
  });
  useEffect(() => {
    if (!signedIn) remember(token);
  }, [signedIn, token]);

  const p = preview.data;
  return (
    <div className="flex min-h-full flex-col items-center justify-center gap-6 bg-paper px-4 py-10">
      <div className="w-full max-w-md rounded border border-line bg-panel p-5 text-[13px]">
        <div className="text-[11px] uppercase tracking-wide text-muted">Invitation</div>
        {preview.isLoading && <p className="mt-2 text-muted">Loading…</p>}
        {preview.isError && <p className="mt-2 text-danger">This invitation link is not valid.</p>}
        {p && (
          <>
            <p className="mt-2 text-[15px]">Join <span className="font-medium">{p.workspaceName}</span> as <span className="font-medium">{p.role.toLowerCase()}</span></p>
            <p className="mt-1 text-muted">For {p.email}. {signedIn ? "" : "Sign in, or create your account, with this address."}</p>
            {!p.valid && <p className="mt-2 text-danger">This invitation is {p.reason}.</p>}
            {signedIn && p.valid && (
              <Button className="mt-4" variant="primary" disabled={accept.isPending} onClick={() => accept.mutate()}>Join the workspace</Button>
            )}
            <ErrorText error={accept.error} />
          </>
        )}
      </div>
      {!signedIn && p?.valid && <AuthPage mode="login" />}
    </div>
  );
}

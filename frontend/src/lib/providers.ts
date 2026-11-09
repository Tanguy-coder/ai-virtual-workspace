import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { ProviderPreset } from "../api/types";

/** Ready-made provider settings (Anthropic, OpenAI, Gemini, Mistral, local servers...). */
export function usePresets() {
  return useQuery({ queryKey: ["provider-presets"], queryFn: () => api.get<ProviderPreset[]>("/api/v1/provider-presets"), staleTime: Infinity });
}

/** Models the provider reports for its stored key; a free call to the provider, cached for the session. */
export function useProviderModels(workspaceId: string, providerId: string | null | undefined) {
  return useQuery({
    queryKey: ["provider-models", providerId],
    queryFn: () => api.get<string[]>(`/api/v1/workspaces/${workspaceId}/providers/${providerId}/models`),
    enabled: !!providerId,
    staleTime: 10 * 60_000,
    retry: false,
  });
}

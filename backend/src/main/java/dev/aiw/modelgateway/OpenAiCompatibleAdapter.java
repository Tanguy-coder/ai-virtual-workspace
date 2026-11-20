package dev.aiw.modelgateway;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Any server implementing the OpenAI Chat Completions API (Ollama, vLLM, LM Studio, hosted gateways...).
 * Base URL example: http://localhost:11434/v1
 */
@Component
class OpenAiCompatibleAdapter implements ModelAdapter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient http;

    OpenAiCompatibleAdapter() {
        // HTTP/1.1 without h2c upgrade: many local model servers do not support the upgrade handshake.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMinutes(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public ProviderKind kind() {
        return ProviderKind.OPENAI_COMPATIBLE;
    }

    @Override
    public AdapterResult call(String providerId, String baseUrl, String apiKey, ModelRequest request) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ModelCallException("This provider has no base URL configured", 0, null);
        }
        List<Map<String, String>> messages = new ArrayList<>();
        // Stable prefix first: OpenAI-compatible providers cache identical prompt prefixes automatically.
        String system = request.fullSystem();
        if (system != null) {
            messages.add(Map.of("role", "system", "content", system));
        }
        for (ChatTurn turn : request.turns()) {
            messages.add(Map.of("role", turn.role() == ChatTurn.Role.USER ? "user" : "assistant", "content", turn.text()));
        }
        String modelKey = baseUrl + "|" + request.model();
        JsonNode response;
        try {
            try {
                response = post(baseUrl, apiKey, request, messages, COMPLETION_TOKENS.contains(modelKey));
            } catch (RestClientResponseException e) {
                // OpenAI's reasoning models (o-series, gpt-5…) refuse max_tokens (seen on T-27, 2026-11-19): retried
                // with max_completion_tokens, and remembered for the model.
                if (e.getStatusCode().value() != 400 || !e.getResponseBodyAsString().contains("max_completion_tokens")
                        || COMPLETION_TOKENS.contains(modelKey)) {
                    throw e;
                }
                COMPLETION_TOKENS.add(modelKey);
                response = post(baseUrl, apiKey, request, messages, true);
            }
        } catch (RestClientResponseException e) {
            String failed = failedToolGeneration(e);
            if (failed != null) {
                // Groq-style "tool_use_failed": the model answered with a native tool call although none was offered.
                // Its generation is kept as the answer; the caller reads the intended action from it.
                return new AdapterResult(failed, request.model(), 0, 0, false, "tool_use_failed", 0, 0);
            }
            String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
            throw new ModelCallException("Provider error " + e.getStatusCode().value() + ": "
                    + abbreviate(e.getResponseBodyAsString()), e.getStatusCode().value(), e, seconds(retryAfter));
        } catch (RestClientException e) {
            throw new ModelCallException("Could not reach the provider: " + e.getMessage(), 0, e);
        }
        if (response == null || !response.path("choices").isArray() || response.path("choices").isEmpty()) {
            throw new ModelCallException("Provider returned no choices", 0, null);
        }
        JsonNode choice = response.path("choices").get(0);
        String finish = choice.path("finish_reason").asString("unknown");
        if ("length".equals(finish)) finish = "max_tokens"; // OpenAI's name for a reply cut off by max_tokens
        long prompt = response.path("usage").path("prompt_tokens").asLong(0);
        long cached = Math.min(prompt, response.path("usage").path("prompt_tokens_details").path("cached_tokens").asLong(0));
        String content = choice.path("message").path("content").asString("");
        JsonNode calls = choice.path("message").path("tool_calls");
        if (content.isBlank() && calls.isArray() && !calls.isEmpty()) {
            // Models trained on native tool calling (gpt-oss behind OpenRouter) may answer with tool calls although
            // none was offered: they are kept as the answer, {"tool_calls": [{"name", "arguments"}]}, for the caller.
            content = nativeCalls(calls);
            if (!"max_tokens".equals(finish)) finish = "tool_calls";
        }
        return new AdapterResult(
                content,
                response.path("model").asString(request.model()),
                prompt - cached,
                response.path("usage").path("completion_tokens").asLong(0),
                "content_filter".equals(finish),
                finish, 0, cached);
    }

    /** Models (base URL | id) that take max_completion_tokens instead of max_tokens. */
    private static final java.util.Set<String> COMPLETION_TOKENS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private JsonNode post(String baseUrl, String apiKey, ModelRequest request, List<Map<String, String>> messages,
                          boolean completionTokens) {
        Map<String, Object> body = Map.of("model", request.model(), "messages", messages,
                completionTokens ? "max_completion_tokens" : "max_tokens", request.maxTokens());
        // Serialized up front so the request carries a Content-Length instead of a chunked body.
        byte[] payload = JSON.writeValueAsBytes(body);
        var spec = http.post().uri(baseUrl.replaceAll("/+$", "") + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
        if (apiKey != null && !apiKey.isBlank()) spec.header("Authorization", "Bearer " + apiKey);
        return spec.retrieve().body(JsonNode.class);
    }

    @Override
    public List<String> listModels(String providerId, String baseUrl, String apiKey) {
        return listModelInfo(providerId, baseUrl, apiKey).stream().map(ModelInfo::id).toList();
    }

    @Override
    public List<ModelInfo> listModelInfo(String providerId, String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ModelCallException("This provider has no base URL configured", 0, null);
        }
        JsonNode response;
        try {
            var spec = http.get().uri(baseUrl.replaceAll("/+$", "") + "/models");
            if (apiKey != null && !apiKey.isBlank()) spec.header("Authorization", "Bearer " + apiKey);
            response = spec.retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new ModelCallException("Provider error " + e.getStatusCode().value() + ": "
                    + abbreviate(e.getResponseBodyAsString()), e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new ModelCallException("Could not reach the provider: " + e.getMessage(), 0, e);
        }
        return parseModels(response);
    }

    /**
     * OpenAI shape {"data": [{"id": ...}]}; Gemini's compatibility layer prefixes ids with "models/". The context
     * window is "context_window" (Groq) or "context_length" (OpenRouter, Together); inactive models are left out.
     */
    private static Double perMillion(JsonNode perToken) {
        if (perToken == null || perToken.isMissingNode() || perToken.isNull()) return null;
        try {
            double v = Double.parseDouble(perToken.asString(perToken.toString()));
            return v < 0 ? null : v * 1_000_000; // OpenRouter uses -1 for routers with variable prices
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static List<ModelInfo> parseModels(JsonNode response) {
        java.util.Map<String, ModelInfo> out = new java.util.TreeMap<>();
        for (JsonNode m : response == null ? JSON.createArrayNode() : response.path("data")) {
            String id = m.path("id").asString("");
            if (id.isBlank() || !m.path("active").asBoolean(true)) continue;
            if (id.startsWith("models/")) id = id.substring("models/".length());
            long window = m.path("context_window").asLong(m.path("context_length").asLong(0));
            // OpenRouter: "pricing": {"prompt": "0.000003", "completion": "0.000015"} in USD per token.
            JsonNode pricing = m.path("pricing");
            Double in = perMillion(pricing.path("prompt")), outPrice = perMillion(pricing.path("completion"));
            out.putIfAbsent(id, new ModelInfo(id, window > 0 ? window : null, outPrice == null ? null : in, in == null ? null : outPrice));
        }
        return List.copyOf(out.values());
    }

    private static String failedToolGeneration(RestClientResponseException e) {
        if (e.getStatusCode().value() != 400) return null;
        try {
            JsonNode error = JSON.readTree(e.getResponseBodyAsString()).path("error");
            String generation = error.path("failed_generation").asString("");
            return "tool_use_failed".equals(error.path("code").asString("")) && !generation.isBlank() ? generation : null;
        } catch (RuntimeException parse) {
            return null;
        }
    }

    private static long seconds(String retryAfter) {
        if (retryAfter == null) return -1;
        try {
            return (long) Math.ceil(Double.parseDouble(retryAfter.strip()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    private static String nativeCalls(JsonNode calls) {
        StringBuilder sb = new StringBuilder("{\"tool_calls\": [");
        boolean first = true;
        for (JsonNode c : calls) {
            JsonNode fn = c.path("function");
            if (!fn.path("name").isString()) continue;
            String args = fn.path("arguments").isString() ? fn.path("arguments").asString() : fn.path("arguments").toString();
            if (args.isBlank()) args = "{}";
            if (!first) sb.append(", ");
            sb.append("{\"name\": ").append(tools.jackson.databind.node.StringNode.valueOf(fn.path("name").asString()).toString())
                    .append(", \"arguments\": ").append(args.strip().startsWith("{") ? args : tools.jackson.databind.node.StringNode.valueOf(args).toString()).append("}");
            first = false;
        }
        return sb.append("]}").toString();
    }
}

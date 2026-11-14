package dev.aiw.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Fake model provider speaking both the Anthropic Messages API and the OpenAI Chat Completions API,
 * recording every request so tests can check exactly what the platform sent.
 */
public class StubModelServer implements AutoCloseable {
    public record Captured(String path, Map<String, List<String>> headers, String body) {}

    private final HttpServer server;
    public final List<Captured> requests = new CopyOnWriteArrayList<>();
    /** Text the Anthropic endpoint answers for a given request body; "OK" by default. */
    public volatile Function<String, String> anthropicResponder = body -> "OK";
    /** Input tokens the Anthropic endpoint reports per call (budgets can be exhausted on purpose). */
    public volatile int anthropicInputTokens = 12;
    /** Content the OpenAI-compatible endpoint answers for a given request body. */
    public volatile Function<String, String> openAiResponder = body -> "Hello from stub";
    /** Error answers {status, body} the OpenAI-compatible endpoint gives first, one per request (rate limits...). */
    /** What GET /v1/models answers. */
    public volatile String models = """
            {"object":"list","data":[{"id":"stub-large","object":"model"},{"id":"models/stub-small","object":"model"}]}
            """;
    public final java.util.Queue<Object[]> openAiErrors = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public StubModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            Object[] error = openAiErrors.poll();
            if (error != null) {
                requests.add(new Captured(ex.getRequestURI().getPath(), Map.copyOf(ex.getRequestHeaders()),
                        new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] bytes = ((String) error[1]).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders((Integer) error[0], bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
                return;
            }
            // A responder answer starting with "RAW:" is the whole response body (e.g. native tool_calls).
            respond(ex, body -> {
                String answer = openAiResponder.apply(body);
                if (answer.startsWith("RAW:")) return answer.substring(4);
                return """
                {"id":"c1","object":"chat.completion","model":"stub-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1000,"completion_tokens":500,"total_tokens":1500}}
                """.formatted(TestApi.JSON.writeValueAsString(answer));
            });
        });
        server.createContext("/v1/models", ex -> respond(ex, body -> models));
        server.createContext("/v1/messages", ex -> respond(ex, body -> """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":%s}],"stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":%d,"output_tokens":3}}
                """.formatted(TestApi.JSON.writeValueAsString(anthropicResponder.apply(body)), anthropicInputTokens)));
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange ex, Function<String, String> answer) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Captured(ex.getRequestURI().getPath(), Map.copyOf(ex.getRequestHeaders()), body));
        byte[] bytes;
        try {
            bytes = answer.apply(body).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            bytes = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"stub failure\"}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(500, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

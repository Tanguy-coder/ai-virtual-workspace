package dev.aiw.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal browser-like HTTP client: keeps the session cookie and echoes the CSRF token. */
public class TestApi {
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final String base;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;

    public TestApi(int port) {
        this.base = "http://localhost:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
    }

    public record Response(int status, String body) {
        public JsonNode json() {
            return JSON.readTree(body);
        }
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    public Response post(String path, Object body) {
        return send(withCsrf(json(HttpRequest.newBuilder(URI.create(base + path)), body).method("POST", publisher(body))));
    }

    public Response put(String path, Object body) {
        return send(withCsrf(json(HttpRequest.newBuilder(URI.create(base + path)), body).method("PUT", publisher(body))));
    }

    public Response postWithoutCsrf(String path, Object body) {
        return send(json(HttpRequest.newBuilder(URI.create(base + path)), body).method("POST", publisher(body)));
    }

    public Response upload(String path, String filename, String content) {
        String boundary = "----aiw" + UUID.randomUUID();
        String payload = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/markdown\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n";
        return send(withCsrf(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))));
    }

    /** Registers a fresh user and returns its id. */
    public String register(String name) {
        Response r = post("/api/v1/auth/register", java.util.Map.of(
                "email", name + "-" + UUID.randomUUID() + "@example.test",
                "displayName", name,
                "password", "correct horse battery staple"));
        if (r.status() != 201) throw new AssertionError("register failed: " + r);
        return r.json().get("id").asString();
    }

    public String cookieHeader() {
        return cookies.getCookieStore().getCookies().stream()
                .map(c -> c.getName() + "=" + c.getValue())
                .reduce((a, b) -> a + "; " + b).orElse("");
    }

    private HttpRequest.Builder withCsrf(HttpRequest.Builder builder) {
        String token = csrfToken();
        if (token == null) {
            get("/api/v1/auth/csrf");
            token = csrfToken();
        }
        return builder.header("X-XSRF-TOKEN", token);
    }

    private String csrfToken() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElse(null);
    }

    private static HttpRequest.Builder json(HttpRequest.Builder builder, Object body) {
        return builder.header("Content-Type", "application/json").header("Accept", "application/json");
    }

    private static HttpRequest.BodyPublisher publisher(Object body) {
        return HttpRequest.BodyPublishers.ofString(body == null ? "{}" : JSON.writeValueAsString(body));
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> r = http.send(builder.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}

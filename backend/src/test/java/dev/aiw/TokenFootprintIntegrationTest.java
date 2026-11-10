package dev.aiw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.modelgateway.ChatTurn;
import dev.aiw.modelgateway.ModelGateway;
import dev.aiw.modelgateway.ModelRequest;
import dev.aiw.modelgateway.ModelResult;
import dev.aiw.support.StubModelServer;
import dev.aiw.support.TestApi;
import dev.aiw.support.TestcontainersConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * Token footprint of real journeys (docs/09-sobriete-tokens.md, T1). Replays UC-1 with a real specification
 * (Prompt.md), then three chat messages and a 4-agent, 3-round meeting, and measures what the platform sends
 * to the model. Tokens are estimated at 4 characters each; the provider cache is simulated from the
 * cache breakpoints found in each request (write 1.25×, read 0.1×, minimum 1024 tokens).
 * The report is written to target/token-footprint.md; the budgets below guard against regressions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiw.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aiw.web.allowed-origins=http://localhost:5180",
        "aiw.execution.auto-start=false", "aiw.data-dir=${java.io.tmpdir}/aiw-test-data"
})
@Import(TestcontainersConfig.class)
class TokenFootprintIntegrationTest {
    static StubModelServer stub;

    @LocalServerPort
    int port;

    @BeforeAll
    static void startStub() throws Exception {
        stub = new StubModelServer();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @Test
    void measuresWhatJourneysSendToTheModel() throws Exception {
        stub.anthropicResponder = TokenFootprintIntegrationTest::script;
        TestApi api = new TestApi(port);
        api.register("cto");
        String ws = api.post("/api/v1/workspaces", Map.of("name", "Studio")).json().get("id").asString();
        api.post("/api/v1/workspaces/" + ws + "/providers", Map.of(
                "kind", "ANTHROPIC", "name", "Anthropic", "baseUrl", stub.baseUrl(), "apiKey", "sk-ant-test"));
        String p = api.post("/api/v1/workspaces/" + ws + "/projects", Map.of("name", "AI Engineering Workspace")).json()
                .get("id").asString();
        String spec = Files.readString(Path.of("../Prompt.md"));
        assertThat(api.upload("/api/v1/projects/" + p + "/documents", "Prompt.md", spec).status()).isEqualTo(201);

        // UC-1: briefing, answers, proposal, hiring with kickoff, plan.
        String briefingId = api.post("/api/v1/projects/" + p + "/lead/briefings", Map.of()).json().get("id").asString();
        await(() -> api.get("/api/v1/briefings/" + briefingId).json(),
                d -> d.get("briefing").get("status").asString().equals("AWAITING_ANSWERS"));
        api.put("/api/v1/briefings/" + briefingId + "/answers",
                Map.of("answers", Map.of("q1", "PostgreSQL", "q2", "Self-hosted with Docker Compose")));
        JsonNode proposed = await(() -> api.get("/api/v1/briefings/" + briefingId).json(),
                d -> d.get("briefing").get("status").asString().equals("PROPOSED"));
        api.post("/api/v1/hiring-proposals/" + proposed.get("proposals").get(0).get("id").asString() + "/accept", Map.of(
                "lines", List.of(
                        Map.of("templateId", "rt_java", "count", 2, "department", "BACKEND", "seniority", "SENIOR"),
                        Map.of("templateId", "rt_qa", "count", 1, "department", "QA", "seniority", "MID")),
                "kickoff", true));
        await(() -> api.get("/api/v1/projects/" + p + "/tasks").json(), t -> t.size() == 3);
        mark("UC-1");

        // Chat: three messages to one engineer, each answered before the next.
        JsonNode agents = api.get("/api/v1/projects/" + p + "/agents").json();
        String lead = null, engineer = null;
        List<String> team = new ArrayList<>();
        for (JsonNode a : agents) {
            if (a.get("lead").asBoolean()) lead = a.get("id").asString();
            else team.add(a.get("id").asString());
            if (a.get("name").asString().equals("Java Engineer 01")) engineer = a.get("id").asString();
        }
        String eng = engineer;
        for (String msg : List.of("What will you build first?", "Which tables do you need?", "OK, go ahead.")) {
            api.post("/api/v1/agents/" + eng + "/messages", Map.of("content", msg));
            int expected = (List.of("What will you build first?", "Which tables do you need?", "OK, go ahead.").indexOf(msg) + 1) * 2;
            await(() -> api.get("/api/v1/agents/" + eng + "/messages").json(), m -> m.size() >= expected
                    && m.get(m.size() - 1).get("authorKind").asString().equals("AGENT"));
        }
        mark("Chat ×3");

        // Ad hoc meeting: the Lead and three agents, three rounds.
        List<String> people = new ArrayList<>(List.of(lead));
        people.addAll(team);
        api.post("/api/v1/projects/" + p + "/meetings", Map.of("title", "API contract review",
                "agenda", "Agree on the accounts and transfers API contract.", "organizerId", lead,
                "participantIds", people, "rounds", 3));
        await(() -> api.get("/api/v1/projects/" + p + "/meetings").json(), m -> m.size() == 2
                && m.get(0).get("status").asString().equals("ENDED") && m.get(1).get("status").asString().equals("ENDED"));
        mark("Meeting 4×3");

        Files.createDirectories(Path.of("target"));
        String report = report();
        Files.writeString(Path.of("target/token-footprint.md"), report);
        System.out.println(report);

        // Budgets (billed-equivalent input tokens). Baseline before docs/09: UC-1 57 730, chat 19 577, meeting 98 292.
        assertThat(totals.get("UC-1")[2]).isLessThan(30_000);
        assertThat(totals.get("Chat ×3")[2]).isLessThan(4_000);
        assertThat(totals.get("Meeting 4×3")[2]).isLessThan(30_000);

        // Minutes are an extraction: they run on the provider's economy model.
        List<String> minutesModels = stub.requests.stream().filter(r -> r.body().contains("Write its minutes"))
                .map(r -> TestApi.JSON.readTree(r.body()).path("model").asString()).toList();
        assertThat(minutesModels).hasSize(2).allMatch("claude-haiku-5-5"::equals);

        // An identical request is answered from the memoized result, without calling the provider again.
        ModelRequest same = new ModelRequest(ws, p, eng, api.get("/api/v1/workspaces/" + ws + "/providers").json()
                .get(0).get("id").asString(), "claude-opus-5-5", "Be brief.",
                List.of(new ChatTurn(ChatTurn.Role.USER, "Say OK")), 16, "low");
        int before = stub.requests.size();
        assertThat(gateway.complete(same).reused()).isFalse();
        ModelResult again = gateway.complete(same);
        assertThat(again.reused()).isTrue();
        assertThat(again.text()).isEqualTo("OK");
        assertThat(stub.requests.size()).isEqualTo(before + 1);
    }

    @org.springframework.beans.factory.annotation.Autowired
    ModelGateway gateway;

    // ---------- measurement ----------

    private final List<Integer> marks = new ArrayList<>();
    private final List<String> markNames = new ArrayList<>();

    private void mark(String name) {
        marks.add(stub.requests.size());
        markNames.add(name);
    }

    record Call(String journey, String kind, long tokens, double billed) {}

    private String report() {
        List<Call> calls = new ArrayList<>();
        Set<String> cache = new HashSet<>();
        int from = 0;
        for (int j = 0; j < marks.size(); j++) {
            for (int i = from; i < marks.get(j); i++) {
                StubModelServer.Captured c = stub.requests.get(i);
                if (!c.path().equals("/v1/messages")) continue;
                calls.add(measure(markNames.get(j), TestApi.JSON.readTree(c.body()), cache));
            }
            from = marks.get(j);
        }
        StringBuilder sb = new StringBuilder("# Token footprint\n\n| Journey | Call | Calls | Input tokens (est.) | Billed-equivalent input |\n|---|---|---:|---:|---:|\n");
        Map<String, long[]> rows = new LinkedHashMap<>();
        for (Call c : calls) {
            long[] r = rows.computeIfAbsent(c.journey() + "|" + c.kind(), k -> new long[3]);
            r[0]++;
            r[1] += c.tokens();
            r[2] += Math.round(c.billed());
        }
        rows.forEach((k, r) -> sb.append("| ").append(k.replace("|", " | ")).append(" | ").append(r[0]).append(" | ")
                .append(r[1]).append(" | ").append(r[2]).append(" |\n"));
        Map<String, long[]> journeys = new LinkedHashMap<>();
        for (Call c : calls) {
            long[] r = journeys.computeIfAbsent(c.journey(), k -> new long[3]);
            r[0]++;
            r[1] += c.tokens();
            r[2] += Math.round(c.billed());
        }
        sb.append("\n| Journey | Calls | Input tokens (est.) | Billed-equivalent input |\n|---|---:|---:|---:|\n");
        journeys.forEach((k, r) -> sb.append("| ").append(k).append(" | ").append(r[0]).append(" | ").append(r[1])
                .append(" | ").append(r[2]).append(" |\n"));
        totals = journeys;
        return sb.toString();
    }

    Map<String, long[]> totals;

    /**
     * Estimated tokens of a request and its billed equivalent, simulating the provider cache: the longest
     * breakpoint prefix already cached is read (0.1x), the rest up to the last breakpoint is written (1.25x),
     * the remainder is plain input. Prefixes under 1024 tokens are not cached.
     */
    private static Call measure(String journey, JsonNode body, Set<String> cache) {
        List<String> blocks = new ArrayList<>();
        List<Integer> breakpoints = new ArrayList<>();
        JsonNode system = body.path("system");
        if (system.isString()) blocks.add(system.asString());
        else for (JsonNode b : system) {
            blocks.add(b.path("text").asString());
            if (b.has("cache_control")) breakpoints.add(blocks.size() - 1);
        }
        for (JsonNode m : body.path("messages")) {
            JsonNode content = m.path("content");
            if (content.isString()) blocks.add(content.asString());
            else for (JsonNode b : content) {
                blocks.add(b.path("text").asString());
                if (b.has("cache_control")) breakpoints.add(blocks.size() - 1);
            }
        }
        if (body.has("cache_control")) breakpoints.add(blocks.size() - 1); // automatic caching: the last block
        long total = blocks.stream().mapToLong(String::length).sum() / 4;
        String model = body.path("model").asString();
        long read = 0, cachedUpTo = 0;
        List<String> prefixes = new ArrayList<>();
        for (int b : breakpoints) {
            String prefix = String.join("\n", blocks.subList(0, b + 1));
            long tokens = prefix.length() / 4;
            if (tokens < 1024) continue;
            prefixes.add(model + "\n" + prefix);
            if (cache.contains(model + "\n" + prefix)) read = tokens;
            cachedUpTo = tokens;
        }
        double billed = (total - cachedUpTo) + read * 0.1 + (cachedUpTo - read) * 1.25;
        cache.addAll(prefixes);
        return new Call(journey, kind(String.join("\n", blocks)), total, billed);
    }

    private static String kind(String body) {
        if (body.contains("analyze the project specification")) return "Lead · analysis";
        if (body.contains("Now propose:")) return "Lead · architecture and team";
        if (body.contains("Break the project down")) return "Lead · plan";
        if (body.contains("Write its minutes")) return "Meeting · minutes";
        if (body.contains("It is your turn")) return "Meeting · turn";
        return "Chat · reply";
    }

    static String script(String body) {
        if (body.contains("It is your turn")) {
            return "I will own the accounts API. I see a dependency on the database schema and a risk on idempotent "
                    + "transfers; I propose we agree on error codes first. Question for QA Engineer 01: which "
                    + "scenarios do you need seeded? ".repeat(3);
        }
        return OrchestrationIntegrationTest.script(body);
    }

    private static JsonNode await(Supplier<JsonNode> read, Predicate<JsonNode> done) {
        long deadline = System.currentTimeMillis() + 60_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = read.get();
            if (done.test(last)) return last;
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Condition not met in time; last state: " + last);
    }
}

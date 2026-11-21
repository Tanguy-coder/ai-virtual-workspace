package dev.aiw.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small models write actions in other shapes; the platform reads them instead of asking again. */
class ActionShapesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode norm(String action) {
        return TaskExecution.normalizeAction(JSON.readTree(action));
    }

    /** Seen on "To do list" (2026-11-18): the tool as the key, rejected 10 times as Unknown tool "". */
    @Test
    void theToolAsTheKey() {
        JsonNode a = norm("{\"task.block\": {\"reason\": \"The lint test expects undefined\"}}");
        assertThat(a.path("tool").asString()).isEqualTo("task.block");
        assertThat(a.path("args").path("reason").asString()).isEqualTo("The lint test expects undefined");
        assertThat(norm("{\"tool\": \"\", \"file.read\": {\"path\": \"app.js\"}}").path("tool").asString()).isEqualTo("file.read");
    }

    @Test
    void otherKeyNames() {
        JsonNode a = norm("{\"name\": \"file.read\", \"arguments\": {\"path\": \"app.js\"}}");
        assertThat(a.path("tool").asString()).isEqualTo("file.read");
        assertThat(a.path("args").path("path").asString()).isEqualTo("app.js");
        assertThat(norm("{\"action\": \"exec\", \"parameters\": {\"command\": \"npm test\"}}").path("args").path("command").asString())
                .isEqualTo("npm test");
        assertThat(norm("{\"tool\": \"repo.tree\"}").path("tool").asString()).isEqualTo("repo.tree");
    }

    /** Seen on T-32 (2026-11-21): "repo.exec" rejected as an unusable reply, a model round trip for a name. */
    @Test
    void toolNamesSmallModelsWriteAreResolved() {
        assertThat(norm("{\"tool\": \"repo.exec\", \"args\": {\"command\": \"npm test\"}}").path("tool").asString()).isEqualTo("exec");
        assertThat(AgentTools.resolve("functions.file.read")).isEqualTo("file.read");
        assertThat(AgentTools.resolve("read_file")).isEqualTo("file.read");
        assertThat(AgentTools.resolve("readFile")).isEqualTo("file.read");
        assertThat(AgentTools.resolve("file_read")).isEqualTo("file.read");
        assertThat(AgentTools.resolve("run_command")).isEqualTo("exec");
        assertThat(AgentTools.resolve("list_files")).isEqualTo("repo.tree");
        assertThat(norm("{\"repo.exec\": {\"command\": \"npm test\"}}").path("tool").asString()).isEqualTo("exec");
        // A guess never deletes: only the exact name (or its separators) reaches file.delete.
        assertThat(AgentTools.resolve("delete_file")).isNull();
        assertThat(AgentTools.resolve("repo.file.delete")).isNull();
        assertThat(AgentTools.resolve("file_delete")).isEqualTo("file.delete");
        assertThat(AgentTools.resolve("rm")).isNull();
        assertThat(AgentTools.resolve("deploy")).isNull();
    }

    @Test
    void ambiguousShapesAreLeftForTheModelToFix() {
        assertThat(norm("{\"file.read\": {}, \"exec\": {}}").path("tool").isMissingNode()).isTrue();
        assertThat(norm("{\"tool\": \"rm\", \"args\": {}}").path("tool").asString()).isEqualTo("rm");
    }

    /** Seen on T-15 (2026-11-18): no action, only "waiting for the Lead": read as a block request, not an unusable reply. */
    @Test
    void noActionButAReasonIsABlockRequest() {
        var node = (tools.jackson.databind.node.ObjectNode) JSON.readTree(
                "{\"thought\": \"Waiting for Lead's clarification; no actions can be taken until then.\", \"actions\": []}");
        TaskExecution.blockWhenIdle(node);
        assertThat(node.path("actions").get(0).path("tool").asString()).isEqualTo("task.block");
        assertThat(node.path("actions").get(0).path("args").path("reason").asString()).startsWith("Waiting for Lead's clarification");
    }

    /** T-21: blocks that ask nothing are submitted by the platform; real questions still go to the Lead. */
    @Test
    void blocksThatWaitForNothing() {
        assertThat(TaskExecution.waitsForNothing("Proceed to implement the next feature or wait for further instructions.")).isTrue();
        assertThat(TaskExecution.waitsForNothing("Wait for Lead's instructions to continue the to do list project.")).isTrue();
        assertThat(TaskExecution.waitsForNothing("Task deletion has been implemented and tests pass.")).isTrue();
        assertThat(TaskExecution.waitsForNothing("Waiting for the Lead's clarification: should tasks have ids?")).isFalse();
        assertThat(TaskExecution.waitsForNothing("I need an API key for the weather service; waiting.")).isFalse();
        assertThat(TaskExecution.waitsForNothing("Tests fail with jsdom; waiting for help.")).isFalse();
    }

    private static RunStep st(int seq, String kind, String tool, String summary, String outcome) {
        return new RunStep("s" + seq, "r", seq, kind, tool, summary, java.util.Map.of(), outcome, 0, 0, 0, java.time.Instant.now());
    }

    /** T-28: dozens of refused edits, 40 steps a segment. Six since the last instruction stop the agent. */
    @Test
    void refusedEditsStopTheAgentAfterSix() {
        java.util.List<RunStep> steps = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) steps.add(st(i, "TOOL", "file.edit", "not applied · syntax error at line " + i, "ERROR"));
        steps.add(st(6, "MESSAGE", null, "Message from the Lead", "OK"));
        for (int i = 7; i <= 11; i++) steps.add(st(i, "TOOL", "file.edit", "not applied · syntax error at line " + i, "ERROR"));
        steps.add(st(12, "TOOL", "file.read", "file.read src/app.js", "OK"));
        assertThat(TaskExecution.editsKeepFailing(steps)).isNull(); // 5 since the Lead's message
        steps.add(st(13, "TOOL", "file.write", "file.write src/app.js · syntax error", "ERROR"));
        assertThat(TaskExecution.editsKeepFailing(steps)).contains("6 changes refused").contains("file.write src/app.js");
    }

    /** The context starts after the latest message; during a takeover, after the takeover started. */
    @Test
    void theContextStartsAtTheLatestIntervention() {
        java.util.List<RunStep> steps = java.util.List.of(st(1, "MODEL", null, "a", "OK"), st(2, "MESSAGE", null, "Message from the Lead", "OK"),
                st(3, "PLATFORM", null, "Resumed", "OK"), st(4, "MODEL", null, "b", "OK"), st(5, "PLATFORM", null, "The Lead takes over: x", "OK"),
                st(6, "MODEL", null, "Lead: c", "OK"));
        assertThat(TaskExecution.contextStart(steps, java.util.Optional.empty())).isEqualTo(2);
        assertThat(TaskExecution.contextStart(steps, java.util.Optional.of(new TaskRuns.Takeover("lead", 5, 0)))).isEqualTo(5);
        assertThat(TaskExecution.contextStart(steps.subList(0, 1), java.util.Optional.empty())).isZero();
    }
}

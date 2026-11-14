package dev.aiw.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Small models drop a closing bracket; the platform repairs it instead of spending a correction round trip. */
class ReplyRepairTest {

    /** The reply seen on the "To do list" project: the last action object is never closed before "]". */
    @Test
    void aMissingCloserBeforeTheArrayEndIsRepaired() {
        String reply = """
                {"thought":"Add guards","actions":[{"tool":"file.edit","args":{"path":"app.js","old":"button.addEventListener('click', handleAdd);","new":"if (button) { button.addEventListener('click', handleAdd); }"}},{"tool":"file.edit","args":{"path":"app.js","old":"});","new":"}); }"}]}""";
        JsonNode node = AgentRunner.parse(reply);
        assertThat(node.get("actions")).hasSize(2);
        assertThat(node.get("actions").get(1).get("args").get("new").asString()).isEqualTo("}); }");
    }

    @Test
    void bracketsInsideStringsAreLeftAlone() {
        assertThat(AgentRunner.balanceBrackets("{\"a\":\"]}\\\"[\",\"b\":[1,2}"))
                .isEqualTo("{\"a\":\"]}\\\"[\",\"b\":[1,2]}");
    }

    @Test
    void unrepairableJsonStillFailsWithAReadableReason() {
        assertThatThrownBy(() -> AgentRunner.parse("{\"thought\": \"x\" \"actions\": []}"))
                .isInstanceOf(AgentRunner.InvalidOutput.class)
                .hasMessageStartingWith("The JSON is malformed")
                .hasMessageNotContaining("[Source");
    }
}

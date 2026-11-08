package dev.aiw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiw.support.TestApi;
import dev.aiw.support.TestcontainersConfig;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiw.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "aiw.web.allowed-origins=http://localhost:5180",
        "aiw.events.relay-interval-ms=50"
})
@Import(TestcontainersConfig.class)
class RealtimeIntegrationTest {
    @LocalServerPort
    int port;

    @Test
    void membersReceiveWorkspaceEventsLiveAndStrangersAreRefused() throws Exception {
        TestApi owner = new TestApi(port);
        owner.register("owner");
        String workspaceId = owner.post("/api/v1/workspaces", Map.of("name", "WS")).json().get("id").asString();

        BlockingQueue<Map<?, ?>> received = new LinkedBlockingQueue<>();
        StompSession session = connect(owner, new StompSessionHandlerAdapter() {});
        session.subscribe("/topic/workspace." + workspaceId, collector(received));
        Thread.sleep(300); // let the SUBSCRIBE frame reach the broker

        owner.post("/api/v1/workspaces/" + workspaceId + "/projects", Map.of("name", "Live"));
        Map<?, ?> event = poll(received, "project.created");
        assertThat(event.get("workspaceId")).isEqualTo(workspaceId);
        assertThat(poll(received, "agent.hired")).isNotNull();

        TestApi stranger = new TestApi(port);
        stranger.register("stranger");
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        BlockingQueue<Map<?, ?>> leaked = new LinkedBlockingQueue<>();
        StompSession strangerSession = connect(stranger, new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(String.valueOf(headers.get("message")));
            }

            @Override
            public void handleException(StompSession s, StompCommand c, StompHeaders h, byte[] p, Throwable e) {
                errors.add(e.getMessage());
            }

            @Override
            public void handleTransportError(StompSession s, Throwable e) {
                errors.add("transport: " + e.getMessage());
            }
        });
        strangerSession.subscribe("/topic/workspace." + workspaceId, collector(leaked));
        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull();
        owner.post("/api/v1/workspaces/" + workspaceId + "/projects", Map.of("name", "Second"));
        assertThat(poll(received, "project.created")).isNotNull();
        assertThat(leaked.poll(1, TimeUnit.SECONDS)).isNull();
        session.disconnect();
    }

    private StompSession connect(TestApi api, StompSessionHandlerAdapter handler) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Cookie", api.cookieHeader());
        headers.add("Origin", "http://localhost:5180");
        return client.connectAsync("ws://localhost:" + port + "/ws", headers, handler).get(5, TimeUnit.SECONDS);
    }

    private static StompFrameHandler collector(BlockingQueue<Map<?, ?>> queue) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((Map<?, ?>) payload);
            }
        };
    }

    private static Map<?, ?> poll(BlockingQueue<Map<?, ?>> queue, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Map<?, ?> e = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (e != null && type.equals(e.get("type"))) return e;
        }
        throw new AssertionError("No " + type + " event received");
    }
}

package dev.aiw.events;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the Redis event stream and pushes each event to the STOMP topic of its workspace.
 * Every api instance reads the whole stream and serves its own WebSocket clients.
 * Clients that miss events (reconnect, restart) resynchronize through the REST endpoint by seq.
 */
@Component
class EventBroadcaster implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(EventBroadcaster.class);

    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messaging;
    private final JsonMapper json;
    private volatile boolean running;

    EventBroadcaster(RedisConnectionFactory connectionFactory, StringRedisTemplate redis,
                     SimpMessagingTemplate messaging, JsonMapper json) {
        this.redis = redis;
        this.messaging = messaging;
        this.json = json;
        this.container = StreamMessageListenerContainer.create(connectionFactory,
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .errorHandler(t -> log.warn("Event stream read failed: {}", t.getMessage()))
                        .build());
    }

    @Override
    public void start() {
        // Start right after the newest entry, then continue from the last consumed id. ReadOffset.latest()
        // would re-read "$" on every poll and silently drop entries added between two polls.
        var newest = redis.opsForStream().reverseRange(OutboxRelay.STREAM, Range.unbounded(), Limit.limit().count(1));
        String startId = newest == null || newest.isEmpty() ? "0-0" : newest.getFirst().getId().getValue();
        container.receive(StreamOffset.create(OutboxRelay.STREAM, ReadOffset.from(startId)), this::onRecord);
        container.start();
        running = true;
    }

    private void onRecord(MapRecord<String, String, String> record) {
        String raw = record.getValue().get("event");
        if (raw == null) return;
        EventRecord event = json.readValue(raw, EventRecord.class);
        messaging.convertAndSend("/topic/workspace." + event.workspaceId(), event);
    }

    @Override
    public void stop() {
        container.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

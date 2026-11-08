package dev.aiw.events;

import java.util.List;
import java.util.Map;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Moves committed events from the outbox (events.published_at IS NULL) to the Redis stream.
 * SKIP LOCKED lets several api instances relay concurrently without duplicates.
 */
@Component
class OutboxRelay {
    static final String STREAM = "aiw:events";
    private static final long MAX_STREAM_LENGTH = 100_000;

    private final JdbcClient jdbc;
    private final EventQueries queries;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    OutboxRelay(JdbcClient jdbc, EventQueries queries, StringRedisTemplate redis, JsonMapper json) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.redis = redis;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${aiw.events.relay-interval-ms:200}")
    @Transactional
    public void relay() {
        List<EventRecord> batch = jdbc.sql("""
                SELECT * FROM events WHERE published_at IS NULL
                ORDER BY seq LIMIT 200 FOR UPDATE SKIP LOCKED
                """).query(queries::map).list();
        if (batch.isEmpty()) return;
        for (EventRecord e : batch) {
            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .in(STREAM)
                    .ofMap(Map.of("event", json.writeValueAsString(e)));
            redis.opsForStream().add(record);
        }
        redis.opsForStream().trim(STREAM, MAX_STREAM_LENGTH, true);
        jdbc.sql("UPDATE events SET published_at = now() WHERE seq IN (:seqs)")
                .param("seqs", batch.stream().map(EventRecord::seq).toList())
                .update();
    }
}

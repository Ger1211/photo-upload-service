package com.wedding.upload.replication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only queue of pushes that could not reach the peer.
 *
 * <p>Design notes: the file is append-only (a retry is cheaper than a rewrite)
 * and the last line for a given key wins on reload. When the queue drains the
 * file is truncated, which is the normal steady state. Stale lines that survive
 * a restart are harmless because the peer is idempotent by filename.
 */
@Component
@ConditionalOnProperty(prefix = "replication", name = "enabled", havingValue = "true")
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);
    private static final String FILENAME = "outbox.jsonl";
    private static final int BASE_BACKOFF_SECONDS = 30;
    private static final int MAX_BACKOFF_SECONDS = 300;

    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, PendingPush> pending = new LinkedHashMap<>();

    @Autowired
    public Outbox(ReplicationProperties properties) {
        this(Path.of(properties.getStatePath()));
    }

    public Outbox(Path statePath) {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            Files.createDirectories(statePath);
        } catch (IOException e) {
            throw new ReplicationException("Cannot create replication state dir: " + statePath, e);
        }
        this.file = statePath.resolve(FILENAME);
        load();
        compact();
    }

    public synchronized void enqueue(PendingPush push) {
        PendingPush fresh = push.withAttempt(0, LocalDateTime.now());
        pending.put(fresh.key(), fresh);
        append(fresh);
        log.info("Queued for replication: {} (outbox depth {})", fresh.filename(), pending.size());
    }

    public synchronized List<PendingPush> due() {
        LocalDateTime now = LocalDateTime.now();
        List<PendingPush> ready = new ArrayList<>();
        for (PendingPush push : pending.values()) {
            if (push.nextAttemptAt() == null || !push.nextAttemptAt().isAfter(now)) {
                ready.add(push);
            }
        }
        return ready;
    }

    public synchronized void remove(String key) {
        if (pending.remove(key) != null && pending.isEmpty()) {
            compact();
        }
    }

    public synchronized void markFailure(String key) {
        PendingPush current = pending.get(key);
        if (current == null) {
            return;
        }
        int attempts = current.attempts() + 1;
        PendingPush retried = current.withAttempt(attempts, nextAttemptFor(attempts));
        pending.put(key, retried);
        append(retried);
        log.warn("Replication retry #{} scheduled for {} at {}",
                attempts, retried.filename(), retried.nextAttemptAt());
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized List<PendingPush> snapshot() {
        return List.copyOf(pending.values());
    }

    static LocalDateTime nextAttemptFor(int attempts) {
        long seconds = Math.min(BASE_BACKOFF_SECONDS * (1L << Math.min(attempts, 4)), MAX_BACKOFF_SECONDS);
        return LocalDateTime.now().plusSeconds(seconds);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    PendingPush push = mapper.readValue(line, PendingPush.class);
                    pending.put(push.key(), push);
                } catch (IOException e) {
                    log.warn("Skipping corrupt outbox line in {}", file);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read outbox: {}", file, e);
        }
    }

    private void append(PendingPush push) {
        try {
            String line = mapper.writeValueAsString(push) + System.lineSeparator();
            Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append to outbox {}", file, e);
        }
    }

    private void compact() {
        try {
            if (pending.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (PendingPush push : pending.values()) {
                sb.append(mapper.writeValueAsString(push)).append(System.lineSeparator());
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to compact outbox {}", file, e);
        }
    }
}

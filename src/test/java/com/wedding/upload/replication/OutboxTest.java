package com.wedding.upload.replication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutboxTest {

    @TempDir
    Path tempDir;

    private static PendingPush push(String filename) {
        return new PendingPush("marina-fernando", "2026-12-05", filename,
                "IMG_0001.jpg", "guest", LocalDateTime.of(2026, 12, 5, 20, 30),
                1024L, "abc123", 0, null);
    }

    @Test
    void shouldQueueAndSurviveRestart() {
        Outbox outbox = new Outbox(tempDir);
        outbox.enqueue(push("a.jpg"));
        assertEquals(1, outbox.size());

        Outbox reloaded = new Outbox(tempDir);
        assertEquals(1, reloaded.size());
        assertEquals("a.jpg", reloaded.snapshot().get(0).filename());
    }

    @Test
    void shouldNotDuplicateTheSamePhoto() {
        Outbox outbox = new Outbox(tempDir);
        outbox.enqueue(push("a.jpg"));
        outbox.enqueue(push("a.jpg"));
        assertEquals(1, outbox.size());
    }

    @Test
    void shouldRemoveAndTruncateStateFile() {
        Outbox outbox = new Outbox(tempDir);
        outbox.enqueue(push("a.jpg"));
        outbox.remove(push("a.jpg").key());

        assertEquals(0, outbox.size());
        assertFalse(Files.exists(tempDir.resolve("outbox.jsonl")));
    }

    @Test
    void shouldBeDueImmediatelyThenBackOffAfterFailure() {
        Outbox outbox = new Outbox(tempDir);
        outbox.enqueue(push("a.jpg"));

        List<PendingPush> first = outbox.due();
        assertEquals(1, first.size());

        outbox.markFailure(push("a.jpg").key());
        assertTrue(outbox.due().isEmpty(), "A failed push must wait before retrying");
        assertEquals(1, outbox.snapshot().get(0).attempts());
    }

    @Test
    void shouldCapBackoffAtFiveMinutes() {
        LocalDateTime far = Outbox.nextAttemptFor(10);
        assertTrue(far.isAfter(LocalDateTime.now().plusMinutes(4)));
        assertTrue(far.isBefore(LocalDateTime.now().plusMinutes(6)));
    }
}

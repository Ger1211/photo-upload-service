package com.wedding.upload.replication;

import java.time.LocalDateTime;

/**
 * A photo waiting to be pushed to the peer. Persisted in the outbox so a queued
 * push survives a restart of the service.
 */
public record PendingPush(
        String event,
        String dateFolder,
        String filename,
        String originalName,
        String guestName,
        LocalDateTime uploadedAt,
        long sizeBytes,
        String sha256,
        int attempts,
        LocalDateTime nextAttemptAt
) {
    public String key() {
        return (event == null ? "" : event) + "|" + dateFolder + "|" + filename;
    }

    public PendingPush withAttempt(int attempts, LocalDateTime nextAttemptAt) {
        return new PendingPush(event, dateFolder, filename, originalName, guestName,
                uploadedAt, sizeBytes, sha256, attempts, nextAttemptAt);
    }
}

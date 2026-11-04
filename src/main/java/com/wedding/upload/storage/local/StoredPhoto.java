package com.wedding.upload.storage.local;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Result of persisting one photo locally. Carries everything the replication
 * layer needs to push an identical copy to the peer without re-reading the
 * upload stream or recomputing the checksum.
 */
public record StoredPhoto(
        String filename,
        String originalName,
        String guestName,
        String event,
        LocalDate dateFolder,
        LocalDateTime uploadedAt,
        long sizeBytes,
        String sha256,
        Path path
) {
}

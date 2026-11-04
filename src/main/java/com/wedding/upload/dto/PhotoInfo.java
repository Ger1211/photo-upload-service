package com.wedding.upload.dto;

import java.time.LocalDateTime;

public record PhotoInfo(
        String filename,
        String originalName,
        String guestName,
        LocalDateTime uploadedAt,
        long sizeBytes,
        String sha256
) {
    /**
     * Legacy constructor kept for call sites that do not have a checksum yet.
     * Entries written before replication existed have no {@code sha256} field in
     * {@code manifest.jsonl}; Jackson deserializes those as {@code null}.
     */
    public PhotoInfo(String filename, String originalName, String guestName,
                     LocalDateTime uploadedAt, long sizeBytes) {
        this(filename, originalName, guestName, uploadedAt, sizeBytes, null);
    }
}

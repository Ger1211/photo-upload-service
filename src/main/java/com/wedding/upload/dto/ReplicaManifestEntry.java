package com.wedding.upload.dto;

/**
 * One photo as seen by the peer during replication. Timestamps travel as ISO
 * strings so both ends agree on the format without sharing Jackson config.
 */
public record ReplicaManifestEntry(
        String event,
        String dateFolder,
        String filename,
        String originalName,
        String guestName,
        String uploadedAt,
        long sizeBytes,
        String sha256
) {
    /** Identity of a photo across both machines. */
    public String key() {
        return (event == null ? "" : event) + "|" + dateFolder + "|" + filename;
    }
}

package com.wedding.upload.dto;

/**
 * Health of the replication link. {@code nube-off} refuses to destroy the cloud
 * VM unless every divergence counter is zero and the peer is reachable.
 */
public record ReplicationStatus(
        String role,
        String peerUrl,
        boolean peerReachable,
        int localCount,
        int peerCount,
        int missingLocally,
        int missingOnPeer,
        int checksumMismatches,
        int outboxDepth,
        String lastReconcileAt,
        String lastError
) {
    public boolean inSync() {
        return peerReachable
                && missingLocally == 0
                && missingOnPeer == 0
                && checksumMismatches == 0
                && outboxDepth == 0;
    }
}

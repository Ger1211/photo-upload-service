package com.wedding.upload.replication;

import com.wedding.upload.dto.ReplicationStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the most recent reconciliation report. {@code /api/internal/status}
 * exposes it so the shutdown script can prove both copies are identical before
 * the cloud VM is destroyed.
 */
@Component
@ConditionalOnProperty(prefix = "replication", name = "enabled", havingValue = "true")
public class ReplicationReport {

    private final AtomicReference<ReplicationStatus> latest = new AtomicReference<>();

    public void update(ReplicationStatus status) {
        latest.set(status);
    }

    public ReplicationStatus get() {
        return latest.get();
    }
}

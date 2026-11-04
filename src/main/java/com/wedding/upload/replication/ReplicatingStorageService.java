package com.wedding.upload.replication;

import com.wedding.upload.storage.StorageService;
import com.wedding.upload.storage.local.LocalDiskStorageService;
import com.wedding.upload.storage.local.StoredPhoto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Decorates local storage with a best-effort push to the peer.
 *
 * <p>Invariant: the guest only gets a success response once the photo is on local
 * disk. A peer that is down, slow or unreachable can never fail an upload — the
 * push is either done inline (fast path) or queued in the outbox and retried by
 * {@link ReconciliationJob}.
 */
@Primary
@Service
@ConditionalOnProperty(prefix = "replication", name = "enabled", havingValue = "true")
public class ReplicatingStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(ReplicatingStorageService.class);

    private final LocalDiskStorageService local;
    private final ReplicationClient client;
    private final Outbox outbox;

    public ReplicatingStorageService(LocalDiskStorageService local, ReplicationClient client, Outbox outbox) {
        this.local = local;
        this.client = client;
        this.outbox = outbox;
    }

    @Override
    public String upload(MultipartFile file, String targetFilename, String guestName, String event) {
        StoredPhoto stored = local.store(file, targetFilename, guestName, event,
                LocalDate.now(), LocalDateTime.now());
        replicate(stored);
        return stored.path().toString();
    }

    private void replicate(StoredPhoto stored) {
        PendingPush push = new PendingPush(
                stored.event(),
                stored.dateFolder().toString(),
                stored.filename(),
                stored.originalName(),
                stored.guestName(),
                stored.uploadedAt(),
                stored.sizeBytes(),
                stored.sha256(),
                0,
                null);

        try {
            if (client.push(push, stored.path())) {
                return;
            }
            log.warn("Peer rejected push for {}, queueing it", stored.filename());
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            // Replication is a side channel: nothing that happens here may fail the
            // guest upload. Anything not queued now is recovered by the reconciler.
            log.warn("Peer push failed for {}: {}", stored.filename(), e.toString());
        }

        try {
            outbox.enqueue(push);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            log.error("Cannot queue {} for replication: {}", stored.filename(), e.toString());
        }
    }
}

package com.wedding.upload.replication;

import com.wedding.upload.dto.ReplicaManifestEntry;
import com.wedding.upload.dto.ReplicationStatus;
import com.wedding.upload.storage.local.LocalDiskStorageService;
import com.wedding.upload.storage.local.PhotoManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps both machines identical.
 *
 * <p>The inline push in {@link ReplicatingStorageService} is fast but lossy: a PC
 * that is off for a while, a crash before the outbox flush, or uploads that went
 * to the fallback host all leave gaps. This job closes them in both directions
 * every cycle, so "the two copies are the same" is an invariant rather than a
 * hope.
 */
@Component
@ConditionalOnProperty(prefix = "replication", name = "enabled", havingValue = "true")
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final LocalDiskStorageService local;
    private final ReplicationClient client;
    private final Outbox outbox;
    private final ReplicationReport report;
    private final ReplicationProperties properties;

    public ReconciliationJob(LocalDiskStorageService local, ReplicationClient client,
                             Outbox outbox, ReplicationReport report,
                             ReplicationProperties properties) {
        this.local = local;
        this.client = client;
        this.outbox = outbox;
        this.report = report;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${replication.reconcile-interval-ms:60000}",
            initialDelayString = "${replication.reconcile-initial-delay-ms:15000}")
    public synchronized void reconcile() {
        drainOutbox();

        Map<String, ReplicaManifestEntry> localIndex = localSnapshot();

        List<ReplicaManifestEntry> peerEntries;
        try {
            peerEntries = client.fetchManifest();
        } catch (RuntimeException e) {
            log.warn("Peer unreachable, keeping {} photos locally: {}", localIndex.size(), e.getMessage());
            report.update(new ReplicationStatus(properties.getRole(), properties.getPeerUrl(), false,
                    localIndex.size(), 0, 0, localIndex.size(), 0, outbox.size(),
                    LocalDateTime.now().toString(), e.getMessage()));
            return;
        }

        Map<String, ReplicaManifestEntry> peerIndex = index(peerEntries);

        int failedPulls = 0;
        for (ReplicaManifestEntry entry : peerIndex.values()) {
            if (!localIndex.containsKey(entry.key()) && !pull(entry)) {
                failedPulls++;
            }
        }

        int missingOnPeer = 0;
        for (ReplicaManifestEntry entry : localIndex.values()) {
            if (!peerIndex.containsKey(entry.key())) {
                outbox.enqueue(toPending(entry));
                missingOnPeer++;
            }
        }

        int mismatches = countChecksumMismatches(localIndex, peerIndex);

        drainOutbox();

        report.update(new ReplicationStatus(properties.getRole(), properties.getPeerUrl(), true,
                localIndex.size(), peerIndex.size(), failedPulls, missingOnPeer, mismatches,
                outbox.size(), LocalDateTime.now().toString(), null));

        if (failedPulls > 0 || missingOnPeer > 0 || mismatches > 0) {
            log.warn("Replication divergence - missingLocally: {}, missingOnPeer: {}, checksumMismatches: {}",
                    failedPulls, missingOnPeer, mismatches);
        }
    }

    private void drainOutbox() {
        for (PendingPush push : outbox.due()) {
            Path source = local.photoPath(push.event(), push.dateFolder(), push.filename());
            if (!Files.exists(source)) {
                log.warn("Dropping outbox entry, source file is gone: {}", source);
                outbox.remove(push.key());
                continue;
            }
            if (client.push(push, source)) {
                outbox.remove(push.key());
                log.info("Replicated from outbox: {}", push.filename());
            } else {
                outbox.markFailure(push.key());
            }
        }
    }

    private boolean pull(ReplicaManifestEntry entry) {
        Path temp = null;
        try {
            temp = Files.createTempFile("replica-", ".part");
            client.download(entry, temp);
            try (InputStream in = Files.newInputStream(temp)) {
                local.store(in, Files.size(temp), entry.originalName(), entry.filename(),
                        entry.guestName(), entry.event(),
                        LocalDate.parse(entry.dateFolder()),
                        LocalDateTime.parse(entry.uploadedAt()),
                        entry.sha256());
            }
            log.info("Pulled missing photo from peer: {}/{}", entry.dateFolder(), entry.filename());
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to pull {} from peer: {}", entry.filename(), e.getMessage());
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Temp file cleanup is best effort.
                }
            }
        }
    }

    private Map<String, ReplicaManifestEntry> localSnapshot() {
        Map<String, ReplicaManifestEntry> index = new LinkedHashMap<>();
        collect(index, null);
        for (String event : local.listEvents()) {
            collect(index, event);
        }
        return index;
    }

    private void collect(Map<String, ReplicaManifestEntry> index, String event) {
        Path base = local.resolveEventPath(event);
        PhotoManifest.readByDate(base).forEach((dateFolder, photos) -> photos.forEach(photo -> {
            ReplicaManifestEntry entry = new ReplicaManifestEntry(
                    event,
                    dateFolder,
                    photo.filename(),
                    photo.originalName(),
                    photo.guestName(),
                    photo.uploadedAt() == null ? LocalDateTime.now().toString() : photo.uploadedAt().toString(),
                    photo.sizeBytes(),
                    photo.sha256());
            index.put(entry.key(), entry);
        }));
    }

    private Map<String, ReplicaManifestEntry> index(List<ReplicaManifestEntry> entries) {
        Map<String, ReplicaManifestEntry> index = new LinkedHashMap<>();
        for (ReplicaManifestEntry entry : entries) {
            index.put(entry.key(), entry);
        }
        return index;
    }

    private int countChecksumMismatches(Map<String, ReplicaManifestEntry> localIndex,
                                        Map<String, ReplicaManifestEntry> peerIndex) {
        int mismatches = 0;
        for (ReplicaManifestEntry mine : localIndex.values()) {
            ReplicaManifestEntry theirs = peerIndex.get(mine.key());
            if (theirs == null) {
                continue;
            }
            // Entries written before replication existed have no hash; presence is
            // all we can compare for them.
            if (mine.sha256() != null && theirs.sha256() != null
                    && !mine.sha256().equalsIgnoreCase(theirs.sha256())) {
                mismatches++;
                log.error("Checksum mismatch for {}: local {} vs peer {}",
                        mine.filename(), mine.sha256(), theirs.sha256());
            }
        }
        return mismatches;
    }

    private PendingPush toPending(ReplicaManifestEntry entry) {
        return new PendingPush(entry.event(), entry.dateFolder(), entry.filename(),
                entry.originalName(), entry.guestName(),
                LocalDateTime.parse(entry.uploadedAt()), entry.sizeBytes(), entry.sha256(), 0, null);
    }
}

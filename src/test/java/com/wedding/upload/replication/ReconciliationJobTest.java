package com.wedding.upload.replication;

import com.wedding.upload.dto.ReplicaManifestEntry;
import com.wedding.upload.dto.ReplicationStatus;
import com.wedding.upload.storage.local.LocalDiskStorageService;
import com.wedding.upload.storage.local.PhotoManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReconciliationJobTest {

    @TempDir
    Path tempDir;

    private LocalDiskStorageService local;
    private ReplicationClient client;
    private Outbox outbox;
    private ReplicationReport report;
    private ReconciliationJob job;

    @BeforeEach
    void setUp() {
        local = new LocalDiskStorageService(tempDir.resolve("uploads").toString());
        client = mock(ReplicationClient.class);
        outbox = new Outbox(tempDir.resolve("state"));
        report = new ReplicationReport();

        ReplicationProperties properties = new ReplicationProperties();
        properties.setEnabled(true);
        properties.setPeerUrl("http://peer:8080");

        job = new ReconciliationJob(local, client, outbox, report, properties);
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("files", "IMG_0001.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3});
    }

    private String shaOf(byte[] content) throws Exception {
        Path tmp = Files.createTempFile(tempDir, "sha", ".bin");
        Files.write(tmp, content);
        return FileChecksum.sha256(tmp);
    }

    @Test
    void shouldPullPhotosMissingLocally() throws Exception {
        byte[] content = "foto-remota".getBytes();
        ReplicaManifestEntry entry = new ReplicaManifestEntry(
                "marina-fernando", "2026-12-05", "remote.jpg", "IMG_9.jpg", "guest",
                "2026-12-05T20:30:00", content.length, shaOf(content));

        when(client.fetchManifest()).thenReturn(List.of(entry));
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), content);
            return null;
        }).when(client).download(eq(entry), any());

        job.reconcile();

        assertTrue(local.exists("marina-fernando", "2026-12-05", "remote.jpg"));
        assertEquals(1, PhotoManifest.readAll(tempDir.resolve("uploads").resolve("marina-fernando")).size());
        assertEquals(0, report.get().missingLocally());
    }

    @Test
    void shouldQueuePhotosMissingOnThePeer() {
        local.upload(photo(), "local.jpg", "Maria", "marina-fernando");
        when(client.fetchManifest()).thenReturn(List.of());

        job.reconcile();

        assertEquals(1, outbox.size(), "A photo absent on the peer must be queued for push");
        assertEquals(1, report.get().missingOnPeer());
    }

    @Test
    void shouldCountChecksumMismatchesWithoutOverwriting() {
        local.upload(photo(), "local.jpg", "Maria", null);

        ReplicaManifestEntry peerEntry = new ReplicaManifestEntry(
                null, LocalDate.now().toString(), "local.jpg", "IMG_0001.jpg", "Maria",
                LocalDateTime.now().toString(), 6, "deadbeef");
        when(client.fetchManifest()).thenReturn(List.of(peerEntry));

        job.reconcile();

        ReplicationStatus status = report.get();
        assertEquals(1, status.checksumMismatches());
        assertEquals(1, status.localCount());
        assertEquals(0, outbox.size(), "A checksum mismatch must not trigger a blind overwrite");
    }

    @Test
    void shouldReportPeerUnreachable() {
        when(client.fetchManifest()).thenThrow(new ReplicationException("peer down"));

        job.reconcile();

        ReplicationStatus status = report.get();
        assertFalse(status.peerReachable());
        assertNotNull(status.lastError());
        assertFalse(status.inSync());
    }
}

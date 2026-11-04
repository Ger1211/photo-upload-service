package com.wedding.upload.replication;

import com.wedding.upload.storage.local.LocalDiskStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReplicatingStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalDiskStorageService local;
    private ReplicationClient client;
    private Outbox outbox;
    private ReplicatingStorageService service;

    @BeforeEach
    void setUp() {
        local = new LocalDiskStorageService(tempDir.resolve("uploads").toString());
        client = mock(ReplicationClient.class);
        outbox = new Outbox(tempDir.resolve("state"));
        service = new ReplicatingStorageService(local, client, outbox);
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("files", "IMG_0001.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3});
    }

    @Test
    void shouldPushToPeerAfterSavingLocally() {
        when(client.push(any(), any())).thenReturn(true);

        String path = service.upload(photo(), "stored.jpg", "Maria", "marina-fernando");

        assertTrue(Files.exists(Path.of(path)));
        verify(client).push(any(), any());
        assertEquals(0, outbox.size(), "A successful push must not leave anything queued");
    }

    @Test
    void shouldQueueWhenPeerRejectsThePush() {
        when(client.push(any(), any())).thenReturn(false);

        String path = service.upload(photo(), "stored.jpg", "Maria", "marina-fernando");

        assertTrue(Files.exists(Path.of(path)), "Local save must succeed even if the peer fails");
        assertEquals(1, outbox.size());
    }

    @Test
    void shouldQueueWhenPeerThrows() {
        when(client.push(any(), any())).thenThrow(new ReplicationException("peer down"));

        String path = service.upload(photo(), "stored.jpg", null, null);

        assertNotNull(path);
        assertTrue(Files.exists(Path.of(path)));
        assertEquals(1, outbox.size());
    }
}

package com.wedding.upload.replication;

import com.wedding.upload.storage.local.LocalDiskStorageService;
import com.wedding.upload.storage.local.PhotoManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InternalReplicationControllerTest {

    private static final String TOKEN = "secret-token";

    @TempDir
    Path tempDir;

    private LocalDiskStorageService local;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        local = new LocalDiskStorageService(tempDir.resolve("uploads").toString());

        ReplicationProperties properties = new ReplicationProperties();
        properties.setEnabled(true);
        properties.setRole("replica");
        properties.setPeerUrl("http://peer:8080");
        properties.setToken(TOKEN);

        Outbox outbox = new Outbox(tempDir.resolve("state"));
        InternalReplicationController controller =
                new InternalReplicationController(local, properties, new ReplicationReport(), outbox);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("file", "IMG_0001.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3});
    }

    @Test
    void shouldRejectRequestsWithoutToken() throws Exception {
        mockMvc.perform(multipart("/api/internal/replicate")
                        .file(photo())
                        .param("filename", "stored.jpg")
                        .param("dateFolder", "2026-12-05")
                        .param("event", "marina-fernando")
                        .param("uploadedAt", "2026-12-05T20:30:00"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectRequestsWithWrongToken() throws Exception {
        mockMvc.perform(get("/api/internal/manifest").header(ReplicationClient.TOKEN_HEADER, "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldStoreThenSkipTheSamePhoto() throws Exception {
        mockMvc.perform(multipart("/api/internal/replicate")
                        .file(photo())
                        .header(ReplicationClient.TOKEN_HEADER, TOKEN)
                        .param("filename", "stored.jpg")
                        .param("dateFolder", "2026-12-05")
                        .param("event", "marina-fernando")
                        .param("originalName", "IMG_0001.jpg")
                        .param("guestName", "Maria")
                        .param("uploadedAt", "2026-12-05T20:30:00")
                        .param("sha256", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stored"));

        mockMvc.perform(multipart("/api/internal/replicate")
                        .file(photo())
                        .header(ReplicationClient.TOKEN_HEADER, TOKEN)
                        .param("filename", "stored.jpg")
                        .param("dateFolder", "2026-12-05")
                        .param("event", "marina-fernando")
                        .param("originalName", "IMG_0001.jpg")
                        .param("guestName", "Maria")
                        .param("uploadedAt", "2026-12-05T20:30:00")
                        .param("sha256", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"));

        assertEquals(1, PhotoManifest.readAll(tempDir.resolve("uploads").resolve("marina-fernando")).size(),
                "A retried push must not duplicate the manifest entry");
    }

    @Test
    void shouldExposeChecksumsInManifest() throws Exception {
        local.upload(photo(), "local.jpg", "Maria", "marina-fernando");

        mockMvc.perform(get("/api/internal/manifest").header(ReplicationClient.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filename").value("local.jpg"))
                .andExpect(jsonPath("$[0].event").value("marina-fernando"))
                .andExpect(jsonPath("$[0].sha256").isNotEmpty());
    }

    @Test
    void shouldServeStoredPhoto() throws Exception {
        local.upload(photo(), "local.jpg", "Maria", null);

        mockMvc.perform(get("/api/internal/photo")
                        .header(ReplicationClient.TOKEN_HEADER, TOKEN)
                        .param("event", "")
                        .param("dateFolder", java.time.LocalDate.now().toString())
                        .param("filename", "local.jpg"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectPathTraversal() throws Exception {
        mockMvc.perform(get("/api/internal/photo")
                        .header(ReplicationClient.TOKEN_HEADER, TOKEN)
                        .param("event", "")
                        .param("dateFolder", "2026-12-05")
                        .param("filename", "../secret.jpg"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReportStatusBeforeFirstReconcile() throws Exception {
        mockMvc.perform(get("/api/internal/status").header(ReplicationClient.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("replica"))
                .andExpect(jsonPath("$.peerReachable").value(false))
                .andExpect(jsonPath("$.lastError").value("reconciliation not run yet"));
    }

    @Test
    void shouldPersistFileOnDisk() throws Exception {
        mockMvc.perform(multipart("/api/internal/replicate")
                        .file(photo())
                        .header(ReplicationClient.TOKEN_HEADER, TOKEN)
                        .param("filename", "stored.jpg")
                        .param("dateFolder", "2026-12-05")
                        .param("event", "")
                        .param("originalName", "IMG_0001.jpg")
                        .param("guestName", "")
                        .param("uploadedAt", "2026-12-05T20:30:00")
                        .param("sha256", ""))
                .andExpect(status().isOk());

        Path stored = tempDir.resolve("uploads").resolve("2026-12-05").resolve("stored.jpg");
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(stored));
    }
}

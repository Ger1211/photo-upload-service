package com.wedding.upload.controller;

import com.wedding.upload.storage.local.LocalDiskStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GalleryControllerTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private LocalDiskStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new LocalDiskStorageService(tempDir.toString());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GalleryController(storageService))
                .build();
    }

    @Test
    void shouldListEventPhotosWithEventScopedUrls() throws Exception {
        String today = LocalDate.now().toString();
        byte[] bytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("files", "photo.jpg", "image/jpeg", bytes);
        storageService.upload(file, "evt_photo.jpg", "Maria", "marina-fernando");

        Path thumbDir = tempDir.resolve("marina-fernando").resolve("thumbnails").resolve(today);
        Files.createDirectories(thumbDir);
        Files.write(thumbDir.resolve("evt_photo.jpg"), new byte[]{9, 9, 9});

        mockMvc.perform(get("/api/photos").param("event", "marina-fernando"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.photos[0].filename").value("evt_photo.jpg"))
                .andExpect(jsonPath("$.photos[0].url")
                        .value("/api/photos/marina-fernando/" + today + "/evt_photo.jpg"))
                .andExpect(jsonPath("$.photos[0].thumbnail")
                        .value("/api/thumbnails/marina-fernando/" + today + "/evt_photo.jpg"));
    }

    @Test
    void shouldKeepDefaultGallerySeparateFromEvents() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        storageService.upload(file, "evt_photo.jpg", null, "marina-fernando");
        storageService.upload(file, "def_photo.jpg", null, null);

        mockMvc.perform(get("/api/photos").param("event", "marina-fernando"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.photos[0].filename").value("evt_photo.jpg"));

        mockMvc.perform(get("/api/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.photos[0].filename").value("def_photo.jpg"));
    }

    @Test
    void shouldServeEventPhotoOnlyFromEventPath() throws Exception {
        String today = LocalDate.now().toString();
        byte[] bytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("files", "photo.jpg", "image/jpeg", bytes);
        storageService.upload(file, "evt_photo.jpg", null, "marina-fernando");

        mockMvc.perform(get("/api/photos/marina-fernando/" + today + "/evt_photo.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));

        mockMvc.perform(get("/api/photos/" + today + "/evt_photo.jpg"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/photos/marina-fernando/" + today + "/other.jpg"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldFallBackToPhotoUrlWhenThumbnailCannotBeGenerated() throws Exception {
        String today = LocalDate.now().toString();
        Path dateDir = tempDir.resolve(today);
        Files.createDirectories(dateDir);
        Files.write(dateDir.resolve("bad.jpg"), new byte[]{0x00, 0x01, 0x02, 0x03, 0x04});
        String manifestLine = "{\"filename\":\"bad.jpg\",\"originalName\":\"bad.jpg\","
                + "\"guestName\":null,\"uploadedAt\":\"" + today + "T10:00:00\",\"sizeBytes\":5}\n";
        Files.write(dateDir.resolve("manifest.jsonl"), manifestLine.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.photos[0].filename").value("bad.jpg"))
                .andExpect(jsonPath("$.photos[0].thumbnail")
                        .value("/api/photos/" + today + "/bad.jpg"));
    }

    @Test
    void shouldRegenerateMissingThumbnailOnServe() throws Exception {
        String today = LocalDate.now().toString();
        Path dateDir = tempDir.resolve(today);
        Files.createDirectories(dateDir);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("sample.webp")) {
            assertTrue(in != null, "Fixture sample.webp should exist on the classpath");
            Files.copy(in, dateDir.resolve("photo.webp"));
        }

        Path thumbFile = tempDir.resolve("thumbnails").resolve(today).resolve("photo.webp");
        assertTrue(!Files.exists(thumbFile), "Thumbnail should start missing");

        mockMvc.perform(get("/api/thumbnails/" + today + "/photo.webp"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));

        assertTrue(Files.exists(thumbFile), "Missing thumbnail should be regenerated and persisted");
        assertTrue(Files.size(thumbFile) > 0);
    }
}
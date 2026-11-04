package com.wedding.upload.storage.local;

import com.wedding.upload.dto.PhotoInfo;
import com.wedding.upload.exception.StorageException;
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

class LocalDiskStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalDiskStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new LocalDiskStorageService(tempDir.toString());
    }

    @Test
    void shouldSaveFileToDisk() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3});

        String path = storageService.upload(file, "20260721_test_photo.jpg", null, null);

        assertNotNull(path);
        assertTrue(Files.exists(Path.of(path)));
        assertTrue(path.contains("20260721_test_photo.jpg"));
    }

    @Test
    void shouldCreateDateFolderAndManifest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        storageService.upload(file, "test.jpg", "Maria", null);

        String today = LocalDate.now().toString();
        Path dateDir = tempDir.resolve(today);
        assertTrue(Files.exists(dateDir));
        assertTrue(Files.exists(dateDir.resolve("manifest.jsonl")));
    }

    @Test
    void shouldUploadMultipleAndReadBackFromManifest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "a.jpg", "image/jpeg", new byte[]{1, 2, 3});

        storageService.upload(file, "photo1.jpg", null, null);
        Thread.sleep(5);
        storageService.upload(file, "photo2.jpg", null, null);

        List<PhotoInfo> photos = PhotoManifest.readAll(tempDir);
        assertEquals(2, photos.size(), "Expected exactly 2 photos from manifest");
        assertNotNull(photos.get(0).uploadedAt());
        assertNotNull(photos.get(1).uploadedAt());
    }

@Test
    void shouldTrackOriginalFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "original_name.jpg", "image/jpeg", new byte[]{1, 2, 3});

        storageService.upload(file, "renamed.jpg", null, null);

        List<PhotoInfo> photos = PhotoManifest.readAll(tempDir);
        assertEquals(1, photos.size());
        assertEquals("original_name.jpg", photos.get(0).originalName());
        assertEquals("renamed.jpg", photos.get(0).filename());
    }

    @Test
    void shouldSaveEventPhotosInEventSubfolder() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        storageService.upload(file, "event_photo.jpg", "Maria", "marina-fernando");

        String today = LocalDate.now().toString();
        Path eventDir = tempDir.resolve("marina-fernando");
        assertTrue(Files.exists(eventDir.resolve(today).resolve("event_photo.jpg")));
        assertTrue(Files.exists(eventDir.resolve(today).resolve("manifest.jsonl")));
        assertFalse(Files.exists(tempDir.resolve(today).resolve("event_photo.jpg")));
    }

    @Test
    void shouldKeepEventGalleriesSeparated() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        storageService.upload(file, "a.jpg", null, "marina-fernando");
        storageService.upload(file, "b.jpg", null, "meli-diego");

        assertEquals(1, PhotoManifest.readAll(tempDir.resolve("marina-fernando")).size());
        assertEquals(1, PhotoManifest.readAll(tempDir.resolve("meli-diego")).size());
        assertEquals(0, PhotoManifest.readAll(tempDir).size());
    }

    @Test
    void shouldSanitizeEventNames() {
        assertEquals("marina-fernando", LocalDiskStorageService.sanitizeEvent("Marina & Fernando"));
        assertEquals("meli_diego", LocalDiskStorageService.sanitizeEvent("  Meli_Diego  "));
        assertNull(LocalDiskStorageService.sanitizeEvent(null));
        assertNull(LocalDiskStorageService.sanitizeEvent("   "));
    }

    @Test
    void shouldStoreUnderExplicitDateFolder() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        StoredPhoto stored = storageService.upload(file, "late.jpg", null, "marina-fernando",
                LocalDate.of(2026, 12, 5));

        assertEquals(LocalDate.of(2026, 12, 5), stored.dateFolder());
        assertTrue(Files.exists(tempDir.resolve("marina-fernando").resolve("2026-12-05").resolve("late.jpg")));
    }

    @Test
    void shouldPersistSha256InManifest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        StoredPhoto stored = storageService.upload(file, "hashed.jpg", null, null, LocalDate.now());

        List<PhotoInfo> photos = PhotoManifest.readAll(tempDir);
        assertEquals(1, photos.size());
        assertEquals(stored.sha256(), photos.get(0).sha256());
        assertNotNull(photos.get(0).sha256());
    }

    @Test
    void shouldRejectChecksumMismatch() {
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        assertThrows(StorageException.class, () -> storageService.store(file, "bad.jpg", null, null,
                LocalDate.now(), LocalDateTime.now(), "deadbeef"));
        assertFalse(Files.exists(tempDir.resolve(LocalDate.now().toString()).resolve("bad.jpg")));
    }

    @Test
    void shouldUseExplicitOriginalNameWhenProvided() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "already-renamed.jpg", "image/jpeg", new byte[]{1, 2, 3});

        storageService.store(file, "already-renamed.jpg", "German", null,
                LocalDate.now(), LocalDateTime.now(), "1000170671.jpg", null);

        List<PhotoInfo> photos = PhotoManifest.readAll(tempDir);
        assertEquals("1000170671.jpg", photos.get(0).originalName());
    }

    @Test
    void shouldFallBackToMultipartNameWhenOriginalNameIsBlank() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "from-phone.jpg", "image/jpeg", new byte[]{1, 2, 3});

        storageService.store(file, "renamed.jpg", null, null,
                LocalDate.now(), LocalDateTime.now(), "  ", null);

        List<PhotoInfo> photos = PhotoManifest.readAll(tempDir);
        assertEquals("from-phone.jpg", photos.get(0).originalName());
    }
}

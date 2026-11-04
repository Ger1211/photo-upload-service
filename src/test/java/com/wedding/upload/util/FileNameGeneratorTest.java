package com.wedding.upload.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileNameGeneratorTest {

    private final FileNameGenerator generator = new FileNameGenerator();

    @Test
    void shouldGenerateFilenameWithGuestName() {
        String result = generator.generate("IMG0001.jpg", "Natalia");

        assertNotNull(result);
        assertTrue(result.matches("\\d{8}_\\d{6}_[a-f0-9]{7}_Natalia\\.jpg"));
    }

    @Test
    void shouldGenerateFilenameWithoutGuestName() {
        String result = generator.generate("IMG0001.jpg", null);

        assertNotNull(result);
        assertTrue(result.endsWith(".jpg"));
        assertTrue(result.matches("\\d{8}_\\d{6}_[a-f0-9]{7}\\.jpg"));
    }

    @Test
    void shouldSanitizeGuestNameSpaces() {
        String result = generator.generate("photo.jpg", "Maria Jose");

        assertNotNull(result);
        assertTrue(result.contains("_Maria_Jose."));
    }

    @Test
    void shouldGenerateUniqueNames() {
        String name1 = generator.generate("photo.jpg", "Ana");
        String name2 = generator.generate("photo.jpg", "Ana");

        assertNotEquals(name1, name2);
    }

    @Test
    void shouldPreserveExtension() {
        String result = generator.generate("photo.HEIC", "Juan");

        assertNotNull(result);
        assertTrue(result.endsWith(".HEIC"));
    }

    @Test
    void shouldHandleNullFilename() {
        String result = generator.generate(null, "Test");

        assertNotNull(result);
        assertTrue(result.endsWith(".jpg"));
    }

    @Test
    void shouldUseDetectedExtensionOverOriginalFilename() {
        String result = generator.generate("photo.jpg", "Ana", "webp");

        assertNotNull(result);
        assertTrue(result.endsWith(".webp"));
    }

    @Test
    void shouldCanonicalizeJpegDetectedExtension() {
        String result = generator.generate("photo.jpeg", null, "jpeg");

        assertNotNull(result);
        assertTrue(result.endsWith(".jpg"));
    }

    @Test
    void shouldFallBackToOriginalExtensionWhenDetectionMissing() {
        String result = generator.generate("photo.HEIC", "Juan", null);

        assertNotNull(result);
        assertTrue(result.endsWith(".HEIC"));
    }
}

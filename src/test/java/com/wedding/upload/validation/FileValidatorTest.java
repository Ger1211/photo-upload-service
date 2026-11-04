package com.wedding.upload.validation;

import com.wedding.upload.config.UploadProperties;
import com.wedding.upload.exception.FileValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileValidatorTest {

    @Mock
    private UploadProperties uploadProperties;

    private FileValidator fileValidator;

    @BeforeEach
    void setUp() {
        when(uploadProperties.getAllowedExtensions())
                .thenReturn(List.of("jpg", "jpeg", "png", "webp", "heic"));
        when(uploadProperties.getAllowedContentTypes())
                .thenReturn(List.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"));
        when(uploadProperties.getMaxFileSizeMb()).thenReturn(100);
        when(uploadProperties.getMaxFileSizeBytes()).thenReturn((long) 100 * 1024 * 1024);

        fileValidator = new FileValidator(uploadProperties);
    }

    @Test
    void shouldAcceptValidJpeg() {
        byte[] content = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", content);

        assertDoesNotThrow(() -> fileValidator.validate(file));
    }

    @Test
    void shouldAcceptValidPng() {
        byte[] content = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        };
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.png", "image/png", content);

        assertDoesNotThrow(() -> fileValidator.validate(file));
    }

    @Test
    void shouldAcceptValidWebp() {
        byte[] content = new byte[]{
                0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
                0x57, 0x45, 0x42, 0x50
        };
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.webp", "image/webp", content);

        assertDoesNotThrow(() -> fileValidator.validate(file));
    }

    @Test
    void shouldDetectWebpEvenWhenNamedJpg() {
        byte[] content = new byte[]{
                0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
                0x57, 0x45, 0x42, 0x50
        };
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", content);

        assertEquals("webp", fileValidator.detectFormat(file));
    }

    @Test
    void shouldDetectJpegFormat() {
        byte[] content = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", content);

        assertEquals("jpg", fileValidator.detectFormat(file));
    }

    @Test
    void shouldDetectPngFormat() {
        byte[] content = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        };
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.png", "image/png", content);

        assertEquals("png", fileValidator.detectFormat(file));
    }

    @Test
    void shouldRejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", new byte[0]);

        FileValidationException ex = assertThrows(FileValidationException.class,
                () -> fileValidator.validate(file));
        assertTrue(ex.getMessage().contains("No se recibieron archivos"));
    }

    @Test
    void shouldRejectNullFile() {
        FileValidationException ex = assertThrows(FileValidationException.class,
                () -> fileValidator.validate(null));
        assertTrue(ex.getMessage().contains("No se recibieron archivos"));
    }

    @Test
    void shouldRejectDisallowedExtension() {
        byte[] content = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        };
        MockMultipartFile file = new MockMultipartFile(
                "files", "document.pdf", "application/pdf", content);

        FileValidationException ex = assertThrows(FileValidationException.class,
                () -> fileValidator.validate(file));
        assertTrue(ex.getMessage().contains("Archivo no permitido"));
    }

    @Test
    void shouldRejectInvalidMagicBytes() {
        byte[] content = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
        MockMultipartFile file = new MockMultipartFile(
                "files", "fake.jpg", "image/jpeg", content);

        FileValidationException ex = assertThrows(FileValidationException.class,
                () -> fileValidator.validate(file));
        assertTrue(ex.getMessage().contains("no es una imagen valida"));
    }

    @Test
    void shouldRejectFileWithNoExtension() {
        byte[] content = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        MockMultipartFile file = new MockMultipartFile(
                "files", "photo", "image/jpeg", content);

        FileValidationException ex = assertThrows(FileValidationException.class,
                () -> fileValidator.validate(file));
        assertTrue(ex.getMessage().contains("Archivo no permitido"));
    }

    @Test
    void shouldRejectFileWithNoName() {
        byte[] content = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        MockMultipartFile file = new MockMultipartFile(
                "files", null, "image/jpeg", content);

        FileValidationException ex = assertThrows(FileValidationException.class,
                () -> fileValidator.validate(file));
        assertTrue(ex.getMessage().contains("Archivo sin nombre"));
    }
}

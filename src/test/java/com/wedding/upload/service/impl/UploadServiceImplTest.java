package com.wedding.upload.service.impl;

import com.wedding.upload.config.UploadProperties;
import com.wedding.upload.dto.UploadResponse;
import com.wedding.upload.exception.FileValidationException;
import com.wedding.upload.exception.StorageException;
import com.wedding.upload.exception.UploadLimitExceededException;
import com.wedding.upload.storage.StorageService;
import com.wedding.upload.util.FileNameGenerator;
import com.wedding.upload.validation.FileValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UploadServiceImplTest {

    @Mock
    private StorageService storageService;

    @Mock
    private FileValidator fileValidator;

    private final FileNameGenerator fileNameGenerator = new FileNameGenerator();

    @Mock
    private UploadProperties uploadProperties;

    private UploadServiceImpl uploadService;

    @BeforeEach
    void setUp() {
        when(uploadProperties.getMaxFilesPerRequest()).thenReturn(20);
        uploadService = new UploadServiceImpl(
                storageService, fileValidator, fileNameGenerator, uploadProperties);
    }

    @Test
    void shouldUploadAllFilesSuccessfully() {
        MultipartFile file1 = createMockFile("photo1.jpg");
        MultipartFile file2 = createMockFile("photo2.png");

        when(storageService.upload(any(), anyString(), any(), any())).thenReturn("file-id-1", "file-id-2");

        UploadResponse response = uploadService.upload(List.of(file1, file2), "Maria", null);

        assertTrue(response.success());
        assertEquals(2, response.uploaded());
        assertEquals(0, response.failed());
        assertTrue(response.message().contains("correctamente"));
        verify(storageService, times(2)).upload(any(), anyString(), any(), any());
    }

    @Test
    void shouldPropagateEventToStorage() {
        MultipartFile file = createMockFile("photo.jpg");

        when(storageService.upload(any(), anyString(), any(), any())).thenReturn("file-id-1");

        UploadResponse response = uploadService.upload(List.of(file), "Maria", "marina-fernando");

        assertTrue(response.success());
        verify(storageService).upload(any(), anyString(), eq("Maria"), eq("marina-fernando"));
    }

    @Test
    void shouldHandleEmptyFilesList() {
        UploadResponse response = uploadService.upload(List.of(), null, null);

        assertFalse(response.success());
        assertEquals(0, response.uploaded());
        assertTrue(response.message().contains("No se recibieron archivos"));
    }

    @Test
    void shouldHandleNullFilesList() {
        UploadResponse response = uploadService.upload(null, null, null);

        assertFalse(response.success());
        assertEquals(0, response.uploaded());
        assertTrue(response.message().contains("No se recibieron archivos"));
    }

    @Test
    void shouldRejectTooManyFiles() {
        when(uploadProperties.getMaxFilesPerRequest()).thenReturn(5);

        List<MultipartFile> files = List.of(
                createMockFile("1.jpg"),
                createMockFile("2.jpg"),
                createMockFile("3.jpg"),
                createMockFile("4.jpg"),
                createMockFile("5.jpg"),
                createMockFile("6.jpg")
        );

        assertThrows(UploadLimitExceededException.class,
                () -> uploadService.upload(files, null, null));
    }

    @Test
    void shouldTrackPartialFailures() {
        MultipartFile goodFile = createMockFile("good.jpg");
        MultipartFile badFile = createMockFile("bad.exe");

        doThrow(new FileValidationException("Archivo no permitido."))
                .when(fileValidator).validate(badFile);
        when(storageService.upload(any(), anyString(), any(), any())).thenReturn("file-id-1");

        UploadResponse response = uploadService.upload(List.of(goodFile, badFile), "Juan", null);

        assertTrue(response.success());
        assertEquals(1, response.uploaded());
        assertEquals(1, response.failed());
    }

    @Test
    void shouldHandleStorageFailureGracefully() {
        MultipartFile file = createMockFile("photo.jpg");

        when(storageService.upload(any(), anyString(), any(), any()))
                .thenThrow(new StorageException("Error de Drive"));

        UploadResponse response = uploadService.upload(List.of(file), null, null);

        assertFalse(response.success());
        assertEquals(0, response.uploaded());
        assertEquals(1, response.failed());
    }

    private MockMultipartFile createMockFile(String filename) {
        return new MockMultipartFile(
                "files", filename, "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    }
}

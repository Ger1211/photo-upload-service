package com.wedding.upload.exception;

import com.wedding.upload.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldHandleFileValidationException() {
        ResponseEntity<ErrorResponse> response = handler
                .handleFileValidation(new FileValidationException("Archivo no permitido."));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().status());
        assertEquals("Archivo no permitido.", response.getBody().message());
        assertNotNull(response.getBody().timestamp());
    }

    @Test
    void shouldHandleUploadLimitExceededException() {
        ResponseEntity<ErrorResponse> response = handler
                .handleUploadLimitExceeded(new UploadLimitExceededException(
                        "El limite maximo es de 20 archivos."));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().status());
    }

    @Test
    void shouldHandleStorageException() {
        ResponseEntity<ErrorResponse> response = handler
                .handleStorage(new StorageException("Drive error"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(500, response.getBody().status());
    }

    @Test
    void shouldHandleGenericException() {
        ResponseEntity<ErrorResponse> response = handler
                .handleGeneric(new RuntimeException("Something went wrong"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(500, response.getBody().status());
    }
}

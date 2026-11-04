package com.wedding.upload.controller;

import com.wedding.upload.config.CorsConfig;
import com.wedding.upload.config.RateLimitFilter;
import com.wedding.upload.dto.UploadResponse;
import com.wedding.upload.exception.GlobalExceptionHandler;
import com.wedding.upload.service.UploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {PhotoUploadController.class, HealthController.class},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {RateLimitFilter.class, CorsConfig.class}
        )
)
@Import(GlobalExceptionHandler.class)
class PhotoUploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UploadService uploadService;

    @Test
    void shouldReturnSuccessResponse() throws Exception {
        UploadResponse mockResponse = new UploadResponse(true, 2, 0,
                "Las fotos se subieron correctamente.");
        when(uploadService.upload(any(), eq("Maria"), any())).thenReturn(mockResponse);

        MockMultipartFile file1 = new MockMultipartFile(
                "files", "photo1.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        MockMultipartFile file2 = new MockMultipartFile(
                "files", "photo2.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        mockMvc.perform(multipart("/api/upload")
                        .file(file1)
                        .file(file2)
                        .param("guestName", "Maria"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.uploaded").value(2))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.message")
                        .value("Las fotos se subieron correctamente."));
    }

    @Test
    void shouldForwardEventParameter() throws Exception {
        UploadResponse mockResponse = new UploadResponse(true, 1, 0,
                "Las fotos se subieron correctamente.");
        when(uploadService.upload(any(), eq(null), eq("marina-fernando"))).thenReturn(mockResponse);

        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        mockMvc.perform(multipart("/api/upload")
                        .file(file)
                        .param("event", "marina-fernando"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void shouldReturnHealthStatus() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldReturnUploadResponseWithFailures() throws Exception {
        UploadResponse mockResponse = new UploadResponse(true, 1, 2,
                "1 fotos subidas correctamente. 2 archivos fallaron.");
        when(uploadService.upload(any(), eq(null), any())).thenReturn(mockResponse);

        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        mockMvc.perform(multipart("/api/upload")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.uploaded").value(1))
                .andExpect(jsonPath("$.failed").value(2));
    }
}

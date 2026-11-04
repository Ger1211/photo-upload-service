package com.wedding.upload.dto;

public record UploadResponse(
        boolean success,
        int uploaded,
        int failed,
        String message
) {}

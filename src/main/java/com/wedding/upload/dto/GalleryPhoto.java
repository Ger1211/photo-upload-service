package com.wedding.upload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GalleryPhoto(
        String filename,
        String originalName,
        String guestName,
        LocalDateTime uploadedAt,
        long sizeBytes,
        String url,
        String thumbnail
) {}

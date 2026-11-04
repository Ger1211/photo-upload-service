package com.wedding.upload.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class FileNameGenerator {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public String generate(String originalFilename, String guestName) {
        return generate(originalFilename, guestName, null);
    }

    /**
     * Generates a storage filename. When {@code detectedExt} is provided (the real
     * format detected from magic bytes), it overrides the extension taken from the
     * original filename so WebP payloads delivered under a ".jpg" name are stored
     * with a truthful ".webp" extension.
     */
    public String generate(String originalFilename, String guestName, String detectedExt) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 7);
        String ext = canonicalExtension(detectedExt, originalFilename);

        if (guestName != null && !guestName.isBlank()) {
            String safeName = sanitize(guestName);
            return timestamp + "_" + uuid + "_" + safeName + ext;
        }

        return timestamp + "_" + uuid + ext;
    }

    private String canonicalExtension(String detectedExt, String originalFilename) {
        if (detectedExt != null && !detectedExt.isBlank()) {
            String ext = detectedExt.trim().toLowerCase();
            if ("jpeg".equals(ext)) ext = "jpg";
            return "." + ext;
        }
        return extractExtension(originalFilename);
    }

    private String extractExtension(String filename) {
        if (filename == null) return ".jpg";
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return ".jpg";
        String ext = filename.substring(dot);
        if (ext.length() > 10) return ".jpg";
        return ext;
    }

    private String sanitize(String name) {
        String sanitized = name.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        sanitized = sanitized.replaceAll("[^a-zA-Z0-9._-]", "");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^[._-]+|[._-]+$", "");
        if (sanitized.isEmpty()) return "invitado";
        if (sanitized.length() > 30) sanitized = sanitized.substring(0, 30);
        return sanitized;
    }
}

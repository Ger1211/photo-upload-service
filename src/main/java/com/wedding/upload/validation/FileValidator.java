package com.wedding.upload.validation;

import com.wedding.upload.config.UploadProperties;
import com.wedding.upload.exception.FileValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

@Component
public class FileValidator {

    private static final Logger log = LoggerFactory.getLogger(FileValidator.class);

    private final UploadProperties uploadProperties;

    public FileValidator(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileValidationException("No se recibieron archivos.");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new FileValidationException("Archivo sin nombre.");
        }

        long fileSize = file.getSize();
        if (fileSize > uploadProperties.getMaxFileSizeBytes()) {
            throw new FileValidationException(
                    "El archivo \"" + originalFilename + "\" supera el tamano maximo permitido " +
                            "de " + uploadProperties.getMaxFileSizeMb() + " MB.");
        }

        String extension = extractExtension(originalFilename);
        if (extension == null || !uploadProperties.getAllowedExtensions()
                .contains(extension.toLowerCase(Locale.ROOT))) {
            throw new FileValidationException(
                    "Archivo no permitido: \"" + originalFilename + "\". " +
                            "Formatos aceptados: " + String.join(", ", uploadProperties.getAllowedExtensions()) + ".");
        }

        String contentType = file.getContentType();
        if (contentType == null || !uploadProperties.getAllowedContentTypes()
                .contains(contentType.toLowerCase(Locale.ROOT))) {
            log.warn("Unexpected MIME type for file: {} type: {}", originalFilename, contentType);
        }

        if (detectFormat(file) == null) {
            throw new FileValidationException(
                    "El archivo \"" + originalFilename + "\" no es una imagen valida.");
        }
    }

    /**
     * Detects the real image format from the file magic bytes, independent of the
     * declared extension or MIME type. Returns a canonical extension ("jpg", "png",
     * "webp", "heic") or null when the header is not a supported image. This matters
     * because Android/WhatsApp commonly deliver WebP payloads under a ".jpg" name.
     */
    public String detectFormat(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] header = new byte[12];
            int read = Math.min(in.read(header), 12);
            if (read < 4) {
                return null;
            }

            if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
                return "jpg";
            }

            if (header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47) {
                return "png";
            }

            if (header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46
                    && read >= 12
                    && header[8] == 0x57 && header[9] == 0x45
                    && header[10] == 0x42 && header[11] == 0x50) {
                return "webp";
            }

            if (read >= 12
                    && header[4] == 0x66 && header[5] == 0x74
                    && header[6] == 0x79 && header[7] == 0x70) {
                String brand = new String(header, 8, 4);
                String lowerBrand = brand.toLowerCase(Locale.ROOT);
                if (lowerBrand.startsWith("hei") || lowerBrand.equals("mif1")) {
                    return "heic";
                }
            }

            return null;
        } catch (IOException e) {
            log.warn("Failed to read magic bytes for validation: {}", file.getOriginalFilename());
            return null;
        }
    }

    private String extractExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return null;
        }
        return filename.substring(dotIndex + 1);
    }

    }

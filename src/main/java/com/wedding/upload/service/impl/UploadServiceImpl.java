package com.wedding.upload.service.impl;

import com.wedding.upload.config.UploadProperties;
import com.wedding.upload.dto.UploadResponse;
import com.wedding.upload.exception.FileValidationException;
import com.wedding.upload.service.UploadService;
import com.wedding.upload.exception.StorageException;
import com.wedding.upload.exception.UploadLimitExceededException;
import com.wedding.upload.storage.StorageService;
import com.wedding.upload.util.FileNameGenerator;
import com.wedding.upload.validation.FileValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Service
public class UploadServiceImpl implements UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadServiceImpl.class);

    private final StorageService storageService;
    private final FileValidator fileValidator;
    private final FileNameGenerator fileNameGenerator;
    private final UploadProperties uploadProperties;

    public UploadServiceImpl(
            StorageService storageService,
            FileValidator fileValidator,
            FileNameGenerator fileNameGenerator,
            UploadProperties uploadProperties) {
        this.storageService = storageService;
        this.fileValidator = fileValidator;
        this.fileNameGenerator = fileNameGenerator;
        this.uploadProperties = uploadProperties;
    }

    @Override
    public UploadResponse upload(List<MultipartFile> files, String guestName, String event) {
        long start = System.currentTimeMillis();

        log.info("Upload started - files: {}, guestName: {}, event: {}",
                files != null ? files.size() : 0, guestName, event);

        if (files == null || files.isEmpty()) {
            return new UploadResponse(false, 0, 0, "No se recibieron archivos.");
        }

        if (files.size() > uploadProperties.getMaxFilesPerRequest()) {
            throw new UploadLimitExceededException(
                    "El limite maximo es de " + uploadProperties.getMaxFilesPerRequest()
                            + " archivos por solicitud.");
        }

        int uploaded = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "desconocido";
            try {
                fileValidator.validate(file);
                String detectedExt = fileValidator.detectFormat(file);
                String newFilename = fileNameGenerator.generate(originalFilename, guestName, detectedExt);
                storageService.upload(file, newFilename, guestName, event);
                uploaded++;
                log.info("File uploaded successfully: {} -> {}", originalFilename, newFilename);
            } catch (FileValidationException | StorageException e) {
                failed++;
                String errorDetail = originalFilename + ": " + e.getMessage();
                errors.add(errorDetail);
                log.warn("File upload failed: {}", errorDetail);
            }
        }

        long duration = System.currentTimeMillis() - start;

        if (uploaded == 0 && failed > 0) {
            log.info("Upload finished - all {} files failed. Duration: {}ms", failed, duration);
            return new UploadResponse(false, 0, failed,
                    "Ocurrio un error al subir las fotos.");
        }

        String message;
        if (failed == 0) {
            message = "Las fotos se subieron correctamente.";
        } else {
            message = uploaded + " fotos subidas correctamente. " + failed + " archivos fallaron.";
        }

        log.info("Upload finished - uploaded: {}, failed: {}, duration: {}ms", uploaded, failed, duration);
        return new UploadResponse(true, uploaded, failed, message);
    }
}

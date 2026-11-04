package com.wedding.upload.controller;

import com.wedding.upload.dto.UploadResponse;
import com.wedding.upload.service.UploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PhotoUploadController {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadController.class);

    private final UploadService uploadService;

    public PhotoUploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "guestName", required = false) String guestName,
            @RequestParam(value = "event", required = false) String event) {

        log.info("Received upload request - files: {}, guestName: {}, event: {}",
                files != null ? files.size() : 0, guestName, event);

        UploadResponse response = uploadService.upload(files, guestName, event);
        return ResponseEntity.ok(response);
    }
}

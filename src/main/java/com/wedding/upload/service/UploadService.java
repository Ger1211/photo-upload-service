package com.wedding.upload.service;

import com.wedding.upload.dto.UploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface UploadService {

    UploadResponse upload(List<MultipartFile> files, String guestName, String event);
}

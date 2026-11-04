package com.wedding.upload.storage;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

    String upload(MultipartFile file, String targetFilename, String guestName, String event);
}

package com.wedding.upload.exception;

public class UploadLimitExceededException extends RuntimeException {

    public UploadLimitExceededException(String message) {
        super(message);
    }
}

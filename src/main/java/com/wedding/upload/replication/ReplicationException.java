package com.wedding.upload.replication;

/** Raised when the peer cannot be reached or returns an unexpected response. */
public class ReplicationException extends RuntimeException {

    public ReplicationException(String message) {
        super(message);
    }

    public ReplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.wedding.upload.storage.local;

import com.wedding.upload.dto.PhotoInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhotoManifestTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldKeepAllEntriesUnderConcurrentWrites() throws Exception {
        int writers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < writers; i++) {
                final int n = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    PhotoManifest.addEntry(tempDir, new PhotoInfo(
                            "photo-" + n + ".jpg", "orig-" + n + ".jpg", "guest",
                            LocalDateTime.now(), 100));
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<PhotoInfo> photos = PhotoManifest.readAll(tempDir);
        assertEquals(writers, photos.size(),
                "Concurrent addEntry must not lose manifest entries");
    }
}
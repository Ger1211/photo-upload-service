package com.wedding.upload.storage.local;

import com.wedding.upload.dto.PhotoInfo;
import com.wedding.upload.exception.StorageException;
import com.wedding.upload.storage.StorageService;
import com.wedding.upload.util.ImageThumbnail;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class LocalDiskStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskStorageService.class);

    private static final int THUMB_POOL_SIZE = 4;
    private static final int THUMB_QUEUE_CAPACITY = 256;
    private static final Pattern DATE_FOLDER = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final String THUMBNAILS_DIR = "thumbnails";

    private final Path storagePath;
    private final ExecutorService thumbnailExecutor;

    public LocalDiskStorageService(
            @Value("${storage.local.path:./uploads}") String storagePathStr) {
        this.storagePath = Path.of(storagePathStr).toAbsolutePath().normalize();
        this.thumbnailExecutor = new ThreadPoolExecutor(
                THUMB_POOL_SIZE, THUMB_POOL_SIZE,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(THUMB_QUEUE_CAPACITY),
                daemonThreadFactory("thumbnail"),
                new ThreadPoolExecutor.DiscardPolicy());
        try {
            Files.createDirectories(this.storagePath);
            log.info("Local disk storage initialized. Path: {}", this.storagePath);
        } catch (IOException e) {
            throw new StorageException("Failed to create upload directory: " + this.storagePath, e);
        }
    }

    @PreDestroy
    void shutdown() {
        thumbnailExecutor.shutdown();
    }

    @Override
    public String upload(MultipartFile file, String targetFilename, String guestName, String event) {
        return store(file, targetFilename, guestName, event, LocalDate.now(), LocalDateTime.now()).path().toString();
    }

    /**
     * Stores a photo under an explicit date folder. Replication needs this: a
     * queued push retried after midnight must still land in the folder of the day
     * the photo was actually taken, otherwise the gallery breaks.
     */
    public StoredPhoto upload(MultipartFile file, String targetFilename, String guestName,
                              String event, LocalDate dateFolder) {
        return store(file, targetFilename, guestName, event, dateFolder, LocalDateTime.now());
    }

    public StoredPhoto store(MultipartFile file, String targetFilename, String guestName,
                             String event, LocalDate dateFolder, LocalDateTime uploadedAt) {
        return store(file, targetFilename, guestName, event, dateFolder, uploadedAt, null, null);
    }

    public StoredPhoto store(MultipartFile file, String targetFilename, String guestName,
                             String event, LocalDate dateFolder, LocalDateTime uploadedAt,
                             String expectedSha256) {
        return store(file, targetFilename, guestName, event, dateFolder, uploadedAt, null, expectedSha256);
    }

    /**
     * @param originalName the name the guest's device used. Replication passes it
     *                     explicitly: the multipart part already carries the
     *                     renamed file, so falling back to it would lose the
     *                     original name on the replica. {@code null}/blank keeps
     *                     the upload behaviour (use the multipart filename).
     */
    public StoredPhoto store(MultipartFile file, String targetFilename, String guestName,
                             String event, LocalDate dateFolder, LocalDateTime uploadedAt,
                             String originalName, String expectedSha256) {
        String resolvedOriginalName = originalName;
        if (resolvedOriginalName == null || resolvedOriginalName.isBlank()) {
            resolvedOriginalName = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "unknown";
        }
        try (InputStream in = file.getInputStream()) {
            return store(in, file.getSize(), resolvedOriginalName, targetFilename, guestName,
                    event, dateFolder, uploadedAt, expectedSha256);
        } catch (IOException e) {
            throw new StorageException("Error al guardar el archivo: " + targetFilename, e);
        }
    }

    /**
     * Core write path: copies the stream to disk, computes its SHA-256 on the fly
     * (single pass, no extra read) and appends the manifest entry.
     *
     * @param expectedSha256 optional checksum to verify against; {@code null} or
     *                       blank skips verification (legacy entries have no hash)
     */
    public StoredPhoto store(InputStream in, long sizeBytes, String originalName,
                             String targetFilename, String guestName, String event,
                             LocalDate dateFolder, LocalDateTime uploadedAt, String expectedSha256) {

        Path baseDir = resolveEventPath(event);
        Path dateDir = baseDir.resolve(dateFolder.toString());
        Path targetPath = dateDir.resolve(targetFilename);

        try {
            Files.createDirectories(dateDir);

            MessageDigest digest = sha256Digest();
            try (DigestInputStream digestIn = new DigestInputStream(in, digest)) {
                Files.copy(digestIn, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());

            if (expectedSha256 != null && !expectedSha256.isBlank()
                    && !expectedSha256.equalsIgnoreCase(sha256)) {
                Files.deleteIfExists(targetPath);
                throw new StorageException("Checksum mismatch for " + targetFilename);
            }

            PhotoInfo info = new PhotoInfo(targetFilename, originalName, guestName,
                    uploadedAt, sizeBytes, sha256);
            PhotoManifest.addEntry(baseDir, info);

            log.info("File saved: {} ({} bytes, guest: {}, event: {})",
                    targetPath, sizeBytes, guestName, event);

            scheduleThumbnail(baseDir, dateFolder, targetFilename, targetPath);

            return new StoredPhoto(targetFilename, originalName, guestName, event,
                    dateFolder, uploadedAt, sizeBytes, sha256, targetPath);
        } catch (IOException e) {
            log.error("Failed to save file to disk: {}", targetPath, e);
            throw new StorageException("Error al guardar el archivo: " + targetFilename, e);
        }
    }

    private void scheduleThumbnail(Path baseDir, LocalDate dateFolder, String filename, Path source) {
        Path thumbPath = baseDir.resolve(THUMBNAILS_DIR).resolve(dateFolder.toString()).resolve(filename);
        thumbnailExecutor.submit(() -> {
            boolean ok = ImageThumbnail.save(source, thumbPath);
            if (!ok) {
                log.warn("Thumbnail generation failed: {} -> {}", source, thumbPath);
            }
        });
    }

    public Path getStoragePath() {
        return storagePath;
    }

    /** Absolute path of a stored photo; callers must have validated the segments. */
    public Path photoPath(String event, String dateFolder, String filename) {
        return resolveEventPath(event).resolve(dateFolder).resolve(filename).normalize();
    }

    public boolean exists(String event, String dateFolder, String filename) {
        return Files.exists(photoPath(event, dateFolder, filename));
    }

    /**
     * Album names present on disk. Date folders ({@code yyyy-MM-dd}) and the
     * thumbnails folder are not albums, so they are filtered out.
     */
    public List<String> listEvents() {
        List<String> events = new ArrayList<>();
        try (var stream = Files.list(storagePath)) {
            stream.filter(Files::isDirectory)
                    .map(dir -> dir.getFileName().toString())
                    .filter(name -> !DATE_FOLDER.matcher(name).matches())
                    .filter(name -> !THUMBNAILS_DIR.equals(name))
                    .sorted()
                    .forEach(events::add);
        } catch (IOException e) {
            log.warn("Failed to list event folders under {}", storagePath, e);
        }
        return events;
    }

    /**
     * Resolves the base directory for an event. A null/blank event maps to the
     * storage root (default album). Event names are sanitized to safe, lowercase
     * directory/URL segments.
     */
    public Path resolveEventPath(String event) {
        String clean = sanitizeEvent(event);
        return clean == null ? storagePath : storagePath.resolve(clean);
    }

    public static String sanitizeEvent(String event) {
        if (event == null) return null;
        String clean = event.trim().toLowerCase().replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (clean.isEmpty()) return null;
        if (clean.length() > 50) clean = clean.substring(0, 50);
        return clean;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}

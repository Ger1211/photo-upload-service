package com.wedding.upload.controller;

import com.wedding.upload.dto.GalleryPhoto;
import com.wedding.upload.dto.GalleryResponse;
import com.wedding.upload.dto.PhotoInfo;
import com.wedding.upload.storage.local.LocalDiskStorageService;
import com.wedding.upload.storage.local.PhotoManifest;
import com.wedding.upload.util.ImageThumbnail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class GalleryController {

    private static final Logger log = LoggerFactory.getLogger(GalleryController.class);

    private final Path storagePath;
    private final LocalDiskStorageService storageService;

    public GalleryController(LocalDiskStorageService storageService) {
        this.storageService = storageService;
        this.storagePath = storageService.getStoragePath();
    }

    @GetMapping("/photos")
    public ResponseEntity<GalleryResponse> listPhotos(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "6") int size,
            @RequestParam(value = "event", required = false) String event) {

        Path baseDir = storageService.resolveEventPath(event);
        List<PhotoInfo> allPhotos = PhotoManifest.readAll(baseDir);
        long total = allPhotos.size();

        int fromIndex = page * size;
        if (fromIndex >= allPhotos.size()) {
            GalleryResponse empty = new GalleryResponse(List.of(), total, page, size, false);
            return ResponseEntity.ok(empty);
        }

        int toIndex = Math.min(fromIndex + size, allPhotos.size());
        List<PhotoInfo> pageItems = allPhotos.subList(fromIndex, toIndex);

        List<GalleryPhoto> photos = pageItems.stream()
                .map(p -> toGalleryPhoto(baseDir, p, event))
                .toList();

        boolean hasMore = toIndex < allPhotos.size();

        return ResponseEntity.ok(new GalleryResponse(photos, total, page, size, hasMore));
    }

    private GalleryPhoto toGalleryPhoto(Path baseDir, PhotoInfo p, String event) {
        String date = p.uploadedAt().toLocalDate().toString();
        Path thumbPath = baseDir.resolve("thumbnails").resolve(date).resolve(p.filename());

        String evt = event == null || event.isBlank()
                ? null
                : LocalDiskStorageService.sanitizeEvent(event);

        String url;
        String thumbnail;
        if (thumbPath.toFile().exists()) {
            thumbnail = evt == null
                    ? "/api/thumbnails/" + date + "/" + p.filename()
                    : "/api/thumbnails/" + evt + "/" + date + "/" + p.filename();
        } else {
            Path filePath = baseDir.resolve(date).resolve(p.filename());
            if (filePath.toFile().exists()) {
                thumbnail = ImageThumbnail.create(filePath);
                if (thumbnail == null) {
                    log.warn("Thumbnail generation failed, falling back to full photo: {}", filePath);
                    thumbnail = evt == null
                            ? "/api/photos/" + date + "/" + p.filename()
                            : "/api/photos/" + evt + "/" + date + "/" + p.filename();
                }
            } else {
                thumbnail = null;
            }
        }

        url = evt == null
                ? "/api/photos/" + date + "/" + p.filename()
                : "/api/photos/" + evt + "/" + date + "/" + p.filename();

        return new GalleryPhoto(
                p.filename(),
                p.originalName(),
                p.guestName(),
                p.uploadedAt(),
                p.sizeBytes(),
                url,
                thumbnail
        );
    }

    @GetMapping("/thumbnails/{date}/{filename}")
    public ResponseEntity<Resource> serveThumbnail(
            @PathVariable String date,
            @PathVariable String filename) {

        Path filePath = storagePath.resolve("thumbnails").resolve(date).resolve(filename).normalize();

        if (!filePath.startsWith(storagePath)) {
            return ResponseEntity.notFound().build();
        }

        if (!filePath.toFile().exists()) {
            Path source = storagePath.resolve(date).resolve(filename).normalize();
            if (!source.startsWith(storagePath) || !ensureThumbnail(source, filePath)) {
                return ResponseEntity.notFound().build();
            }
        }

        FileSystemResource resource = new FileSystemResource(filePath);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .body(resource);
    }

    @GetMapping("/thumbnails/{event}/{date}/{filename}")
    public ResponseEntity<Resource> serveEventThumbnail(
            @PathVariable String event,
            @PathVariable String date,
            @PathVariable String filename) {

        Path eventDir = storagePath.resolve(event).normalize();
        Path filePath = eventDir.resolve("thumbnails").resolve(date).resolve(filename).normalize();

        if (!filePath.startsWith(eventDir) || !eventDir.startsWith(storagePath)) {
            return ResponseEntity.notFound().build();
        }

        if (!filePath.toFile().exists()) {
            Path source = eventDir.resolve(date).resolve(filename).normalize();
            if (!source.startsWith(eventDir) || !ensureThumbnail(source, filePath)) {
                return ResponseEntity.notFound().build();
            }
        }

        FileSystemResource resource = new FileSystemResource(filePath);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .body(resource);
    }

    /**
     * Regenerates a missing thumbnail from its source photo and persists it. This
     * self-heals thumbnails that failed to generate asynchronously (e.g. formats
     * undecodable before the WebP plugin was added) without a manual step.
     */
    private boolean ensureThumbnail(Path source, Path thumbPath) {
        if (!source.toFile().exists()) {
            return false;
        }
        boolean ok = ImageThumbnail.save(source, thumbPath);
        if (!ok) {
            log.warn("Failed to regenerate missing thumbnail {} from {}", thumbPath, source);
        }
        return ok;
    }

    @GetMapping("/photos/{date}/{filename}")
    public ResponseEntity<Resource> servePhoto(
            @PathVariable String date,
            @PathVariable String filename) {

        Path filePath = storagePath.resolve(date).resolve(filename).normalize();

        if (!filePath.startsWith(storagePath)) {
            return ResponseEntity.notFound().build();
        }

        if (!filePath.toFile().exists()) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource resource = new FileSystemResource(filePath);

        String contentType = resolveContentType(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                .body(resource);
    }

    @GetMapping("/photos/{event}/{date}/{filename}")
    public ResponseEntity<Resource> serveEventPhoto(
            @PathVariable String event,
            @PathVariable String date,
            @PathVariable String filename) {

        Path eventDir = storagePath.resolve(event).normalize();
        Path filePath = eventDir.resolve(date).resolve(filename).normalize();

        if (!filePath.startsWith(eventDir) || !eventDir.startsWith(storagePath)) {
            return ResponseEntity.notFound().build();
        }

        if (!filePath.toFile().exists()) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource resource = new FileSystemResource(filePath);

        String contentType = resolveContentType(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                .body(resource);
    }

    private String resolveContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic")) return "image/heic";
        return "image/jpeg";
    }
}
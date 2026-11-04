package com.wedding.upload.replication;

import com.wedding.upload.dto.ReplicaManifestEntry;
import com.wedding.upload.dto.ReplicaStoreResponse;
import com.wedding.upload.dto.ReplicationStatus;
import com.wedding.upload.exception.StorageException;
import com.wedding.upload.storage.local.LocalDiskStorageService;
import com.wedding.upload.storage.local.PhotoManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Peer-facing endpoints used to replicate photos and to verify both copies are
 * identical. Every call requires the shared token; the endpoints are inert when
 * replication is disabled.
 */
@RestController
@RequestMapping("/api/internal")
@ConditionalOnProperty(prefix = "replication", name = "enabled", havingValue = "true")
public class InternalReplicationController {

    private static final Logger log = LoggerFactory.getLogger(InternalReplicationController.class);

    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");
    private static final Pattern DATE_FOLDER = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final LocalDiskStorageService local;
    private final ReplicationProperties properties;
    private final ReplicationReport report;
    private final Outbox outbox;

    public InternalReplicationController(LocalDiskStorageService local,
                                         ReplicationProperties properties,
                                         ReplicationReport report,
                                         Outbox outbox) {
        this.local = local;
        this.properties = properties;
        this.report = report;
        this.outbox = outbox;
    }

    @PostMapping(value = "/replicate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReplicaStoreResponse> replicate(
            @RequestHeader(value = ReplicationClient.TOKEN_HEADER, required = false) String token,
            @RequestParam("file") MultipartFile file,
            @RequestParam("filename") String filename,
            @RequestParam("dateFolder") String dateFolder,
            @RequestParam(value = "event", required = false, defaultValue = "") String event,
            @RequestParam(value = "originalName", required = false, defaultValue = "unknown") String originalName,
            @RequestParam(value = "guestName", required = false, defaultValue = "") String guestName,
            @RequestParam("uploadedAt") String uploadedAt,
            @RequestParam(value = "sha256", required = false, defaultValue = "") String sha256) {

        requireToken(token);
        String safeFilename = requireFilename(filename);
        LocalDate date = requireDate(dateFolder);
        LocalDateTime uploaded = parseTimestamp(uploadedAt);
        String safeEvent = LocalDiskStorageService.sanitizeEvent(event);

        if (local.exists(safeEvent, date.toString(), safeFilename)) {
            return ResponseEntity.ok(new ReplicaStoreResponse("skipped", safeFilename));
        }

        try {
            local.store(file, safeFilename, guestName, safeEvent, date, uploaded, originalName, sha256);
        } catch (StorageException e) {
            log.warn("Replication rejected for {}: {}", safeFilename, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ReplicaStoreResponse("rejected", safeFilename));
        }

        return ResponseEntity.ok(new ReplicaStoreResponse("stored", safeFilename));
    }

    @GetMapping("/manifest")
    public ResponseEntity<List<ReplicaManifestEntry>> manifest(
            @RequestHeader(value = ReplicationClient.TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        return ResponseEntity.ok(buildManifest());
    }

    @GetMapping("/photo")
    public ResponseEntity<Resource> photo(
            @RequestHeader(value = ReplicationClient.TOKEN_HEADER, required = false) String token,
            @RequestParam(value = "event", required = false, defaultValue = "") String event,
            @RequestParam("dateFolder") String dateFolder,
            @RequestParam("filename") String filename) {

        requireToken(token);
        String safeFilename = requireFilename(filename);
        LocalDate date = requireDate(dateFolder);
        String safeEvent = LocalDiskStorageService.sanitizeEvent(event);

        Path path = local.photoPath(safeEvent, date.toString(), safeFilename);
        Path base = local.resolveEventPath(safeEvent);
        if (!path.startsWith(base) || !Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentTypeOf(safeFilename)))
                .body(new FileSystemResource(path));
    }

    @GetMapping("/status")
    public ResponseEntity<ReplicationStatus> status(
            @RequestHeader(value = ReplicationClient.TOKEN_HEADER, required = false) String token) {
        requireToken(token);

        int localCount = buildManifest().size();
        ReplicationStatus latest = report.get();
        if (latest == null) {
            return ResponseEntity.ok(new ReplicationStatus(
                    properties.getRole(), properties.getPeerUrl(), false,
                    localCount, 0, 0, 0, 0, outbox.size(), null, "reconciliation not run yet"));
        }

        return ResponseEntity.ok(new ReplicationStatus(
                latest.role(), latest.peerUrl(), latest.peerReachable(),
                localCount, latest.peerCount(), latest.missingLocally(),
                latest.missingOnPeer(), latest.checksumMismatches(),
                outbox.size(), latest.lastReconcileAt(), latest.lastError()));
    }

    private List<ReplicaManifestEntry> buildManifest() {
        List<ReplicaManifestEntry> entries = new ArrayList<>();
        collect(entries, null);
        for (String event : local.listEvents()) {
            collect(entries, event);
        }
        return entries;
    }

    private void collect(List<ReplicaManifestEntry> target, String event) {
        Path base = local.resolveEventPath(event);
        PhotoManifest.readByDate(base).forEach((dateFolder, photos) -> photos.forEach(photo ->
                target.add(new ReplicaManifestEntry(
                        event,
                        dateFolder,
                        photo.filename(),
                        photo.originalName(),
                        photo.guestName(),
                        photo.uploadedAt() == null ? LocalDateTime.now().toString() : photo.uploadedAt().toString(),
                        photo.sizeBytes(),
                        photo.sha256()))));
    }

    private void requireToken(String provided) {
        String expected = properties.getToken();
        boolean configured = expected != null && !expected.isBlank();
        boolean matches = configured && provided != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid replication token");
        }
    }

    private String requireFilename(String filename) {
        if (filename == null || !SAFE_FILENAME.matcher(filename).matches() || filename.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }
        return filename;
    }

    private LocalDate requireDate(String dateFolder) {
        if (dateFolder == null || !DATE_FOLDER.matcher(dateFolder).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date folder");
        }
        try {
            return LocalDate.parse(dateFolder);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date folder");
        }
    }

    private LocalDateTime parseTimestamp(String uploadedAt) {
        try {
            return LocalDateTime.parse(uploadedAt);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid uploadedAt");
        }
    }

    private String contentTypeOf(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        return "image/jpeg";
    }
}

package com.wedding.upload.replication;

import com.wedding.upload.dto.ReplicaManifestEntry;
import com.wedding.upload.dto.ReplicationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * HTTP client for the peer's {@code /api/internal} endpoints. Every call carries
 * the shared token; failures are surfaced as {@link ReplicationException} so the
 * caller can queue the work instead of failing the guest upload.
 */
@Component
@ConditionalOnProperty(prefix = "replication", name = "enabled", havingValue = "true")
public class ReplicationClient {

    static final String TOKEN_HEADER = "X-Replication-Token";

    private static final Logger log = LoggerFactory.getLogger(ReplicationClient.class);

    private final ReplicationProperties properties;
    private final RestClient restClient;

    public ReplicationClient(ReplicationProperties properties) {
        this.properties = properties;

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));

        this.restClient = RestClient.builder()
                .baseUrl(normalizeBaseUrl(properties.getPeerUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    /** Streams the original file to the peer. Returns false when the peer rejects the push. */
    public boolean push(PendingPush push, Path source) {
        if (!Files.exists(source)) {
            log.warn("Cannot replicate, source file is gone: {}", source);
            return false;
        }

        // Built as a plain MultiValueMap on purpose: MultipartBodyBuilder drags in
        // reactive-streams, which this servlet-only app does not ship.
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(source));
        body.add("filename", push.filename());
        body.add("dateFolder", push.dateFolder());
        body.add("event", push.event() == null ? "" : push.event());
        body.add("originalName", push.originalName() == null ? "unknown" : push.originalName());
        body.add("guestName", push.guestName() == null ? "" : push.guestName());
        body.add("uploadedAt", push.uploadedAt().toString());
        body.add("sha256", push.sha256() == null ? "" : push.sha256());

        try {
            restClient.post()
                    .uri("/api/internal/replicate")
                    .header(TOKEN_HEADER, token())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.warn("Push to peer failed for {}: {}", push.filename(), e.getMessage());
            return false;
        }
    }

    public List<ReplicaManifestEntry> fetchManifest() {
        try {
            List<ReplicaManifestEntry> entries = restClient.get()
                    .uri("/api/internal/manifest")
                    .header(TOKEN_HEADER, token())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ReplicaManifestEntry>>() {
                    });
            return entries == null ? List.of() : entries;
        } catch (RuntimeException e) {
            throw new ReplicationException("Cannot fetch peer manifest: " + e.getMessage(), e);
        }
    }

    /** Streams one photo from the peer into {@code target}. */
    public void download(ReplicaManifestEntry entry, Path target) {
        restClient.get()
                .uri(builder -> builder.path("/api/internal/photo")
                        .queryParam("event", entry.event() == null ? "" : entry.event())
                        .queryParam("dateFolder", entry.dateFolder())
                        .queryParam("filename", entry.filename())
                        .build())
                .header(TOKEN_HEADER, token())
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new ReplicationException(
                                "Peer returned " + response.getStatusCode() + " for " + entry.filename());
                    }
                    try (InputStream in = response.getBody()) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return target;
                });
    }

    public ReplicationStatus fetchPeerStatus() {
        try {
            return restClient.get()
                    .uri("/api/internal/status")
                    .header(TOKEN_HEADER, token())
                    .retrieve()
                    .body(ReplicationStatus.class);
        } catch (RuntimeException e) {
            throw new ReplicationException("Cannot fetch peer status: " + e.getMessage(), e);
        }
    }

    private String token() {
        return properties.getToken();
    }

    private static String normalizeBaseUrl(String peerUrl) {
        if (peerUrl == null || peerUrl.isBlank()) {
            // Never reached in practice: callers guard on a configured peer.
            return "http://127.0.0.1:1";
        }
        return peerUrl.endsWith("/") ? peerUrl.substring(0, peerUrl.length() - 1) : peerUrl;
    }
}

package com.wedding.upload.storage.local;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wedding.upload.dto.PhotoInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PhotoManifest {

    private static final Logger log = LoggerFactory.getLogger(PhotoManifest.class);
    private static final String MANIFEST_FILENAME = "manifest.jsonl";
    private static final String LEGACY_MANIFEST_FILENAME = "manifest.json";
    private static final Object MANIFEST_LOCK = new Object();
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * Appends one JSON entry per line to the daily manifest. The append is O(1)
     * per upload — no whole-file rewrite — and is guarded by a process-wide lock
     * so concurrent uploads never interleave or lose entries. Readers tolerate
     * partial trailing lines (e.g. after a crash mid-append).
     */
    public static void addEntry(Path baseDir, PhotoInfo info) {
        synchronized (MANIFEST_LOCK) {
            addEntryLocked(baseDir, info);
        }
    }

    private static void addEntryLocked(Path baseDir, PhotoInfo info) {
        try {
            String dateFolder = LocalDate.now().toString();
            Path dateDir = baseDir.resolve(dateFolder);
            Files.createDirectories(dateDir);

            Path manifestFile = dateDir.resolve(MANIFEST_FILENAME);
            String line = objectMapper.writeValueAsString(info) + System.lineSeparator();
            Files.write(manifestFile, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to update manifest", e);
        }
    }

    public static List<PhotoInfo> readAll(Path baseDir) {
        List<PhotoInfo> all = new ArrayList<>();
        File root = baseDir.toFile();
        if (!root.exists() || !root.isDirectory()) {
            return all;
        }

        File[] dateDirs = root.listFiles(File::isDirectory);
        if (dateDirs == null) {
            return all;
        }

        for (File dateDir : dateDirs) {
            Path dir = dateDir.toPath();
            all.addAll(readManifest(dir.resolve(MANIFEST_FILENAME)));
            Path legacy = dir.resolve(LEGACY_MANIFEST_FILENAME);
            if (Files.exists(legacy)) {
                all.addAll(readLegacyManifest(legacy));
            }
        }

        all.sort(Comparator.comparing(PhotoInfo::uploadedAt).reversed());
        return all;
    }

    /**
     * Groups manifest entries by their date folder. Replication needs the folder
     * name explicitly because a delayed push can cross midnight: the folder is the
     * upload day, while {@link PhotoInfo#uploadedAt()} is the original timestamp.
     */
    public static Map<String, List<PhotoInfo>> readByDate(Path baseDir) {
        Map<String, List<PhotoInfo>> byDate = new LinkedHashMap<>();
        File root = baseDir.toFile();
        if (!root.exists() || !root.isDirectory()) {
            return byDate;
        }

        File[] dateDirs = root.listFiles(File::isDirectory);
        if (dateDirs == null) {
            return byDate;
        }

        for (File dateDir : dateDirs) {
            Path dir = dateDir.toPath();
            List<PhotoInfo> entries = new ArrayList<>(readManifest(dir.resolve(MANIFEST_FILENAME)));
            Path legacy = dir.resolve(LEGACY_MANIFEST_FILENAME);
            if (Files.exists(legacy)) {
                entries.addAll(readLegacyManifest(legacy));
            }
            if (!entries.isEmpty()) {
                byDate.put(dateDir.getName(), entries);
            }
        }
        return byDate;
    }

    private static List<PhotoInfo> readManifest(Path manifestFile) {
        List<PhotoInfo> entries = new ArrayList<>();
        try {
            if (Files.exists(manifestFile)) {
                for (String line : Files.readAllLines(manifestFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        entries.add(objectMapper.readValue(line, PhotoInfo.class));
                    } catch (IOException e) {
                        log.warn("Skipping corrupt manifest line in {}", manifestFile);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read manifest: {}", manifestFile, e);
        }
        return entries;
    }

    private static List<PhotoInfo> readLegacyManifest(Path manifestFile) {
        try {
            if (Files.exists(manifestFile)) {
                return objectMapper.readValue(
                        manifestFile.toFile(),
                        new TypeReference<List<PhotoInfo>>() {}
                );
            }
        } catch (IOException e) {
            log.warn("Failed to read legacy manifest: {}", manifestFile, e);
        }
        return new ArrayList<>();
    }
}
package com.aiassist.draft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.aiassist.config.OutputProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Persists drafts as Markdown files named with a timestamp, e.g.
 * {@code drafts/2026-07-05_14-30-05_weekly-status-meeting.md}. Saving is
 * best-effort: a disk problem is logged but never fails the draft request.
 */
@Service
public class DraftFileWriter {

    private static final Logger log = LoggerFactory.getLogger(DraftFileWriter.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final OutputProperties properties;

    public DraftFileWriter(OutputProperties properties) {
        this.properties = properties;
    }

    /** Saves under a fresh timestamped name. Returns the path, or null when saving is off/failed. */
    public Path save(Draft draft) {
        String stamp = LocalDateTime.now(ZoneId.systemDefault()).format(TIMESTAMP);
        return write(draft, stamp + "_" + slug(draft.title()) + ".md");
    }

    private Path write(Draft draft, String fileName) {
        if (!properties.saveDrafts()) {
            return null;
        }
        try {
            Path dir = Path.of(properties.dir());
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, draft.fullText() + "\n");
            log.info("Draft saved to {}", file.toAbsolutePath());
            return file.toAbsolutePath();
        } catch (IOException e) {
            log.warn("Could not save draft to disk: {}", e.getMessage());
            return null;
        }
    }

    private String slug(String title) {
        String slug = (title == null ? "draft" : title)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            slug = "draft";
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }
}

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
 * Persists drafts as Rich Text Format files named with a 12-hour timestamp,
 * e.g. {@code Minutes-07-23-2026_02-45-10-PM.rtf} — opens formatted in Word,
 * TextEdit, WordPad, Pages. Saving is best-effort: a disk problem is logged
 * but never fails the draft request.
 */
@Service
public class DraftFileWriter {

    private static final Logger log = LoggerFactory.getLogger(DraftFileWriter.class);
    // No colons (illegal in Windows file names) — hyphens throughout instead.
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("MM-dd-yyyy_hh-mm-ss-a", Locale.ROOT);

    private final OutputProperties properties;

    public DraftFileWriter(OutputProperties properties) {
        this.properties = properties;
    }

    /** Saves under a fresh timestamped name. Returns the path, or null when saving is off/failed. */
    public Path save(Draft draft) {
        String stamp = LocalDateTime.now(ZoneId.systemDefault()).format(TIMESTAMP);
        return write(draft, "Minutes-" + stamp + ".rtf");
    }

    private Path write(Draft draft, String fileName) {
        if (!properties.saveDrafts()) {
            return null;
        }
        try {
            Path dir = resolveDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, toRtf(draft));
            log.info("Draft saved to {}", file.toAbsolutePath());
            return file.toAbsolutePath();
        } catch (IOException e) {
            log.warn("Could not save draft to disk: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The output folder: the configured {@code ai-assist.output.dir} when set,
     * otherwise the {@code minutes-of-meeting} folder in the user's Documents.
     */
    private Path resolveDir() {
        String configured = properties.dir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        // Default: the installed app saves notes into the user's Documents.
        return com.aiassist.setup.UserPaths.meetingNotesDir();
    }

    /** Renders the structured draft as a simple RTF document. */
    private String toRtf(Draft draft) {
        StringBuilder rtf = new StringBuilder("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Helvetica;}}\\f0\\fs22\n");
        rtf.append("{\\b\\fs32 ").append(escape(draft.title())).append("\\par}\\par\n");
        if (draft.summary() != null && !draft.summary().isBlank()) {
            rtf.append("{\\b\\fs26 Summary\\par}\n");
            rtf.append(escape(draft.summary())).append("\\par\n");
        }
        for (Draft.Section section : draft.sections()) {
            rtf.append("\\par{\\b\\fs26 ").append(escape(section.heading())).append("\\par}\n");
            // Normalize CRLF/CR first: split("\n") alone would leave a stray
            // '\r' at the end of every line for text that came from a
            // Windows-authored source, which renders as visible garbage here.
            String body = section.body().replace("\r\n", "\n").replace("\r", "\n");
            for (String line : body.split("\n")) {
                if (line.startsWith("- ")) {
                    rtf.append("\\bullet  ").append(escape(line.substring(2))).append("\\par\n");
                } else if (line.isBlank()) {
                    rtf.append("\\par\n");
                } else {
                    rtf.append(escape(line)).append("\\par\n");
                }
            }
        }
        return rtf.append("}\n").toString();
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c == '\\' || c == '{' || c == '}') {
                out.append('\\').append(c);
            } else if (c > 127) {
                out.append("\\u").append((int) (short) c).append('?');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}

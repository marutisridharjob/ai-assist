package com.aiassist.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the final meeting notes are written as timestamped Markdown files.
 * Defaults to the user's Desktop (macOS/Windows/Linux) so the notes are
 * immediately visible after Stop, regardless of how the jar was launched —
 * a double-clicked jar has no useful working directory, and nothing should
 * ever be written into whatever folder the app happened to start from.
 */
@ConfigurationProperties(prefix = "ai-assist.output")
public record OutputProperties(boolean saveDrafts, String dir) {

    public OutputProperties {
        if (dir == null || dir.isBlank()) {
            dir = Path.of(System.getProperty("user.home"), "Desktop").toString();
        }
    }
}

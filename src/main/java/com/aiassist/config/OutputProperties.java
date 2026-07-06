package com.aiassist.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the final meeting notes are written as timestamped Markdown files.
 * Defaults to a folder in the user's Documents so notes are easy to find
 * regardless of how the jar was launched (double-click has no useful
 * working directory).
 */
@ConfigurationProperties(prefix = "ai-assist.output")
public record OutputProperties(boolean saveDrafts, String dir) {

    public OutputProperties {
        if (dir == null || dir.isBlank()) {
            dir = Path.of(System.getProperty("user.home"), "Documents", "ai-assist-notes").toString();
        }
    }
}

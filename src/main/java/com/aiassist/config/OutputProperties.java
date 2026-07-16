package com.aiassist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the final meeting notes are written as timestamped rich-text files.
 * When {@code dir} is left blank the notes go to a {@code meeting-notes}
 * folder inside the app's own folder (next to the jar); set {@code dir} to
 * an absolute path to save somewhere else. A blank value is kept blank here
 * so {@code DraftFileWriter} can resolve the app-folder default at save time.
 */
@ConfigurationProperties(prefix = "ai-assist.output")
public record OutputProperties(boolean saveDrafts, String dir) {
}

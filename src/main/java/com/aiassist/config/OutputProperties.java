package com.aiassist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the final meeting notes are written as timestamped rich-text files.
 * When {@code dir} is left blank the notes go to a {@code minutes-of-meeting}
 * folder in the user's Documents; set {@code dir} to an absolute path to save
 * somewhere else. A blank value is kept blank here so {@code DraftFileWriter}
 * can resolve the Documents default at save time.
 */
@ConfigurationProperties(prefix = "ai-assist.output")
public record OutputProperties(boolean saveDrafts, String dir) {
}

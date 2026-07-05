package com.aiassist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where drafts are written as timestamped Markdown files. */
@ConfigurationProperties(prefix = "ai-assist.output")
public record OutputProperties(boolean saveDrafts, String dir) {

    public OutputProperties {
        if (dir == null || dir.isBlank()) {
            dir = "drafts";
        }
    }
}

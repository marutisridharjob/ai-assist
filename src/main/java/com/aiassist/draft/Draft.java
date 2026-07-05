package com.aiassist.draft;

import java.time.Instant;
import java.util.List;

/** A structured, detailed draft produced from a session's captured notes. */
public record Draft(String title,
                    String contentType,
                    String tone,
                    String summary,
                    List<Section> sections,
                    List<String> keyPoints,
                    List<String> actionItems,
                    String fullText,
                    String generatedBy,
                    Instant generatedAt,
                    String savedTo) {

    public record Section(String heading, String body) {
    }

    /** Drafters produce drafts without a file; the writer attaches the path afterwards. */
    public Draft withSavedTo(String path) {
        return new Draft(title, contentType, tone, summary, sections, keyPoints,
                actionItems, fullText, generatedBy, generatedAt, path);
    }
}

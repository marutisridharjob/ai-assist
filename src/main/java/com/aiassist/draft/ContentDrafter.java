package com.aiassist.draft;

/** Strategy for turning captured notes into a detailed draft. */
public interface ContentDrafter {

    Draft draft(String topic, String transcript, DraftOptions options);

    /** Identifier reported in {@link Draft#generatedBy()}. */
    String name();
}

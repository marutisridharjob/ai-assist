package com.aiassist.draft;

/** What kind of document to draft and in what voice. */
public record DraftOptions(ContentType contentType, Tone tone) {

    public enum ContentType {
        EMAIL, DOCUMENT, MEETING_NOTES, BLOG_POST, SUMMARY
    }

    public enum Tone {
        PROFESSIONAL, FRIENDLY, CONCISE, PERSUASIVE
    }

    public static DraftOptions defaults() {
        return new DraftOptions(ContentType.DOCUMENT, Tone.PROFESSIONAL);
    }

    public DraftOptions {
        if (contentType == null) {
            contentType = ContentType.DOCUMENT;
        }
        if (tone == null) {
            tone = Tone.PROFESSIONAL;
        }
    }
}

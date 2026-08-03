package com.aiassist.draft;

import java.util.ArrayList;
import java.util.List;

import com.aiassist.listen.Utterance;

/**
 * Appends the word-for-word transcript with source attribution to a draft,
 * so it is always clear who was captured where: {@code [mic]} is the user's
 * side of the room, {@code [other]} is the other participants arriving
 * through the system/speaker audio. Used for both the running interim draft
 * and the final saved notes.
 */
public final class AttributedTranscript {

    public static final String HEADING = "Full transcript (who said what)";
    private static final String LEGEND =
            "You = you / your side of the room · Other = other participants (system audio)";

    private AttributedTranscript() {
    }

    private static final java.time.format.DateTimeFormatter TIMESTAMP =
            java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault());

    /** Friendly speaker label for the saved document: You / Other. */
    public static String speakerLabel(String speaker) {
        if ("you".equalsIgnoreCase(speaker)) {
            return "You";
        }
        if ("other".equalsIgnoreCase(speaker)) {
            return "Other";
        }
        if (speaker == null || speaker.isBlank()) {
            return "Other";
        }
        return Character.toUpperCase(speaker.charAt(0)) + speaker.substring(1);
    }

    /**
     * The verbatim transcript text: a legend, a single "Meeting started"
     * timestamp, one line per non-blank utterance tagged You / Other (no
     * per-line timestamp — the start/end timestamps already bound the whole
     * conversation), and a single "Meeting ended" timestamp at the close.
     * Utterances with no actual words (a silent/near-silent stretch that
     * still produced an entry) are skipped rather than printed as an empty
     * line.
     */
    public static String rawText(List<Utterance> utterances, java.time.Instant startedAt,
            java.time.Instant endedAt) {
        StringBuilder lines = new StringBuilder(LEGEND).append("\n");
        lines.append("Meeting started: ").append(TIMESTAMP.format(startedAt)).append("\n\n");
        for (Utterance utterance : utterances) {
            String text = utterance.text() == null ? "" : utterance.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            lines.append(speakerLabel(utterance.speaker())).append(":  ").append(text).append("\n");
        }
        lines.append("\nMeeting ended: ")
                .append(TIMESTAMP.format(endedAt != null ? endedAt : java.time.Instant.now()));
        return lines.toString();
    }

    public static Draft appendTo(Draft draft, List<Utterance> utterances,
            java.time.Instant startedAt, java.time.Instant endedAt) {
        String lines = rawText(utterances, startedAt, endedAt);
        List<Draft.Section> sections = new ArrayList<>(draft.sections());
        sections.add(new Draft.Section(HEADING, lines));
        return new Draft(draft.title(), draft.contentType(), draft.tone(), draft.summary(),
                List.copyOf(sections), draft.keyPoints(), draft.actionItems(),
                draft.fullText() + "\n\n## " + HEADING + "\n\n" + lines,
                draft.generatedBy(), draft.generatedAt(), draft.savedTo());
    }
}

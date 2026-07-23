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

    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault());

    /** Friendly speaker label for the saved document: You / Other. */
    private static String speakerLabel(String speaker) {
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
     * The verbatim transcript text: a legend, then one line per utterance with
     * the timestamp and the spoken content side by side, tagged You / Other.
     */
    public static String rawText(List<Utterance> utterances) {
        StringBuilder lines = new StringBuilder(LEGEND).append("\n");
        for (Utterance utterance : utterances) {
            lines.append("\n[").append(TIME.format(utterance.capturedAt())).append("]  ")
                    .append(speakerLabel(utterance.speaker())).append(":  ").append(utterance.text());
        }
        return lines.toString();
    }

    public static Draft appendTo(Draft draft, List<Utterance> utterances) {
        String lines = rawText(utterances);
        List<Draft.Section> sections = new ArrayList<>(draft.sections());
        sections.add(new Draft.Section(HEADING, lines));
        return new Draft(draft.title(), draft.contentType(), draft.tone(), draft.summary(),
                List.copyOf(sections), draft.keyPoints(), draft.actionItems(),
                draft.fullText() + "\n\n## " + HEADING + "\n\n" + lines,
                draft.generatedBy(), draft.generatedAt(), draft.savedTo());
    }
}

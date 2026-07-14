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
            "[you] = you / your side of the room · [other] = other participants (system audio) · "
            + "[Speaker-1/2/...] = individual meeting voices (when the speaker model is installed)";

    private AttributedTranscript() {
    }

    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault());

    /** The verbatim transcript text: legend then one timestamped, tagged line each. */
    public static String rawText(List<Utterance> utterances) {
        StringBuilder lines = new StringBuilder(LEGEND).append("\n");
        for (Utterance utterance : utterances) {
            lines.append("\n[").append(TIME.format(utterance.capturedAt())).append("] [")
                    .append(utterance.speaker()).append("] ").append(utterance.text());
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

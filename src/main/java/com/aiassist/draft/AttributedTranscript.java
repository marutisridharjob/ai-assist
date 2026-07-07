package com.aiassist.draft;

import java.util.ArrayList;
import java.util.List;

import com.aiassist.listen.Utterance;

/**
 * Appends the word-for-word transcript with source attribution to a draft,
 * so it is always clear who was captured where: {@code [mic]} is the user's
 * side of the room, {@code [meeting]} is the other participants arriving
 * through the system/speaker audio. Used for both the running interim draft
 * and the final saved notes.
 */
public final class AttributedTranscript {

    public static final String HEADING = "Full transcript (who said what)";
    private static final String LEGEND =
            "[mic] = you / your side of the room · [meeting] = other participants (system audio)";

    private AttributedTranscript() {
    }

    public static Draft appendTo(Draft draft, List<Utterance> utterances) {
        StringBuilder lines = new StringBuilder(LEGEND).append("\n");
        for (Utterance utterance : utterances) {
            lines.append("\n[").append(utterance.speaker()).append("] ").append(utterance.text());
        }
        List<Draft.Section> sections = new ArrayList<>(draft.sections());
        sections.add(new Draft.Section(HEADING, lines.toString()));
        return new Draft(draft.title(), draft.contentType(), draft.tone(), draft.summary(),
                List.copyOf(sections), draft.keyPoints(), draft.actionItems(),
                draft.fullText() + "\n\n## " + HEADING + "\n\n" + lines,
                draft.generatedBy(), draft.generatedAt(), draft.savedTo());
    }
}

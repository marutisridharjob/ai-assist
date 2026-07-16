package com.aiassist.draft;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateContentDrafterTest {

    private final TemplateContentDrafter drafter = new TemplateContentDrafter();

    private static final String NOTES = """
            The team reviewed the third-quarter roadmap and agreed the mobile release is the top priority.
            Performance testing showed a 40 percent improvement after the caching change.
            We need to finalize the security review before the end of the month.
            Sarah will follow up with the design team about the new onboarding flow.
            The budget for cloud infrastructure was approved.
            """;

    @Test
    void draftsDetailedDocumentWithSectionsKeyPointsAndActions() {
        Draft draft = drafter.draft("Q3 planning", NOTES, DraftOptions.defaults());

        assertThat(draft.title()).isEqualTo("Q3 planning");
        assertThat(draft.summary()).contains("Q3 planning");
        assertThat(draft.sections()).extracting(Draft.Section::heading)
                .contains("Overview", "Details");
        assertThat(draft.keyPoints()).isNotEmpty();
        assertThat(draft.actionItems())
                .anySatisfy(item -> assertThat(item).containsIgnoringCase("security review"))
                .anySatisfy(item -> assertThat(item).containsIgnoringCase("follow up"));
        assertThat(draft.fullText()).startsWith("# Q3 planning").contains("## Details");
        assertThat(draft.generatedBy()).isEqualTo("template");
    }

    @Test
    void meetingNotesAlwaysIncludeActionItemsSection() {
        Draft draft = drafter.draft("Sync", "We talked about the launch.",
                new DraftOptions(DraftOptions.ContentType.MEETING_NOTES, DraftOptions.Tone.CONCISE));

        assertThat(draft.sections()).extracting(Draft.Section::heading)
                .contains("Discussion", "Action items");
        assertThat(sectionBody(draft, "Action items")).contains("No action items were captured.");
    }

    @Test
    void emailDraftHasGreetingBodyAndClosing() {
        Draft draft = drafter.draft("Renewal reminder", NOTES,
                new DraftOptions(DraftOptions.ContentType.EMAIL, DraftOptions.Tone.FRIENDLY));

        assertThat(draft.sections()).extracting(Draft.Section::heading)
                .containsSubsequence("Greeting", "Body", "Closing");
        assertThat(sectionBody(draft, "Greeting")).isEqualTo("Hi there,");
    }

    @Test
    void usesFirstSentenceAsTitleWhenTopicIsUntitled() {
        Draft draft = drafter.draft("Untitled", "budget approved for next year. more detail here.",
                DraftOptions.defaults());

        assertThat(draft.title()).isEqualTo("Budget approved for next year");
    }

    @Test
    void blogPostHasIntroductionAndTakeaways() {
        Draft draft = drafter.draft("Faster builds", NOTES,
                new DraftOptions(DraftOptions.ContentType.BLOG_POST, DraftOptions.Tone.FRIENDLY));

        assertThat(draft.sections()).extracting(Draft.Section::heading)
                .containsSubsequence("Introduction", "Main content", "Key takeaways");
        assertThat(sectionBody(draft, "Introduction")).contains("faster builds");
    }

    @Test
    void summaryTypeLeadsWithKeyPoints() {
        Draft draft = drafter.draft("Digest", NOTES,
                new DraftOptions(DraftOptions.ContentType.SUMMARY, DraftOptions.Tone.CONCISE));

        assertThat(draft.sections().getFirst().heading()).isEqualTo("Key points");
        assertThat(draft.summary()).startsWith("Summary of \"Digest\":");
    }

    @Test
    void emptyTranscriptStillProducesAGracefulDraft() {
        Draft draft = drafter.draft("Untitled", "   ", DraftOptions.defaults());

        assertThat(draft.title()).isEqualTo("Draft");
        assertThat(draft.summary()).isEqualTo("No notes were captured for this draft.");
        assertThat(sectionBody(draft, "Details")).isEqualTo("No content was captured.");
        assertThat(draft.keyPoints()).isEmpty();
        assertThat(draft.actionItems()).isEmpty();
        assertThat(draft.fullText()).startsWith("# Draft");
    }

    @Test
    void existingPunctuationIsNotDoubled() {
        Draft draft = drafter.draft("Launch", "Ship it now! Are we ready? Yes we are.",
                new DraftOptions(DraftOptions.ContentType.SUMMARY, DraftOptions.Tone.CONCISE));

        assertThat(sectionBody(draft, "Details"))
                .contains("Ship it now!")
                .contains("Are we ready?")
                .doesNotContain("!.").doesNotContain("?.").doesNotContain("..");
    }

    @Test
    void keyPointsAreEmptyWhenEverySentenceIsTooShort() {
        Draft draft = drafter.draft("Brief", "Yes. No. Maybe so.", DraftOptions.defaults());

        assertThat(draft.keyPoints()).isEmpty();
        assertThat(draft.sections()).extracting(Draft.Section::heading).doesNotContain("Key points");
    }

    @Test
    void longTranscriptIsSplitIntoParagraphs() {
        StringBuilder notes = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            notes.append("This is detailed observation number ").append(i)
                    .append(" from the discussion. ");
        }
        Draft draft = drafter.draft("Long meeting", notes.toString(), DraftOptions.defaults());

        // Nine sentences grouped three per paragraph -> two paragraph breaks.
        assertThat(sectionBody(draft, "Details").split("\n\n")).hasSize(3);
    }

    @Test
    void detectsImperativeTasksAndIgnoresNonActorFuture() {
        Draft draft = drafter.draft("Sync",
                "Schedule the design review for Monday. It will rain tomorrow. "
                + "There will be a holiday next week.",
                new DraftOptions(DraftOptions.ContentType.MEETING_NOTES, DraftOptions.Tone.PROFESSIONAL));

        assertThat(draft.actionItems())
                .anySatisfy(item -> assertThat(item).containsIgnoringCase("Schedule the design review"))
                .noneSatisfy(item -> assertThat(item).containsIgnoringCase("rain"))
                .noneSatisfy(item -> assertThat(item).containsIgnoringCase("holiday"));
    }

    @Test
    void wordBoundaryStopsFalseActionMatches() {
        // "shoulder" contains "should" but must not count as an action.
        Draft draft = drafter.draft("Health", "He hurt his shoulder yesterday.",
                new DraftOptions(DraftOptions.ContentType.MEETING_NOTES, DraftOptions.Tone.CONCISE));

        assertThat(sectionBody(draft, "Action items")).isEqualTo("No action items were captured.");
    }

    @Test
    void dropsFillerAndDuplicateLines() {
        Draft draft = drafter.draft("Standup",
                "Okay. Yeah. The API latency dropped by 30 percent. "
                + "The API latency dropped by 30 percent. Right. Cool.",
                new DraftOptions(DraftOptions.ContentType.SUMMARY, DraftOptions.Tone.CONCISE));

        assertThat(draft.keyPoints()).hasSize(1);
        assertThat(draft.keyPoints().getFirst()).containsIgnoringCase("latency");
    }

    @Test
    void ranksSentencesWithNumbersAndDecisionsAbovePlainOnes() {
        Draft draft = drafter.draft("Review",
                "Someone opened the window. The board approved a 2 million dollar budget increase. "
                + "The coffee was cold.",
                new DraftOptions(DraftOptions.ContentType.SUMMARY, DraftOptions.Tone.CONCISE));

        assertThat(draft.keyPoints().getFirst()).containsIgnoringCase("budget");
    }

    private String sectionBody(Draft draft, String heading) {
        return draft.sections().stream()
                .filter(s -> s.heading().equals(heading))
                .map(Draft.Section::body)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing section " + heading
                        + " in " + List.copyOf(draft.sections())));
    }
}

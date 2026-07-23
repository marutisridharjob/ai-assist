package com.aiassist.draft;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These tests assert the specific, deterministic behaviour of the offline
 * rule-based rewriter (TemplateContentDrafter/TextRewriteService), so the
 * real LocalLlmService must never actually find a model — otherwise, on a
 * machine that has genuinely set up ai-assist with a real GGUF model in
 * ~/Documents/ai-assist/models, the LLM-first path in StyleRewriteService
 * would use that model instead, producing different (if equally valid)
 * wording than these hardcoded assertions expect. user.home is pointed at an
 * empty temp dir for the duration of every test so results are independent
 * of whoever runs them.
 */
class StyleRewriteServiceTest {

    @TempDir
    Path isolatedHome;
    private String originalHome;

    @BeforeEach
    void isolateFromAnyRealLocalModel() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
    }

    @AfterEach
    void restoreRealHome() {
        System.setProperty("user.home", originalHome);
    }

    private final StyleRewriteService service = new StyleRewriteService(
            new TextRewriteService(new TemplateContentDrafter()),
            new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                    .getBeanProvider(OllamaStyleRewriter.class),
            new TemplateContentDrafter(),
            new LocalLlmService());

    private static final String TEXT =
            "i think we should update the docs. don't forget the release notes. "
            + "maybe you could get the team to help with this.";

    @Test
    void doesNotSummarizeNearEmptyInput() {
        // A tiny model must never be handed one or two stray words to "summarize".
        assertThat(service.summarizeMeeting("", null)).isEmpty();
        assertThat(service.summarizeMeeting("   ", null)).isEmpty();
        assertThat(service.summarizeMeeting("the", null)).isEqualTo("Not enough was captured to summarize.");
        assertThat(service.summarizeMeeting("okay great sure", null))
                .isEqualTo("Not enough was captured to summarize.");
        assertThat(StyleRewriteService.wordCount("hello there friend")).isEqualTo(3);
        assertThat(StyleRewriteService.wordCount("  ")).isZero();
    }

    @Test
    void everyStyleProducesOutput() {
        for (StyleRewriteService.Style style : StyleRewriteService.Style.values()) {
            assertThat(service.draft(TEXT, style))
                    .as("style %s", style)
                    .isNotBlank();
        }
    }

    @Test
    void formalExpandsContractionsAndFormalizesWords() {
        String result = service.draft(TEXT, StyleRewriteService.Style.FORMAL);
        assertThat(result)
                .contains("Do not forget")
                .doesNotContain("don't")
                .contains("assist")
                .doesNotContain("!");
    }

    @Test
    void commandingStripsHedgesAndGoesImperative() {
        String result = service.draft(TEXT, StyleRewriteService.Style.COMMANDING);
        assertThat(result)
                .doesNotContainIgnoringCase("maybe")
                .doesNotContainIgnoringCase("i think")
                .contains("Update the docs");
    }

    @Test
    void casualContractsAndSimplifies() {
        String result = service.draft("We are ready. Do not hesitate to request assistance.",
                StyleRewriteService.Style.CASUAL);
        assertThat(result).contains("We're").contains("Don't").contains("ask").contains("help");
    }

    @Test
    void analyticalNumbersThePointsWithAConclusion() {
        String result = service.draft(TEXT, StyleRewriteService.Style.ANALYTICAL);
        assertThat(result).startsWith("Analysis:").contains("1. ").contains("Conclusion:");
    }

    @Test
    void assertiveDropsApologiesAndOwnsTheStatement() {
        String result = service.draft("Sorry for the delay. I think this plan is right. Hopefully it lands well.",
                StyleRewriteService.Style.ASSERTIVE);
        assertThat(result)
                .doesNotContainIgnoringCase("sorry")
                .contains("I am confident")
                .contains("I expect");
    }

    @Test
    void blankInputYieldsEmptyDraft() {
        assertThat(service.draft("  ", StyleRewriteService.Style.FORMAL)).isEmpty();
    }

    @Test
    void meetingSummaryIncludesActionItems() {
        String transcript = "We agreed to ship the beta on Friday. "
                + "John needs to update the docs. Please send the release notes to the team.";
        String summary = service.summarizeMeeting(transcript, null);
        assertThat(summary)
                .isNotBlank()
                .contains("Action items");
    }

    @Test
    void meetingSummaryOfBlankIsEmpty() {
        assertThat(service.summarizeMeeting("   ", null)).isEmpty();
    }
}

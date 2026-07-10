package com.aiassist.draft;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StyleRewriteServiceTest {

    private final StyleRewriteService service = new StyleRewriteService(
            new TextRewriteService(new TemplateContentDrafter()),
            new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                    .getBeanProvider(OllamaStyleRewriter.class));

    private static final String TEXT =
            "i think we should update the docs. don't forget the release notes. "
            + "maybe you could get the team to help with this.";

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
}

package com.aiassist.draft;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextRewriteServiceTest {

    private final TextRewriteService service = new TextRewriteService(new TemplateContentDrafter());

    @Test
    void grammarTidiesCapitalizationSpacingAndDuplicates() {
        String result = service.rewrite(
                "i think the the report is ready .it needs a update ,and i will send it!!",
                TextRewriteService.Mode.GRAMMAR);

        assertThat(result)
                .contains("I think the report is ready")
                .contains("an update")
                .doesNotContain("the the")
                .doesNotContain(" .")
                .doesNotContain("!!")
                .contains(", and I will send it!");
    }

    @Test
    void compactRemovesFillersAndWordyPhrases() {
        String result = service.rewrite(
                "Basically, we need to meet in order to discuss the plan, due to the fact that "
                + "the deadline moved. It is really very important.",
                TextRewriteService.Mode.COMPACT);

        assertThat(result)
                .doesNotContainIgnoringCase("basically")
                .doesNotContain("in order to")
                .contains("to discuss")
                .contains("because")
                .doesNotContainIgnoringCase("really")
                .doesNotContainIgnoringCase(" very ");
    }

    @Test
    void detailedProducesAStructuredDocument() {
        String result = service.rewrite(
                "The rollout finished on time. We need to update the documentation next week.",
                TextRewriteService.Mode.DETAILED);

        assertThat(result)
                .contains("# Detailed version")
                .contains("## Details")
                .contains("## Next steps");
    }

    @Test
    void blankInputYieldsEmptyOutput() {
        assertThat(service.rewrite("   ", TextRewriteService.Mode.GRAMMAR)).isEmpty();
        assertThat(service.rewrite(null, TextRewriteService.Mode.COMPACT)).isEmpty();
    }
}

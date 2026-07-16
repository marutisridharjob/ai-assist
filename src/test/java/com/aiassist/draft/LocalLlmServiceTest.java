package com.aiassist.draft;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalLlmServiceTest {

    @Test
    void unavailableAndSilentWithoutAModel() {
        // No .gguf next to the app: the service reports unavailable and
        // generate() returns empty so callers fall back to the offline rules.
        LocalLlmService service = new LocalLlmService();

        assertThat(service.findModel()).isEmpty();
        assertThat(service.isAvailable()).isFalse();
        assertThat(service.generate("summarise this", "some text", 128)).isEmpty();
    }

    @Test
    void generateOfBlankInputIsEmpty() {
        assertThat(new LocalLlmService().generate("do something", "   ", 128)).isEmpty();
    }
}

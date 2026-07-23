package com.aiassist.draft;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LocalLlmServiceTest {

    @Test
    void unavailableAndSilentWithoutAModel(@TempDir Path isolatedHome) {
        // findModel() searches the real ~/Documents/ai-assist/models among
        // other places, so on a machine that has actually set up ai-assist for
        // real use, a genuine model lives there and would be found instead of
        // "nothing". Point user.home at an empty temp dir for the duration of
        // this test so the result is independent of whoever runs it.
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
        try {
            // No .gguf next to the app: the service reports unavailable and
            // generate() returns empty so callers fall back to the offline rules.
            LocalLlmService service = new LocalLlmService();

            assertThat(service.findModel()).isEmpty();
            assertThat(service.isAvailable()).isFalse();
            assertThat(service.generate("summarise this", "some text", 128)).isEmpty();
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void generateOfBlankInputIsEmpty() {
        assertThat(new LocalLlmService().generate("do something", "   ", 128)).isEmpty();
    }
}

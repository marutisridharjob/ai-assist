package com.aiassist.audio;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WhisperTranscriberTest {

    @TempDir
    Path tempDir;

    @Test
    void convertsPcmToNormalizedFloat() throws Exception {
        // Samples 0, 32767 (max), -32768 (min) as 16-bit LE.
        byte[] pcm = {0, 0, (byte) 0xFF, 0x7F, 0x00, (byte) 0x80};
        Path file = tempDir.resolve("a.pcm");
        Files.write(file, pcm);

        float[] samples = WhisperTranscriber.readPcmAsFloat(file);

        assertThat(samples).hasSize(3);
        assertThat(samples[0]).isEqualTo(0f);
        assertThat(samples[1]).isCloseTo(1f, within(0.001f));
        assertThat(samples[2]).isCloseTo(-1f, within(0.001f));
    }

    @Test
    void flagsAShortWordSpanningALongQuietSegmentAsHallucinated() {
        // "the" hallucinated out of several seconds of silence/near-silence.
        assertThat(WhisperTranscriber.isLikelyHallucinatedFiller("the", 6.0)).isTrue();
        assertThat(WhisperTranscriber.isLikelyHallucinatedFiller("you", 5.0)).isTrue();
    }

    @Test
    void doesNotFlagARealQuickReplyOrALongerSpokenSegment() {
        // A real one-word reply is spoken quickly, not stretched over several seconds.
        assertThat(WhisperTranscriber.isLikelyHallucinatedFiller("Okay.", 0.6)).isFalse();
        // A long segment with substantial text is genuine speech, not a filler hallucination.
        assertThat(WhisperTranscriber.isLikelyHallucinatedFiller(
                "Let's push the release to next Friday and follow up with QA.", 6.0)).isFalse();
    }

    @Test
    void unavailableWithoutAModel(@TempDir Path isolatedHome) {
        // findModel() searches the real ~/Documents/ai-assist/models among other
        // places, so on a machine that has actually set up ai-assist for real
        // use, a genuine model lives there and would be found instead of
        // "nothing". Point user.home at an empty temp dir for the duration of
        // this test so the result is independent of whoever runs it.
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
        try {
            WhisperTranscriber transcriber = new WhisperTranscriber();
            assertThat(transcriber.findModel()).isEmpty();
            assertThat(transcriber.isAvailable()).isFalse();
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}

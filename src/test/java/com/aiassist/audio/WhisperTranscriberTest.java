package com.aiassist.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void collapsesAStuckDecoderLoopOfThreeOrMoreIdenticalSegmentsToOne() {
        List<WhisperTranscriber.Segment> looped = List.of(
                new WhisperTranscriber.Segment(0.0, "I don't know."),
                new WhisperTranscriber.Segment(1.0, "I don't know."),
                new WhisperTranscriber.Segment(2.0, "I don't know."),
                new WhisperTranscriber.Segment(3.0, "I don't know."));

        List<WhisperTranscriber.Segment> result = WhisperTranscriber.dropRepeatedFillerLoops(looped);

        assertThat(result).containsExactly(looped.get(0));
    }

    @Test
    void keepsARealDoubleRepeatUntouched() {
        // A genuine double ("no, no") is common in real speech; only 3+ in a
        // row is treated as a stuck-decoder artifact.
        List<WhisperTranscriber.Segment> segments = List.of(
                new WhisperTranscriber.Segment(0.0, "No, no."),
                new WhisperTranscriber.Segment(1.0, "No, no."));

        List<WhisperTranscriber.Segment> result = WhisperTranscriber.dropRepeatedFillerLoops(segments);

        assertThat(result).containsExactlyElementsOf(segments);
    }

    @Test
    void keepsDistinctSegmentsAroundACollapsedLoop() {
        List<WhisperTranscriber.Segment> segments = List.of(
                new WhisperTranscriber.Segment(0.0, "Let's ship on Friday."),
                new WhisperTranscriber.Segment(1.0, "Yeah."),
                new WhisperTranscriber.Segment(2.0, "Yeah."),
                new WhisperTranscriber.Segment(3.0, "Yeah."),
                new WhisperTranscriber.Segment(4.0, "Sounds good."));

        List<WhisperTranscriber.Segment> result = WhisperTranscriber.dropRepeatedFillerLoops(segments);

        assertThat(result).extracting(WhisperTranscriber.Segment::text)
                .containsExactly("Let's ship on Friday.", "Yeah.", "Sounds good.");
    }

    @Test
    void findModelPrefersTheMostAccurateModelEvenWhenAWorseOneSortsFirstAlphabetically(
            @TempDir Path isolatedHome) throws Exception {
        // "ggml-base.bin" sorts before "ggml-large-v3.bin" alphabetically
        // (b < l), so a plain alphabetical pick would keep using the small,
        // less accurate model even after a better one was added alongside
        // it — this is exactly what findModel() must not do, since it backs
        // the accuracy-critical saved-notes pass.
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
        try {
            Path modelsDir = isolatedHome.resolve("ai-assist").resolve("models");
            Files.createDirectories(modelsDir);
            Files.createFile(modelsDir.resolve("ggml-base.bin"));
            Files.createFile(modelsDir.resolve("ggml-large-v3.bin"));

            WhisperTranscriber transcriber = new WhisperTranscriber();

            assertThat(transcriber.findModel()).contains(modelsDir.resolve("ggml-large-v3.bin"));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void findFastModelStillPrefersBaseOverTinyWhenBothArePresent(@TempDir Path isolatedHome) throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
        try {
            Path modelsDir = isolatedHome.resolve("ai-assist").resolve("models");
            Files.createDirectories(modelsDir);
            Files.createFile(modelsDir.resolve("ggml-tiny.bin"));
            Files.createFile(modelsDir.resolve("ggml-base.bin"));

            WhisperTranscriber transcriber = new WhisperTranscriber();

            assertThat(transcriber.findFastModel()).contains(modelsDir.resolve("ggml-base.bin"));
        } finally {
            System.setProperty("user.home", originalHome);
        }
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

    @Test
    void findFastModelOnlyMatchesTinyOrBaseGgmlFiles(@TempDir Path isolatedHome) throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
        try {
            Path modelsDir = isolatedHome.resolve("ai-assist").resolve("models");
            Files.createDirectories(modelsDir);
            // A big, accurate model for the saved-notes pass — must NOT be
            // picked for live captions, it's far too slow to keep up live.
            Files.createFile(modelsDir.resolve("ggml-medium.bin"));

            WhisperTranscriber transcriber = new WhisperTranscriber();
            assertThat(transcriber.findFastModel()).isEmpty();
            assertThat(transcriber.isFastModelAvailable()).isFalse();
            // findModel() (the accurate/final pass) still finds the medium model.
            assertThat(transcriber.findModel()).isPresent();

            Files.createFile(modelsDir.resolve("ggml-tiny.en.bin"));
            assertThat(transcriber.findFastModel())
                    .contains(modelsDir.resolve("ggml-tiny.en.bin"));
            assertThat(transcriber.isFastModelAvailable()).isTrue();
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}

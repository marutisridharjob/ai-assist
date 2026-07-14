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
    void unavailableWithoutAModel() {
        // No ggml-*.bin in the working dir, so Whisper is not available and
        // transcription returns an empty list rather than failing.
        WhisperTranscriber transcriber = new WhisperTranscriber();
        assertThat(transcriber.findModel()).isEmpty();
        assertThat(transcriber.isAvailable()).isFalse();
    }
}

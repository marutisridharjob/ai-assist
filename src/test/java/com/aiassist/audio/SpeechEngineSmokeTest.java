package com.aiassist.audio;

import java.nio.file.Path;

import com.aiassist.config.TranscriptionProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real native speech engine through our lazy JNA binding:
 * extracts the embedded model, loads it, streams audio through a recognizer,
 * and reads results. Guards against the class of failure seen on macOS with
 * the vosk-java wrapper (eager registration of symbols the native library
 * doesn't export) — our binding must never touch symbols we don't call.
 */
class SpeechEngineSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsEmbeddedModelAndRunsRecognizerEndToEnd() throws Exception {
        TranscriptionProperties props = new TranscriptionProperties(
                tempDir.toString(), null, "vosk-model-small-en-us-0.15", 16000f, null, false);
        Path modelDir = new VoskModelManager(props).ensureModel();

        try (SpeechModel model = new SpeechModel(modelDir.toString());
             SpeechRecognizer recognizer = new SpeechRecognizer(model, 16000f)) {
            byte[] second_of_silence = new byte[32_000];
            recognizer.acceptWaveform(second_of_silence, second_of_silence.length);
            String json = recognizer.finalResult();

            assertThat(json).contains("\"text\"");
        }
    }
}

package com.aiassist.audio;

import java.io.InputStream;
import java.nio.file.Path;

import com.aiassist.config.TranscriptionProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the real native speech engine through our lazy JNA binding when
 * a model is available on the test classpath ({@code test-vosk-model.zip}) —
 * otherwise it is skipped, since the project ships no model. Guards against
 * the failure seen on macOS with the vosk-java wrapper (eager registration
 * of symbols the native library doesn't export): our binding must never
 * touch symbols we don't call.
 */
class SpeechEngineSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsModelAndRunsRecognizerEndToEnd() throws Exception {
        assumeTrue(getClass().getResource("/test-vosk-model.zip") != null,
                "no test model on the classpath — drop test-vosk-model.zip in src/test/resources to run");

        TranscriptionProperties props = new TranscriptionProperties(
                tempDir.toString(), null, "vosk-model-small-en-us-0.15", 16000f, null, false);
        VoskModelManager manager = new VoskModelManager(props) {
            @Override
            protected InputStream embeddedModelZip() {
                return getClass().getResourceAsStream("/test-vosk-model.zip");
            }
        };
        Path modelDir = manager.ensureModel();

        try (SpeechModel model = new SpeechModel(modelDir.toString());
             SpeechRecognizer recognizer = new SpeechRecognizer(model, 16000f)) {
            byte[] second_of_silence = new byte[32_000];
            recognizer.acceptWaveform(second_of_silence, second_of_silence.length);
            String json = recognizer.finalResult();

            assertThat(json).contains("\"text\"");
        }
    }
}

package com.aiassist.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for live meeting transcription. The Vosk model is loaded from
 * local disk; the app never fetches anything at runtime unless
 * {@code allowDownload} is explicitly enabled (off by default so the app is
 * fully offline).
 */
@ConfigurationProperties(prefix = "ai-assist.transcription")
public record TranscriptionProperties(String modelDir, String modelUrl, String modelName,
                                      float sampleRate, String preferredDevice,
                                      boolean allowDownload) {

    public TranscriptionProperties {
        if (modelDir == null || modelDir.isBlank()) {
            // OS-managed temp space where the user-supplied model is unpacked.
            // No model ships with the app (to keep the download small); the user
            // places it themselves — see the Settings tab -> Instructions.
            modelDir = Path.of(System.getProperty("java.io.tmpdir"), "ai-assist", "models").toString();
        }
        if (modelName == null || modelName.isBlank()) {
            modelName = "vosk-model-small-en-us-0.15";
        }
        if (modelUrl == null || modelUrl.isBlank()) {
            modelUrl = "https://alphacephei.com/vosk/models/" + modelName + ".zip";
        }
        if (sampleRate <= 0) {
            sampleRate = 16000f;
        }
    }
}

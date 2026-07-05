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
            modelDir = Path.of(System.getProperty("user.home"), ".ai-assist", "models").toString();
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

package com.aiassist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for meeting-audio capture and transcription. Transcription runs
 * locally with Whisper; the app never uses the network at runtime.
 */
@ConfigurationProperties(prefix = "ai-assist.transcription")
public record TranscriptionProperties(String preferredDevice) {

    public TranscriptionProperties {
        if (preferredDevice == null) {
            preferredDevice = "";
        }
    }
}

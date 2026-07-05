package com.aiassist.config;

import com.aiassist.draft.DraftOptions;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "Double-click and go" behaviour: when the app starts it can immediately
 * begin capturing audio, open the browser UI, and keep re-drafting notes
 * from whatever it has heard so far.
 */
@ConfigurationProperties(prefix = "ai-assist.auto")
public record AutoPilotProperties(boolean startCapture,
                                  boolean openBrowser,
                                  int draftIntervalSeconds,
                                  DraftOptions.ContentType contentType,
                                  DraftOptions.Tone tone) {

    public AutoPilotProperties {
        if (draftIntervalSeconds <= 0) {
            draftIntervalSeconds = 30;
        }
        if (contentType == null) {
            contentType = DraftOptions.ContentType.MEETING_NOTES;
        }
        if (tone == null) {
            tone = DraftOptions.Tone.PROFESSIONAL;
        }
    }
}

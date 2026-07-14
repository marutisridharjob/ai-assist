package com.aiassist.audio;

import com.aiassist.config.AutoPilotProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Optional hands-free startup: when configured, begins capturing as soon as
 * the app launches. No AI runs during the meeting — the audio is recorded and
 * only transcribed (and, on demand, drafted) after Stop.
 */
@Service
public class AutoPilot {

    private static final Logger log = LoggerFactory.getLogger(AutoPilot.class);

    private final AutoPilotProperties properties;
    private final LiveTranscriptionService liveTranscription;

    public AutoPilot(AutoPilotProperties properties, LiveTranscriptionService liveTranscription) {
        this.properties = properties;
        this.liveTranscription = liveTranscription;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!properties.startCapture()) {
            return;
        }
        try {
            LiveTranscriptionService.Status status = liveTranscription.start(null, null);
            log.info("Auto-started meeting capture on {} (session {})",
                    status.devices(), status.sessionId());
        } catch (Exception e) {
            log.warn("Could not auto-start audio capture: {}. Use the window or POST /api/live/start to retry.",
                    e.getMessage());
        }
    }
}

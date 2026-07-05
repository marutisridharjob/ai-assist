package com.aiassist.audio;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.aiassist.config.AutoPilotProperties;
import com.aiassist.draft.ContentDrafter;
import com.aiassist.draft.Draft;
import com.aiassist.draft.DraftOptions;
import com.aiassist.listen.ListeningSession;
import com.aiassist.listen.SessionStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Makes the app hands-free: on startup it begins capturing audio (preferring
 * a loopback device so an active Webex/Teams meeting is heard), opens the
 * browser UI, and re-drafts the notes on a rolling interval so a detailed
 * draft is always ready — no clicks required.
 */
@Service
public class AutoPilot {

    private static final Logger log = LoggerFactory.getLogger(AutoPilot.class);

    private final AutoPilotProperties properties;
    private final LiveTranscriptionService liveTranscription;
    private final AudioDeviceService audioDevices;
    private final SessionStore sessions;
    private final ContentDrafter drafter;
    private final Environment environment;

    private final AtomicReference<Draft> latestDraft = new AtomicReference<>();
    private final AtomicInteger draftedUtteranceCount = new AtomicInteger(0);

    public AutoPilot(AutoPilotProperties properties, LiveTranscriptionService liveTranscription,
                     AudioDeviceService audioDevices, SessionStore sessions,
                     ContentDrafter drafter, Environment environment) {
        this.properties = properties;
        this.liveTranscription = liveTranscription;
        this.audioDevices = audioDevices;
        this.sessions = sessions;
        this.drafter = drafter;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (properties.startCapture()) {
            startCapture();
        }
        if (properties.openBrowser()) {
            openBrowser();
        }
    }

    private void startCapture() {
        try {
            String device = pickDevice();
            LiveTranscriptionService.Status status = liveTranscription.start(device, null);
            log.info("Auto-started meeting capture on device '{}' (session {})",
                    device == null || device.isBlank() ? "(default)" : device, status.sessionId());
        } catch (Exception e) {
            log.warn("Could not auto-start audio capture: {}. Use the UI or POST /api/live/start to retry.",
                    e.getMessage());
        }
    }

    /** Prefer a loopback device (carries meeting audio); fall back to the default input. */
    private String pickDevice() {
        List<AudioDeviceService.AudioDevice> devices =
                audioDevices.listCaptureDevices(liveTranscription.captureFormat());
        return devices.stream()
                .filter(AudioDeviceService.AudioDevice::likelyLoopback)
                .map(AudioDeviceService.AudioDevice::name)
                .findFirst()
                .orElse(null);
    }

    private void openBrowser() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        URI ui = URI.create("http://localhost:" + port + "/");
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(ui);
                return;
            }
        } catch (Throwable e) {
            // AWT can throw Errors (e.g. AWTError when no display is reachable);
            // opening the browser is best-effort and must never break startup.
            log.debug("Desktop browse failed: {}", e.getMessage());
        }
        log.info("Open {} in your browser to see live notes", ui);
    }

    /** Re-draft whenever new speech has been captured since the last draft. */
    @Scheduled(fixedDelayString = "${ai-assist.auto.draft-interval-seconds:30}000")
    public void autoDraft() {
        LiveTranscriptionService.Status status = liveTranscription.status();
        if (status.sessionId() == null) {
            return;
        }
        ListeningSession session;
        try {
            session = sessions.get(status.sessionId());
        } catch (Exception e) {
            return;
        }
        int count = session.utterances().size();
        if (count == 0 || count == draftedUtteranceCount.get()) {
            return;
        }
        // Interim drafts live in memory only; the notes file is written once,
        // when the user marks the meeting as ended.
        Draft draft = drafter.draft(session.topic(), session.transcript(),
                new DraftOptions(properties.contentType(), properties.tone()));
        latestDraft.set(draft);
        draftedUtteranceCount.set(count);
        log.info("Auto-drafted interim notes from {} captured utterances", count);
    }

    public Draft latestDraft() {
        return latestDraft.get();
    }
}

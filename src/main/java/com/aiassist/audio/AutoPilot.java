package com.aiassist.audio;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Makes the app hands-free: on startup it begins capturing from the
 * microphone and any OS loopback device (so an active Webex/Teams meeting is
 * heard too), and keeps re-drafting interim notes in memory on a rolling
 * interval. The notes file is written only when the user presses Stop.
 */
@Service
public class AutoPilot {

    private static final Logger log = LoggerFactory.getLogger(AutoPilot.class);

    private final AutoPilotProperties properties;
    private final LiveTranscriptionService liveTranscription;
    private final SessionStore sessions;
    private final ContentDrafter drafter;

    private final AtomicReference<Draft> latestDraft = new AtomicReference<>();
    private final AtomicInteger draftedUtteranceCount = new AtomicInteger(0);

    public AutoPilot(AutoPilotProperties properties, LiveTranscriptionService liveTranscription,
                     SessionStore sessions, ContentDrafter drafter) {
        this.properties = properties;
        this.liveTranscription = liveTranscription;
        this.sessions = sessions;
        this.drafter = drafter;
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

    /** Re-draft in memory whenever new speech has been captured since the last cycle. */
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
        // The attributed transcript rides along so the running draft also
        // shows which source ([mic] you / [meeting] others) said what.
        Draft draft = com.aiassist.draft.AttributedTranscript.appendTo(
                drafter.draft(session.topic(), session.transcript(),
                        new DraftOptions(properties.contentType(), properties.tone())),
                session.utterances());
        latestDraft.set(draft);
        draftedUtteranceCount.set(count);
        log.info("Auto-drafted interim notes from {} captured utterances", count);
    }

    public Draft latestDraft() {
        return latestDraft.get();
    }
}

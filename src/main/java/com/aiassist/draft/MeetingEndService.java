package com.aiassist.draft;

import java.nio.file.Path;

import com.aiassist.audio.LiveTranscriptionService;
import com.aiassist.config.AutoPilotProperties;
import com.aiassist.listen.ListeningSession;
import com.aiassist.listen.SessionStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ends a meeting: stops live capture if it feeds this session, locks the
 * session against further input, drafts the complete end-to-end transcript,
 * and only then writes the timestamped notes file. Interim drafts shown
 * during the meeting are never persisted — the file on disk is always the
 * full final version.
 */
@Service
public class MeetingEndService {

    private static final Logger log = LoggerFactory.getLogger(MeetingEndService.class);

    private final SessionStore sessions;
    private final LiveTranscriptionService liveTranscription;
    private final ContentDrafter drafter;
    private final DraftFileWriter fileWriter;
    private final AutoPilotProperties autoPilotProperties;

    public MeetingEndService(SessionStore sessions, LiveTranscriptionService liveTranscription,
                             ContentDrafter drafter, DraftFileWriter fileWriter,
                             AutoPilotProperties autoPilotProperties) {
        this.sessions = sessions;
        this.liveTranscription = liveTranscription;
        this.drafter = drafter;
        this.fileWriter = fileWriter;
        this.autoPilotProperties = autoPilotProperties;
    }

    public Draft endMeeting(String sessionId, DraftOptions options) {
        ListeningSession session = sessions.get(sessionId);

        // Stop the recognizer first so its final flushed phrase still lands
        // in the session before we lock it.
        if (sessionId.equals(liveTranscription.status().sessionId())) {
            liveTranscription.stop();
        }
        session.end();

        String transcript = session.transcript();
        if (transcript.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Meeting " + sessionId + " ended but nothing was captured, so there is nothing to save");
        }
        if (options == null) {
            options = new DraftOptions(autoPilotProperties.contentType(), autoPilotProperties.tone());
        }
        Draft draft = drafter.draft(session.topic(), transcript, options);
        Path saved = fileWriter.save(draft);
        log.info("Meeting {} ended with {} utterances; notes saved to {}",
                sessionId, session.utterances().size(), saved);
        return saved == null ? draft : draft.withSavedTo(saved.toString());
    }

    /** Ends whichever meeting the live capture is currently feeding. */
    public Draft endCurrentLiveMeeting(DraftOptions options) {
        String sessionId = liveTranscription.status().sessionId();
        if (sessionId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No live meeting is in progress");
        }
        return endMeeting(sessionId, options);
    }
}

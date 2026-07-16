package com.aiassist.draft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.aiassist.audio.LiveTranscriptionService;
import com.aiassist.audio.WhisperTranscriber;
import com.aiassist.listen.ListeningSession;
import com.aiassist.listen.SessionStore;
import com.aiassist.listen.Utterance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ends a meeting: stops live capture, locks the session, converts the
 * recorded voice into text with Whisper (the accurate complete-conversation
 * transcription; falls back to the live captions when Whisper or a recording
 * is unavailable), and saves that verbatim transcript. No AI drafting or
 * summarizing happens here — that is applied only on demand from the Editor
 * and Compose tabs.
 */
@Service
public class MeetingEndService {

    private static final Logger log = LoggerFactory.getLogger(MeetingEndService.class);

    private final SessionStore sessions;
    private final LiveTranscriptionService liveTranscription;
    private final WhisperTranscriber whisper;
    private final ContentDrafter drafter;
    private final DraftFileWriter fileWriter;

    public MeetingEndService(SessionStore sessions, LiveTranscriptionService liveTranscription,
                             WhisperTranscriber whisper, ContentDrafter drafter,
                             DraftFileWriter fileWriter) {
        this.sessions = sessions;
        this.liveTranscription = liveTranscription;
        this.whisper = whisper;
        this.drafter = drafter;
        this.fileWriter = fileWriter;
    }

    public Draft endMeeting(String sessionId, DraftOptions options) {
        ListeningSession session = sessions.get(sessionId);

        // Stop capture first so the recording is complete before we read it.
        if (sessionId.equals(liveTranscription.status().sessionId())) {
            liveTranscription.stop();
        }
        session.end();

        List<Utterance> utterances = transcribeRecordingOrLive(session);
        String transcript = utterances.stream().map(Utterance::text)
                .reduce((a, b) -> a + "\n" + b).orElse("");
        if (transcript.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Meeting " + sessionId + " ended but nothing was captured, so there is nothing to save");
        }
        // Saved file leads with the summary and action points, then the full
        // verbatim transcript. Drop the drafter's "Discussion" section — it just
        // reflows the same words the appended transcript already carries.
        Draft base = drafter.draft(session.topic(), transcript,
                new DraftOptions(DraftOptions.ContentType.MEETING_NOTES, DraftOptions.Tone.PROFESSIONAL));
        List<Draft.Section> topSections = base.sections().stream()
                .filter(s -> !"Discussion".equals(s.heading()))
                .toList();
        Draft summary = new Draft(base.title(), base.contentType(), base.tone(),
                base.summary(), topSections, base.keyPoints(), base.actionItems(),
                base.fullText(), base.generatedBy(), base.generatedAt(), base.savedTo());
        Draft draft = AttributedTranscript.appendTo(summary, utterances);
        Path saved = fileWriter.save(draft);
        log.info("Meeting {} ended with {} utterances; notes saved to {}",
                sessionId, utterances.size(), saved);
        return saved == null ? draft : draft.withSavedTo(saved.toString());
    }

    /**
     * Whisper transcription of the recorded audio (accurate, complete),
     * ordered chronologically across sources; falls back to the live captions
     * when Whisper or the recording is unavailable. Deletes the recording.
     */
    private List<Utterance> transcribeRecordingOrLive(ListeningSession session) {
        var files = liveTranscription.finishRecording(session.id());
        if (files.isEmpty() || !whisper.isAvailable()) {
            files.values().forEach(this::deleteQuietly);
            return session.utterances();
        }
        record Timed(double at, String speaker, String text) {
        }
        List<Timed> collected = new ArrayList<>();
        try {
            for (var entry : files.entrySet()) {
                for (WhisperTranscriber.Segment seg : whisper.transcribe(entry.getValue())) {
                    collected.add(new Timed(seg.start(), entry.getKey(), seg.text()));
                }
            }
        } finally {
            files.values().forEach(this::deleteQuietly);
        }
        if (collected.isEmpty()) {
            return session.utterances();
        }
        collected.sort(java.util.Comparator.comparingDouble(Timed::at));
        List<Utterance> result = new ArrayList<>();
        int seq = 1;
        for (Timed t : collected) {
            result.add(new Utterance(seq++, t.text(), t.speaker(),
                    session.startedAt().plusMillis((long) (t.at() * 1000))));
        }
        log.info("Whisper transcribed meeting {} into {} segments", session.id(), result.size());
        return result;
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // temp file; OS cleans up
        }
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

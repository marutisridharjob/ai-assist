package com.aiassist.draft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
 * is unavailable), drafts the summary (the local LLM when installed, else the
 * offline rules — same path as the Meeting/Editor tabs' Apply), and saves the
 * notes file. Both the transcription and the summary are bounded-time: ending
 * a meeting must always finish and save something, even if a native call
 * (Whisper/llama.cpp) is running unreasonably slowly on the machine's CPU.
 */
@Service
public class MeetingEndService {

    private static final Logger log = LoggerFactory.getLogger(MeetingEndService.class);

    /**
     * How long to wait for Whisper/the LLM before giving up and using the
     * fallback (live captions / offline drafter) instead. Generous — a real
     * meeting recording can legitimately take minutes to transcribe/summarize
     * on a modest CPU — but bounded, so "saving the notes" can never block
     * forever: the abandoned call (not forcibly cancelled — a blocking native
     * call can't be interrupted safely) simply keeps running in the
     * background and its result is discarded.
     */
    private static final Duration TRANSCRIPTION_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration SUMMARY_TIMEOUT = Duration.ofMinutes(5);

    private static final ExecutorService FINISH_NOTES_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "finish-notes-worker");
        t.setDaemon(true);
        return t;
    });

    private final SessionStore sessions;
    private final LiveTranscriptionService liveTranscription;
    private final WhisperTranscriber whisper;
    private final StyleRewriteService styleRewrite;
    private final DraftFileWriter fileWriter;

    public MeetingEndService(SessionStore sessions, LiveTranscriptionService liveTranscription,
                             WhisperTranscriber whisper, StyleRewriteService styleRewrite,
                             DraftFileWriter fileWriter) {
        this.sessions = sessions;
        this.liveTranscription = liveTranscription;
        this.whisper = whisper;
        this.styleRewrite = styleRewrite;
        this.fileWriter = fileWriter;
    }

    /** The recording captured on Stop, to be transcribed and drafted later. */
    public record PendingNotes(String sessionId, Map<String, Path> recordings) {
    }

    /**
     * Fast, synchronous part of Stop: stops live capture, locks the session,
     * and grabs the recorded audio files. Returns immediately so the UI can be
     * ready for a new meeting while {@link #finishNotes} runs in the background.
     */
    public PendingNotes stopCapture(String sessionId) {
        ListeningSession session = sessions.get(sessionId);
        if (sessionId.equals(liveTranscription.status().sessionId())) {
            liveTranscription.stop();
        }
        session.end();
        // Detach the recording now so a new meeting's recorder can't clobber it.
        Map<String, Path> recordings = liveTranscription.finishRecording(sessionId);
        return new PendingNotes(sessionId, recordings);
    }

    /** Stops whichever meeting the live capture is currently feeding. */
    public PendingNotes stopCurrentCapture() {
        String sessionId = liveTranscription.status().sessionId();
        if (sessionId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No live meeting is in progress");
        }
        return stopCapture(sessionId);
    }

    /**
     * Slow, background part of Stop: transcribes the recording with Whisper,
     * drafts the summary + action points (via the local LLM when installed,
     * else the offline drafter), appends the full verbatim transcript, and
     * saves the notes file.
     */
    public Draft finishNotes(PendingNotes pending, DraftOptions options) {
        ListeningSession session = sessions.get(pending.sessionId());
        List<Utterance> utterances = runWithTimeout("Whisper transcription", TRANSCRIPTION_TIMEOUT,
                () -> transcribeRecordingOrLive(session, pending.recordings()), session::utterances);
        String transcript = utterances.stream().map(Utterance::text)
                .reduce((a, b) -> a + "\n" + b).orElse("");
        if (transcript.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Meeting " + pending.sessionId()
                    + " ended but nothing was captured, so there is nothing to save");
        }
        // Summary + action points: the same path as the Meeting tab's Apply,
        // so a dropped-in LLM writes them (falling back to the offline drafter).
        String summaryText = stripMarkdownHeadings(runWithTimeout("Meeting summary generation", SUMMARY_TIMEOUT,
                () -> styleRewrite.summarizeMeeting(transcript, null), () -> styleRewrite.offlineSummary(transcript)));
        Draft notes = new Draft(session.topic(), "MEETING_NOTES", "PROFESSIONAL", "",
                List.of(new Draft.Section("Summary", summaryText)),
                List.of(), List.of(), summaryText, "summary", Instant.now(), null);
        Draft draft = AttributedTranscript.appendTo(notes, utterances);
        Path saved = fileWriter.save(draft);
        log.info("Meeting {} finished with {} utterances; notes saved to {}",
                pending.sessionId(), utterances.size(), saved);
        return saved == null ? draft : draft.withSavedTo(saved.toString());
    }

    /**
     * Runs {@code task} on a background thread and waits up to {@code timeout}
     * for it; past that, logs and returns {@code fallback.get()} instead
     * without cancelling the task — a blocking native call (Whisper/llama.cpp)
     * can't be safely force-interrupted, so it's left to finish on its own in
     * the background and its result is simply discarded.
     */
    private <T> T runWithTimeout(String what, Duration timeout, Callable<T> task, Supplier<T> fallback) {
        var future = FINISH_NOTES_EXECUTOR.submit(task);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("{} did not finish within {}; using the fallback so the meeting still saves",
                    what, timeout);
            return fallback.get();
        } catch (Exception e) {
            log.warn("{} failed ({}); using the fallback so the meeting still saves", what, e.getMessage());
            return fallback.get();
        }
    }

    /** Synchronous end-to-end (used by the REST API): stop then finish. */
    public Draft endMeeting(String sessionId, DraftOptions options) {
        return finishNotes(stopCapture(sessionId), options);
    }

    /**
     * Ends the meeting without drafting or saving any notes: the captured
     * recording is simply discarded. Used when the user chooses "No" (do not
     * save) on Stop.
     */
    public void discardNotes(PendingNotes pending) {
        pending.recordings().values().forEach(this::deleteQuietly);
        log.info("Meeting {} ended without saving; recording discarded", pending.sessionId());
    }

    /** Markdown headings (# / ##) render badly in RTF; strip them to plain lines. */
    private static String stripMarkdownHeadings(String text) {
        if (text == null) {
            return "";
        }
        return text.lines()
                .map(l -> l.replaceFirst("^\\s*#{1,6}\\s*", ""))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Whisper transcription of the recorded audio (accurate, complete),
     * ordered chronologically across sources; falls back to the live captions
     * when Whisper or the recording is unavailable. Deletes the recording.
     */
    private List<Utterance> transcribeRecordingOrLive(ListeningSession session, Map<String, Path> files) {
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

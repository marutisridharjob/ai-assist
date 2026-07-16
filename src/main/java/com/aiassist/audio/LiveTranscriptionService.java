package com.aiassist.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.TargetDataLine;

import com.aiassist.config.TranscriptionProperties;
import com.aiassist.listen.ListeningSession;
import com.aiassist.listen.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Captures audio from several devices at once — the microphone and any OS
 * loopback device that carries what the computer is playing (an active
 * Webex/Teams/any-platform meeting) — and streams each through its own
 * offline Vosk recognizer into one listening session. Utterances are
 * labelled with their source ("mic" or "other"). Supports pause/resume
 * mid-meeting; a full stop is driven by the meeting-end flow.
 */
@Service
public class LiveTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(LiveTranscriptionService.class);
    private static final int BUFFER_BYTES = 4096;

    public enum State { IDLE, PREPARING, LISTENING, PAUSED, ERROR }

    public record Status(State state, String sessionId, List<String> devices, String detail) {
    }

    private final AudioDeviceService audioDevices;
    private final VoskModelManager modelManager;
    private final SessionStore sessions;
    private final TranscriptionProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<Status> status =
            new AtomicReference<>(new Status(State.IDLE, null, List.of(), null));
    private final List<CaptureWorker> workers = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile SpeechModel model;
    private volatile String loadedModelName;
    private volatile String requestedModelName;
    private volatile String modelNote;
    private volatile MeetingRecorder recorder;
    private volatile String recorderSessionId;

    public LiveTranscriptionService(AudioDeviceService audioDevices, VoskModelManager modelManager,
                                    SessionStore sessions, TranscriptionProperties properties) {
        this.audioDevices = audioDevices;
        this.modelManager = modelManager;
        this.sessions = sessions;
        this.properties = properties;
    }

    /**
     * Loads the active speech model into memory (and the optional speaker
     * model). Called on start, and preloaded at app launch so pressing Start
     * begins drafting immediately. Falls back to the first available model
     * when the requested one can't load.
     */
    private synchronized void loadModelIfNeeded(String wanted) throws Exception {
        if (model == null || !wanted.equals(loadedModelName)) {
            long t0 = System.currentTimeMillis();
            if (model != null) {
                model.close();
                model = null;
            }
            try {
                model = new SpeechModel(modelManager.ensureModel(wanted).toString());
                loadedModelName = wanted;
                modelNote = null;
            } catch (Throwable loadFailure) {
                // A picked model that can't load (still unpacking, incomplete,
                // out of memory) must not kill the meeting: revert to the first
                // available model and say so.
                String fallback = modelManager.defaultModelName();
                if (fallback.equals(wanted)) {
                    throw loadFailure;
                }
                // Keep the user's saved choice (it may still be unpacking) so
                // the next Start retries it; only the in-memory model falls back.
                modelNote = "model \"" + wanted + "\" could not be loaded ("
                        + loadFailure.getMessage() + ") — using " + fallback;
                log.warn(modelNote);
                model = new SpeechModel(modelManager.ensureModel(fallback).toString());
                loadedModelName = fallback;
            }
            log.info("Speech model '{}' ready in {} ms", loadedModelName, System.currentTimeMillis() - t0);
        }
    }

    /**
     * Preloads the model at app launch so Start is instant. Best-effort:
     * waits (in the background) for a model to appear if the user is still
     * dropping/extracting one, then loads it. Never blocks startup.
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void preloadOnStartup() {
        Thread preloader = new Thread(() -> {
            // Warm the capture path once, in parallel with waiting for a model:
            // the native tap's one-time helper build and device enumeration
            // are the slow parts, so doing them now makes Start instant.
            try {
                NativeSystemAudioTap.isSupported();
                audioDevices.listCaptureDevices();
            } catch (Throwable e) {
                log.debug("Capture warm-up skipped: {}", e.getMessage());
            }
            for (int i = 0; i < 600 && model == null && !running; i++) {
                if (!modelManager.listAvailableModels().isEmpty()) {
                    try {
                        loadModelIfNeeded(activeModelName());
                        log.info("Model and audio capture ready — Start begins drafting immediately");
                    } catch (Throwable e) {
                        log.warn("Model preload failed ({}); it will load when you press Start",
                                e.getMessage());
                    }
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "capture-warmup");
        preloader.setDaemon(true);
        preloader.start();
    }

    /**
     * Starts capture on the given devices (auto-resolved to microphone +
     * loopback devices when null/empty) into the given session (created when
     * null). Returns immediately; model loading and recognition happen on
     * worker threads.
     */
    public synchronized Status start(List<String> deviceNames, String sessionId) {
        State current = status.get().state();
        if (current == State.LISTENING || current == State.PREPARING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Live transcription is already running for session " + status.get().sessionId());
        }
        ListeningSession session;
        if (sessionId == null || sessionId.isBlank()) {
            session = sessions.create("Live meeting notes");
        } else {
            session = sessions.get(sessionId);
        }
        // Record the meeting audio locally (one file per source), kept across
        // pause/resume, so Whisper can transcribe the whole conversation on Stop.
        if (recorder == null || !session.id().equals(recorderSessionId)) {
            try {
                recorder = new MeetingRecorder(session.id());
                recorderSessionId = session.id();
            } catch (java.io.IOException e) {
                log.warn("Local recording unavailable ({}); notes will use the live captions",
                        e.getMessage());
                recorder = null;
            }
        }

        running = true;
        status.set(new Status(State.PREPARING, session.id(), List.of("detecting audio sources…"),
                "Loading speech model and preparing audio capture"));

        Thread starter = new Thread(() -> {
            try {
                loadModelIfNeeded(activeModelName());
            } catch (Throwable e) {
                // Throwable, not Exception: native-library loading failures are
                // Errors, and swallowing them would leave the status stuck on
                // PREPARING forever with no explanation.
                log.error("Speech model unavailable", e);
                running = false;
                status.set(new Status(State.ERROR, session.id(), List.of(),
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                return;
            }
            if (!running) {
                return;
            }
            // Resolved here, off the caller's thread: the native tap helper may
            // need a one-time compile on first use.
            List<AudioDeviceService.DeviceSelection> selections = resolveSelections(deviceNames);
            List<String> deviceLabels = selections.stream()
                    .map(s -> s.displayName() + " [" + s.label() + "]")
                    .toList();
            status.updateAndGet(s -> s.state() == State.PREPARING
                    ? new Status(State.PREPARING, session.id(), deviceLabels,
                            "Opening audio sources — first time, the OS asks for permission "
                            + "(microphone, and 'System Audio Recording' for the native tap); "
                            + "approve to continue")
                    : s);
            for (AudioDeviceService.DeviceSelection selection : selections) {
                CaptureWorker worker = new CaptureWorker(session, selection, deviceLabels);
                workers.add(worker);
                Thread t = new Thread(worker, "capture-" + selection.label());
                t.setDaemon(true);
                worker.thread = t;
                t.start();
            }
        }, "live-capture-init");
        starter.setDaemon(true);
        starter.start();
        return status.get();
    }

    /**
     * The meeting source, best first: the native system-audio tap (direct
     * capture of what the computer plays — macOS 14.2+ / Windows) when its
     * helper is available, otherwise any loopback devices (Stereo Mix,
     * BlackHole, PulseAudio monitors). The microphone is always included.
     */
    private List<AudioDeviceService.DeviceSelection> resolveSelections(List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested.stream()
                    .map(n -> new AudioDeviceService.DeviceSelection(n, "other"))
                    .toList();
        }
        List<AudioDeviceService.DeviceSelection> auto =
                new ArrayList<>(audioDevices.resolveAutoDevices(properties.preferredDevice()));
        if (NativeSystemAudioTap.isSupported()) {
            auto.removeIf(s -> "other".equals(s.label()));
            auto.add(0, new AudioDeviceService.DeviceSelection(
                    NativeSystemAudioTap.SOURCE_NAME, "other", true));
        }
        return auto;
    }

    /** Pauses capture; the session stays open and {@link #resume()} continues it. */
    public synchronized Status pause() {
        Status current = status.get();
        if (current.state() != State.LISTENING && current.state() != State.PREPARING) {
            return current;
        }
        stopWorkers();
        Status paused = new Status(State.PAUSED, current.sessionId(), current.devices(), "Paused");
        status.set(paused);
        return paused;
    }

    /** Resumes a paused meeting on the same session. */
    public synchronized Status resume() {
        Status current = status.get();
        if (current.state() != State.PAUSED || current.sessionId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No paused meeting to resume");
        }
        return start(null, current.sessionId());
    }

    /** Fully stops capture (used by the meeting-end flow). */
    public synchronized Status stop() {
        Status current = status.get();
        stopWorkers();
        Status stopped = new Status(State.IDLE, current.sessionId(), current.devices(), "Stopped");
        status.set(stopped);
        return stopped;
    }

    public Status status() {
        return status.get();
    }

    /** Models the user can pick from (built-in default + ./models folder). */
    public List<String> availableModels() {
        return modelManager.listAvailableModels();
    }

    /** Kicks off unpacking of any model zips dropped while the app runs. */
    public void rescanModelZips() {
        modelManager.rescanDroppedZips();
    }

    private final java.util.prefs.Preferences prefs =
            java.util.prefs.Preferences.userNodeForPackage(LiveTranscriptionService.class);

    /**
     * The model to use: the in-session choice, else the one saved from a
     * previous run (persisted across restarts), else the first available.
     */
    public String activeModelName() {
        String requested = requestedModelName;
        if (requested == null) {
            requested = prefs.get("model", null);
        }
        return requested != null ? requested : modelManager.defaultModelName();
    }

    /** Models still unpacking from dropped zips (shown disabled in the UI). */
    public java.util.Set<String> unpackingModels() {
        return modelManager.unpackingNow();
    }

    /**
     * Closes the recording for the session and returns its per-source PCM
     * files for Whisper transcription (empty when nothing was recorded). The
     * caller deletes the files when done.
     */
    public java.util.Map<String, java.nio.file.Path> finishRecording(String sessionId) {
        MeetingRecorder rec = recorder;
        if (rec == null || !sessionId.equals(recorderSessionId)) {
            return java.util.Map.of();
        }
        recorder = null;
        recorderSessionId = null;
        return rec.finish();
    }

    /** Discards any active recording (e.g. meeting abandoned without saving). */
    public void discardRecording() {
        MeetingRecorder rec = recorder;
        if (rec != null) {
            rec.discard();
            recorder = null;
            recorderSessionId = null;
        }
    }

    /** One-line note about a model fallback, for the status line; null when none. */
    public String modelNote() {
        return modelNote;
    }

    /**
     * Switches the recognition model; a running meeting is briefly paused
     * and resumed on the same session with the new model loaded.
     */
    public synchronized Status selectModel(String name) {
        if (name == null || name.isBlank() || name.equals(activeModelName())) {
            return status.get();
        }
        Status before = status.get();
        boolean wasActive = before.state() == State.LISTENING || before.state() == State.PREPARING;
        stopWorkers();
        requestedModelName = name;
        prefs.put("model", name); // remembered across restarts until changed
        log.info("Speech model switched to '{}'", name);
        if (wasActive && before.sessionId() != null) {
            status.set(new Status(State.PAUSED, before.sessionId(), before.devices(),
                    "Switching model to " + name));
            return start(null, before.sessionId());
        }
        if (before.state() != State.PAUSED) {
            status.set(new Status(before.state() == State.ERROR ? State.ERROR : State.IDLE,
                    before.sessionId(), before.devices(), "Model set to " + name));
        }
        return status.get();
    }

    /** Live input level (0–100) per source label, e.g. {mic=42, meeting=0}. */
    public java.util.Map<String, Integer> levels() {
        java.util.Map<String, Integer> levels = new java.util.LinkedHashMap<>();
        for (CaptureWorker worker : workers) {
            levels.merge(worker.selection.label(), worker.level, Integer::max);
        }
        return levels;
    }

    /** In-progress (not yet final) words per source label — live captions. */
    public java.util.Map<String, String> partials() {
        java.util.Map<String, String> partials = new java.util.LinkedHashMap<>();
        for (CaptureWorker worker : workers) {
            String partial = worker.partialText;
            if (partial != null && !partial.isBlank()) {
                partials.put(worker.selection.label(), partial);
            }
        }
        return partials;
    }

    private void stopWorkers() {
        running = false;
        for (CaptureWorker worker : workers) {
            worker.closeLine();
        }
        for (CaptureWorker worker : workers) {
            worker.join(5000);
        }
        workers.clear();
    }

    private final class CaptureWorker implements Runnable {

        private final ListeningSession session;
        private final AudioDeviceService.DeviceSelection selection;
        private final List<String> deviceLabels;
        private volatile TargetDataLine line;
        private volatile Process tapProcess;
        private volatile int level;
        private volatile String partialText = "";
        // When the caption (partial) stops growing for this long, the phrase is
        // committed to the transcript ourselves — Vosk's own end-of-phrase
        // detection can keep a phrase open indefinitely on some inputs, leaving
        // the box empty while the live caption keeps scrolling.
        private static final long PARTIAL_FLUSH_MS = 900;
        private long lastPartialGrewAt;
        // Loudest sample seen (0-100) since the last committed phrase. A phrase
        // whose audio never rose above near-silence is dropped, so a quiet room
        // can't post a stray filler word like "the" when Pause flushes.
        private static final int MIN_SPEECH_LEVEL = 3;
        private volatile int phrasePeak;
        private Thread thread;

        private CaptureWorker(ListeningSession session, AudioDeviceService.DeviceSelection selection,
                              List<String> deviceLabels) {
            this.session = session;
            this.selection = selection;
            this.deviceLabels = deviceLabels;
        }

        @Override
        public void run() {
            try {
                if (selection.systemTap()) {
                    captureFromSystemTap();
                } else {
                    captureFromDevice();
                }
            } catch (Throwable e) {
                if (running) {
                    log.warn("Capture on '{}' failed: {}", selection.displayName(), e.getMessage());
                    String message = e.getClass().getSimpleName() + ": " + e.getMessage();
                    if (selection.systemTap()) {
                        // Don't fail the same way twice: the next start/resume
                        // uses loopback devices or the microphone instead.
                        NativeSystemAudioTap.disableAfterRuntimeFailure(message);
                        message += " — press Pause then Resume to switch to the fallback capture";
                    }
                    markFailed(message);
                }
            } finally {
                closeLine();
            }
        }

        /** Java Sound path: microphone or a loopback device. */
        private void captureFromDevice() throws Exception {
            line = audioDevices.openBestCaptureLine(selection.deviceName());
            AudioFormat format = line.getFormat();
            boolean stereo = format.getChannels() == 2;
            float deviceRate = format.getSampleRate();
            line.start();
            markListening();
            log.info("Capturing '{}' as [{}] at {} Hz {} → 16 kHz into session {}",
                    selection.displayName(), selection.label(),
                    (int) deviceRate, stereo ? "stereo" : "mono", session.id());
            // The model runs at 16 kHz; always recognize at 16 kHz for accuracy,
            // resampling whatever rate the OS granted.
            try (SpeechRecognizer recognizer = newRecognizer(16000f)) {
                byte[] buffer = new byte[BUFFER_BYTES * format.getFrameSize()];
                while (running) {
                    int n = line.read(buffer, 0, buffer.length);
                    if (n <= 0) {
                        continue;
                    }
                    int monoLen = stereo ? downmixToMono(buffer, n) : n;
                    level = peakPercent(buffer, monoLen);
                    byte[] pcm16k = resampleTo16k(buffer, monoLen, deviceRate);
                    feed(recognizer, pcm16k, pcm16k.length);
                }
                appendResult(recognizer.finalResult());
                partialText = "";
            }
        }

        /**
         * Native-helper path: reads what the computer is playing straight
         * from the OS (Core Audio tap on macOS, WASAPI loopback on Windows).
         */
        private void captureFromSystemTap() throws Exception {
            Process process = NativeSystemAudioTap.startHelper();
            tapProcess = process;
            Thread stderrDrain = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getErrorStream()))) {
                    String errorLine;
                    while ((errorLine = reader.readLine()) != null) {
                        log.warn("[system-tap] {}", errorLine);
                    }
                } catch (java.io.IOException ignored) {
                    // stream closes with the process
                }
            }, "system-tap-stderr");
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            java.io.InputStream pcm = new java.io.BufferedInputStream(process.getInputStream());
            float sampleRate = NativeSystemAudioTap.readHeader(pcm);
            markListening();
            log.info("Capturing system audio via native tap at {} Hz → 16 kHz into session {}",
                    (int) sampleRate, session.id());
            try (SpeechRecognizer recognizer = newRecognizer(16000f)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                while (running) {
                    int n = pcm.read(buffer, 0, buffer.length);
                    if (n < 0) {
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }
                    level = peakPercent(buffer, n);
                    byte[] pcm16k = resampleTo16k(buffer, n, sampleRate);
                    feed(recognizer, pcm16k, pcm16k.length);
                }
                appendResult(recognizer.finalResult());
                partialText = "";
            }
            if (running && !process.isAlive() && process.exitValue() != 0) {
                throw new java.io.IOException("system-audio helper exited with code "
                        + process.exitValue() + " (see [system-tap] log lines for the reason)");
            }
        }

        /**
         * Feeds 16 kHz mono PCM to the recognizer. A phrase is committed only
         * when the speaker pauses (Vosk's own end-of-phrase detection); until
         * then it grows as live-caption partial text and is never cut off.
         */
        private void feed(SpeechRecognizer recognizer, byte[] pcm, int length) {
            MeetingRecorder rec = recorder;
            if (rec != null) {
                rec.record(selection.label(), pcm, length);
            }
            if (level > phrasePeak) {
                phrasePeak = level; // track the loudest moment of this phrase
            }
            if (recognizer.acceptWaveform(pcm, length)) {
                appendResult(recognizer.result());
                partialText = "";
            } else {
                updatePartial(recognizer.partialResult());
                // Vosk hasn't closed the phrase, but if the caption has stopped
                // growing (a natural pause) commit it now so the box keeps up.
                if (!partialText.isBlank()
                        && System.currentTimeMillis() - lastPartialGrewAt > PARTIAL_FLUSH_MS) {
                    appendResult(recognizer.finalResult());
                    partialText = "";
                }
            }
        }

        /** Loudest sample in the buffer as 0–100, so the UI can show what this source hears. */
        private static int peakPercent(byte[] buffer, int length) {
            int peak = 0;
            for (int i = 0; i + 1 < length; i += 2) {
                int sample = Math.abs((short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF)));
                if (sample > peak) {
                    peak = sample;
                }
            }
            return peak * 100 / 32767;
        }

        private byte[] resampleTo16k(byte[] buffer, int length, float sourceRate) {
            return AudioResampler.to16k(buffer, length, sourceRate);
        }

        /** Averages 16-bit little-endian stereo frames into mono, in place. */
        private int downmixToMono(byte[] buffer, int length) {
            int frames = length / 4;
            for (int i = 0; i < frames; i++) {
                int left = (short) ((buffer[4 * i + 1] << 8) | (buffer[4 * i] & 0xFF));
                int right = (short) ((buffer[4 * i + 3] << 8) | (buffer[4 * i + 2] & 0xFF));
                int mono = (left + right) / 2;
                buffer[2 * i] = (byte) mono;
                buffer[2 * i + 1] = (byte) (mono >> 8);
            }
            return frames * 2;
        }

        private void markListening() {
            status.updateAndGet(s -> s.state() == State.PREPARING || s.state() == State.LISTENING
                    ? new Status(State.LISTENING, session.id(), deviceLabels, "Transcribing live audio")
                    : s);
        }

        private void markFailed(String message) {
            // Only escalate to ERROR when no other capture source is delivering.
            boolean anyOtherAlive = workers.stream()
                    .anyMatch(w -> w != this && w.thread != null && w.thread.isAlive());
            String detail = selection.displayName() + ": " + message;
            status.updateAndGet(s -> anyOtherAlive && s.state() == State.LISTENING
                    ? s
                    : new Status(State.ERROR, session.id(), deviceLabels, detail));
        }

        private SpeechRecognizer newRecognizer(float sampleRate) {
            return new SpeechRecognizer(model, sampleRate);
        }

        private void appendResult(String resultJson) {
            try {
                JsonNode node = objectMapper.readTree(resultJson);
                String text = node.path("text").asText("");
                // Capture every recognized phrase, labelled only by its source
                // ([you] = mic, [other] = system audio), but drop phrases that
                // carried no real audio energy — those are silence artefacts.
                boolean hadSpeech = phrasePeak >= MIN_SPEECH_LEVEL;
                phrasePeak = 0; // start measuring the next phrase
                if (!text.isBlank() && hadSpeech && !session.isEnded()) {
                    session.addUtterance(text, selection.label());
                }
            } catch (Exception e) {
                log.warn("Could not parse recognizer result: {}", resultJson, e);
            }
        }

        private void updatePartial(String partialJson) {
            try {
                String next = objectMapper.readTree(partialJson).path("partial").asText("");
                if (!next.equals(partialText)) {
                    // The caption grew (or changed): note when, so the flush timer
                    // only fires after it has been quiet for PARTIAL_FLUSH_MS.
                    if (!next.isBlank()) {
                        lastPartialGrewAt = System.currentTimeMillis();
                    }
                    partialText = next;
                }
            } catch (Exception e) {
                partialText = "";
            }
        }

        private void closeLine() {
            TargetDataLine current = line;
            if (current != null) {
                current.close();
                line = null;
            }
            Process process = tapProcess;
            if (process != null) {
                process.destroy();
                tapProcess = null;
            }
        }

        private void join(long millis) {
            if (thread != null) {
                try {
                    thread.join(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}

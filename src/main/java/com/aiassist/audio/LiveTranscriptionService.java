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
 * labelled with their source ("mic" or "meeting"). Supports pause/resume
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

    public LiveTranscriptionService(AudioDeviceService audioDevices, VoskModelManager modelManager,
                                    SessionStore sessions, TranscriptionProperties properties) {
        this.audioDevices = audioDevices;
        this.modelManager = modelManager;
        this.sessions = sessions;
        this.properties = properties;
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
        ListeningSession session = sessionId == null || sessionId.isBlank()
                ? sessions.create("Live meeting notes")
                : sessions.get(sessionId);

        List<AudioDeviceService.DeviceSelection> selections = deviceNames == null || deviceNames.isEmpty()
                ? audioDevices.resolveAutoDevices(properties.preferredDevice())
                : deviceNames.stream().map(n -> new AudioDeviceService.DeviceSelection(n, "meeting")).toList();
        List<String> deviceLabels = selections.stream()
                .map(s -> s.displayName() + " [" + s.label() + "]")
                .toList();

        running = true;
        status.set(new Status(State.PREPARING, session.id(), deviceLabels,
                "Loading speech model and opening audio devices"));

        Thread starter = new Thread(() -> {
            try {
                if (model == null) {
                    long t0 = System.currentTimeMillis();
                    model = new SpeechModel(modelManager.ensureModel().toString());
                    log.info("Speech model ready in {} ms", System.currentTimeMillis() - t0);
                }
            } catch (Throwable e) {
                // Throwable, not Exception: native-library loading failures are
                // Errors, and swallowing them would leave the status stuck on
                // PREPARING forever with no explanation.
                log.error("Speech model unavailable", e);
                running = false;
                status.set(new Status(State.ERROR, session.id(), deviceLabels,
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                return;
            }
            if (!running) {
                return;
            }
            status.updateAndGet(s -> s.state() == State.PREPARING
                    ? new Status(State.PREPARING, session.id(), deviceLabels,
                            "Opening audio devices — first time, the OS may ask for microphone "
                            + "permission (macOS: System Settings > Privacy & Security > Microphone); "
                            + "approve it to continue")
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
                // The device chooses the format (macOS often refuses 16 kHz);
                // the recognizer is created with whatever rate was granted and
                // stereo input is downmixed to mono before recognition.
                line = audioDevices.openBestCaptureLine(selection.deviceName());
                AudioFormat format = line.getFormat();
                boolean stereo = format.getChannels() == 2;
                line.start();
                markListening();
                log.info("Capturing '{}' as [{}] at {} Hz {} into session {}",
                        selection.displayName(), selection.label(),
                        (int) format.getSampleRate(), stereo ? "stereo" : "mono", session.id());
                try (SpeechRecognizer recognizer = new SpeechRecognizer(model, format.getSampleRate())) {
                    byte[] buffer = new byte[BUFFER_BYTES * format.getFrameSize()];
                    while (running) {
                        int n = line.read(buffer, 0, buffer.length);
                        if (n <= 0) {
                            continue;
                        }
                        int length = stereo ? downmixToMono(buffer, n) : n;
                        if (recognizer.acceptWaveform(buffer, length)) {
                            appendResult(recognizer.result());
                        }
                    }
                    appendResult(recognizer.finalResult());
                }
            } catch (Throwable e) {
                if (running) {
                    log.warn("Capture on '{}' failed: {}", selection.displayName(), e.getMessage());
                    markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            } finally {
                closeLine();
            }
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

        private void appendResult(String resultJson) {
            try {
                JsonNode node = objectMapper.readTree(resultJson);
                String text = node.path("text").asText("");
                if (!text.isBlank() && !session.isEnded()) {
                    session.addUtterance(text, selection.label());
                }
            } catch (Exception e) {
                log.warn("Could not parse recognizer result: {}", resultJson, e);
            }
        }

        private void closeLine() {
            TargetDataLine current = line;
            if (current != null) {
                current.close();
                line = null;
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

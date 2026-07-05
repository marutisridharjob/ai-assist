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
import org.vosk.Model;
import org.vosk.Recognizer;

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
    private volatile Model model;

    public LiveTranscriptionService(AudioDeviceService audioDevices, VoskModelManager modelManager,
                                    SessionStore sessions, TranscriptionProperties properties) {
        this.audioDevices = audioDevices;
        this.modelManager = modelManager;
        this.sessions = sessions;
        this.properties = properties;
    }

    public AudioFormat captureFormat() {
        // 16 kHz, 16-bit, mono, signed, little-endian: what the English Vosk model expects.
        return new AudioFormat(properties.sampleRate(), 16, 1, true, false);
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
                ? audioDevices.resolveAutoDevices(captureFormat(), properties.preferredDevice())
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
                    model = new Model(modelManager.ensureModel().toString());
                }
            } catch (Exception e) {
                log.error("Speech model unavailable", e);
                running = false;
                status.set(new Status(State.ERROR, session.id(), deviceLabels, e.getMessage()));
                return;
            }
            if (!running) {
                return;
            }
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
            AudioFormat format = captureFormat();
            try {
                line = audioDevices.openCaptureLine(selection.deviceName(), format);
                line.start();
                markListening();
                log.info("Capturing '{}' as [{}] into session {}",
                        selection.displayName(), selection.label(), session.id());
                try (Recognizer recognizer = new Recognizer(model, format.getSampleRate())) {
                    byte[] buffer = new byte[BUFFER_BYTES];
                    while (running) {
                        int n = line.read(buffer, 0, buffer.length);
                        if (n <= 0) {
                            continue;
                        }
                        if (recognizer.acceptWaveForm(buffer, n)) {
                            appendResult(recognizer.getResult());
                        }
                    }
                    appendResult(recognizer.getFinalResult());
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("Capture on '{}' failed: {}", selection.displayName(), e.getMessage());
                    markFailed(e.getMessage());
                }
            } finally {
                closeLine();
            }
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

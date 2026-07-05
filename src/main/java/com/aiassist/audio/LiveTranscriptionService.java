package com.aiassist.audio;

import java.util.concurrent.atomic.AtomicBoolean;
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
 * Captures audio from a device and streams it through the Vosk offline
 * recognizer, appending every recognized phrase to a listening session.
 * Point it at the OS loopback device to transcribe an active Webex or
 * MS Teams meeting playing on this computer.
 */
@Service
public class LiveTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(LiveTranscriptionService.class);
    private static final int BUFFER_BYTES = 4096;

    public enum State { IDLE, PREPARING, LISTENING, ERROR }

    public record Status(State state, String sessionId, String device, String detail) {
    }

    private final AudioDeviceService audioDevices;
    private final VoskModelManager modelManager;
    private final SessionStore sessions;
    private final TranscriptionProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Status> status =
            new AtomicReference<>(new Status(State.IDLE, null, null, null));
    private volatile Thread worker;
    private volatile TargetDataLine line;
    private volatile Model model;

    public LiveTranscriptionService(AudioDeviceService audioDevices, VoskModelManager modelManager,
                                    SessionStore sessions, TranscriptionProperties properties) {
        this.audioDevices = audioDevices;
        this.modelManager = modelManager;
        this.sessions = sessions;
        this.properties = properties;
    }

    public AudioFormat captureFormat() {
        // 16 kHz, 16-bit, mono, signed, little-endian: what Vosk models expect.
        return new AudioFormat(properties.sampleRate(), 16, 1, true, false);
    }

    /**
     * Starts live capture into the given session (created if null). Returns
     * immediately; model download and recognition happen on a worker thread.
     */
    public synchronized Status start(String deviceName, String sessionId) {
        if (running.get()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Live transcription is already running for session " + status.get().sessionId());
        }
        ListeningSession session = sessionId == null || sessionId.isBlank()
                ? sessions.create("Live meeting notes")
                : sessions.get(sessionId);
        String device = deviceName == null || deviceName.isBlank() ? properties.preferredDevice() : deviceName;

        running.set(true);
        status.set(new Status(State.PREPARING, session.id(), device,
                "Preparing speech model and opening audio device"));
        worker = new Thread(() -> captureLoop(session, device), "live-transcription");
        worker.setDaemon(true);
        worker.start();
        return status.get();
    }

    public synchronized Status stop() {
        if (!running.get()) {
            return status.get();
        }
        running.set(false);
        TargetDataLine current = line;
        if (current != null) {
            current.stop();
            current.close();
        }
        Thread t = worker;
        if (t != null) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Status stopped = new Status(State.IDLE, status.get().sessionId(), status.get().device(), "Stopped");
        status.set(stopped);
        return stopped;
    }

    public Status status() {
        return status.get();
    }

    private void captureLoop(ListeningSession session, String device) {
        AudioFormat format = captureFormat();
        try {
            if (model == null) {
                model = new Model(modelManager.ensureModel().toString());
            }
            line = audioDevices.openCaptureLine(device, format);
            line.start();
            status.set(new Status(State.LISTENING, session.id(), device, "Transcribing live audio"));
            log.info("Live transcription started on device '{}' into session {}",
                    device == null ? "(default)" : device, session.id());

            try (Recognizer recognizer = new Recognizer(model, format.getSampleRate())) {
                byte[] buffer = new byte[BUFFER_BYTES];
                while (running.get()) {
                    int n = line.read(buffer, 0, buffer.length);
                    if (n <= 0) {
                        continue;
                    }
                    if (recognizer.acceptWaveForm(buffer, n)) {
                        appendResult(session, recognizer.getResult());
                    }
                }
                appendResult(session, recognizer.getFinalResult());
            }
        } catch (Exception e) {
            log.error("Live transcription failed", e);
            status.set(new Status(State.ERROR, session.id(), device, e.getMessage()));
        } finally {
            running.set(false);
            TargetDataLine current = line;
            if (current != null) {
                current.close();
                line = null;
            }
            if (status.get().state() == State.LISTENING) {
                status.set(new Status(State.IDLE, session.id(), device, "Stopped"));
            }
        }
    }

    private void appendResult(ListeningSession session, String resultJson) {
        try {
            JsonNode node = objectMapper.readTree(resultJson);
            String text = node.path("text").asText("");
            if (!text.isBlank()) {
                session.addUtterance(text, "meeting");
            }
        } catch (Exception e) {
            log.warn("Could not parse recognizer result: {}", resultJson, e);
        }
    }
}

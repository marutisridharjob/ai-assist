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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Records the meeting audio from several devices at once — the microphone and
 * any OS loopback device that carries what the computer is playing — into one
 * local recording per source. On Stop the recording is transcribed with Whisper
 * into the meeting notes. Supports pause/resume mid-meeting; a full stop is
 * driven by the meeting-end flow.
 */
@Service
public class MeetingCaptureService {

    private static final Logger log = LoggerFactory.getLogger(MeetingCaptureService.class);
    private static final int BUFFER_BYTES = 4096;

    public enum State { IDLE, PREPARING, LISTENING, PAUSED, ERROR }

    public record Status(State state, String sessionId, List<String> devices, String detail) {
    }

    private final AudioDeviceService audioDevices;
    private final SessionStore sessions;
    private final TranscriptionProperties properties;

    private final AtomicReference<Status> status =
            new AtomicReference<>(new Status(State.IDLE, null, List.of(), null));
    private final List<CaptureWorker> workers = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile MeetingRecorder recorder;
    private volatile String recorderSessionId;

    public MeetingCaptureService(AudioDeviceService audioDevices, SessionStore sessions,
                                 TranscriptionProperties properties) {
        this.audioDevices = audioDevices;
        this.sessions = sessions;
        this.properties = properties;
    }

    /**
     * Warms the capture path at app launch so pressing Start is instant. The
     * native tap's one-time helper build and device enumeration are the slow
     * parts. Best-effort; never blocks startup.
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void preloadOnStartup() {
        Thread preloader = new Thread(() -> {
            try {
                NativeSystemAudioTap.isSupported();
                audioDevices.listCaptureDevices();
            } catch (Throwable e) {
                log.debug("Capture warm-up skipped: {}", e.getMessage());
            }
        }, "capture-warmup");
        preloader.setDaemon(true);
        preloader.start();
    }

    /**
     * Starts recording the given devices (auto-resolved to microphone +
     * loopback devices when null/empty) into the given session (created when
     * null). Returns immediately; device opening happens on a worker thread.
     */
    public synchronized Status start(List<String> deviceNames, String sessionId) {
        State current = status.get().state();
        if (current == State.LISTENING || current == State.PREPARING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Recording is already running for session " + status.get().sessionId());
        }
        ListeningSession session;
        if (sessionId == null || sessionId.isBlank()) {
            session = sessions.create("Minutes of meeting");
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
                String detail = "Cannot record meeting audio: " + e.getMessage();
                log.error(detail);
                status.set(new Status(State.ERROR, session.id(), List.of(), detail));
                return status.get();
            }
        }

        running = true;
        status.set(new Status(State.PREPARING, session.id(), List.of("detecting audio sources…"),
                "Preparing audio capture"));

        Thread starter = new Thread(() -> {
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
        }, "capture-init");
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

    /** Pauses recording; the session stays open and {@link #resume()} continues it. */
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

    /** Fully stops recording (used by the meeting-end flow). */
    public synchronized Status stop() {
        Status current = status.get();
        stopWorkers();
        Status stopped = new Status(State.IDLE, current.sessionId(), current.devices(), "Stopped");
        status.set(stopped);
        return stopped;
    }

    // ===== System-audio activity monitor =====
    // A lightweight, level-only listen on the SYSTEM audio (the "other" source —
    // what the computer is playing, i.e. the meeting participants). No speech
    // model, no recording — it just measures loudness so auto-start can wait
    // until others are actually talking. Uses the same source a meeting would
    // (the native tap, or a loopback device), so it works on every OS.

    private static final int MONITOR_ACTIVE_LEVEL = 4; // 0-100 peak that counts as "someone talking"
    private volatile Thread monitorThread;
    private volatile boolean monitorRunning;
    private volatile javax.sound.sampled.TargetDataLine monitorLine;
    private volatile Process monitorProcess;
    private volatile int monitorLevel;
    private volatile long monitorLastLoud;
    // A second, independent channel on the microphone, run alongside the
    // system-audio one above — a system-audio tap/loopback only ever carries
    // what the computer plays back, so it stays silent for an in-person
    // conversation (nothing playing through the speakers) even though people
    // are clearly talking. Tracking the mic too means the ambient "still
    // listening" indicator and the generic (non-known-app) start prompt work
    // for that case as well, not only for a remote meeting's audio.
    private volatile Thread micMonitorThread;
    private volatile javax.sound.sampled.TargetDataLine micMonitorLine;
    private volatile int micMonitorLevel;
    private volatile long micMonitorLastLoud;

    /** The best system-audio ("other") source, or null when none is available. */
    private AudioDeviceService.DeviceSelection systemAudioSelection() {
        if (NativeSystemAudioTap.isSupported()) {
            return new AudioDeviceService.DeviceSelection(NativeSystemAudioTap.SOURCE_NAME, "other", true);
        }
        return audioDevices.resolveAutoDevices(properties.preferredDevice()).stream()
                .filter(s -> "other".equals(s.label()))
                .findFirst().orElse(null);
    }

    /**
     * Begins level-only monitoring (no transcription/recording) of the system
     * audio, when available, and the microphone, always attempted — so
     * either channel can drive the "listening" indicator and the generic
     * start prompt regardless of which one a given meeting actually uses.
     */
    public synchronized void startActivityMonitor() {
        if (monitorRunning) {
            return;
        }
        monitorRunning = true;
        monitorLevel = 0;
        micMonitorLevel = 0;
        AudioDeviceService.DeviceSelection sys = systemAudioSelection();
        if (sys != null) {
            monitorThread = new Thread(() -> {
                try {
                    if (sys.systemTap()) {
                        monitorViaTap();
                    } else {
                        monitorViaLine(sys.deviceName());
                    }
                } catch (Throwable t) {
                    log.warn("System-audio monitor unavailable ({}); auto-start will fall back to app detection",
                            rootMessage(t));
                }
            }, "sys-audio-monitor");
            monitorThread.setDaemon(true);
            monitorThread.start();
        }
        micMonitorThread = new Thread(() -> {
            try {
                monitorMic();
            } catch (Throwable t) {
                log.warn("Microphone activity monitor unavailable ({})", rootMessage(t));
            }
        }, "mic-audio-monitor");
        micMonitorThread.setDaemon(true);
        micMonitorThread.start();
    }

    /** Stops the activity monitor and frees the audio sources. Safe to call any time. */
    public synchronized void stopActivityMonitor() {
        monitorRunning = false;
        javax.sound.sampled.TargetDataLine l = monitorLine;
        if (l != null) {
            try {
                l.stop();
                l.close();
            } catch (Exception ignored) {
                // closing best-effort
            }
            monitorLine = null;
        }
        Process p = monitorProcess;
        if (p != null) {
            p.destroy();
            monitorProcess = null;
        }
        javax.sound.sampled.TargetDataLine micLine = micMonitorLine;
        if (micLine != null) {
            try {
                micLine.stop();
                micLine.close();
            } catch (Exception ignored) {
                // closing best-effort
            }
            micMonitorLine = null;
        }
        // Joined so the microphone is guaranteed free the moment this returns
        // — a real meeting's own capture opens the same device right after.
        joinQuietly(monitorThread);
        monitorThread = null;
        joinQuietly(micMonitorThread);
        micMonitorThread = null;
        monitorLevel = 0;
        micMonitorLevel = 0;
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean monitorRunning() {
        return monitorRunning;
    }

    /**
     * The activity monitor's current level, 0-100 (0 when it isn't running):
     * the louder of the system-audio and microphone channels. Shown live in
     * the UI so the user can see the app is actually listening while it
     * waits to offer starting a meeting — not just for the meeting apps it
     * recognizes by name, but for any audio source, including a
     * browser-based meeting tool or an in-person conversation picked up on
     * the microphone.
     */
    public int monitorLevel() {
        return monitorRunning ? Math.max(monitorLevel, micMonitorLevel) : 0;
    }

    /**
     * True when the system audio specifically has been loud within the last
     * {@code withinMs}. Deliberately mic-blind — the hands-free auto-start
     * for a recognized meeting app only ever triggers once its own audio is
     * confirmed, never from ambient room noise on the microphone.
     */
    public boolean systemAudioActiveWithin(long withinMs) {
        return monitorRunning && (System.currentTimeMillis() - monitorLastLoud) < withinMs;
    }

    /**
     * True when either channel — system audio or microphone — has been loud
     * within the last {@code withinMs}. Used only for the generic (no known
     * app recognized) start prompt, which always requires an explicit click
     * to actually start, so triggering it from room noise is harmless.
     */
    public boolean anyAudioActiveWithin(long withinMs) {
        long now = System.currentTimeMillis();
        return monitorRunning
                && (now - monitorLastLoud < withinMs || now - micMonitorLastLoud < withinMs);
    }

    private void monitorMic() throws Exception {
        javax.sound.sampled.TargetDataLine line = audioDevices.openBestCaptureLine(null);
        micMonitorLine = line;
        int frame = Math.max(2, line.getFormat().getFrameSize());
        line.start();
        byte[] buffer = new byte[BUFFER_BYTES * frame];
        while (monitorRunning) {
            int n = line.read(buffer, 0, buffer.length);
            if (n <= 0) {
                continue;
            }
            int level = peakLevel(buffer, n);
            micMonitorLevel = level;
            if (level >= MONITOR_ACTIVE_LEVEL) {
                micMonitorLastLoud = System.currentTimeMillis();
            }
        }
    }

    private void monitorViaLine(String deviceName) throws Exception {
        javax.sound.sampled.TargetDataLine line = audioDevices.openBestCaptureLine(deviceName);
        monitorLine = line;
        int frame = Math.max(2, line.getFormat().getFrameSize());
        line.start();
        byte[] buffer = new byte[BUFFER_BYTES * frame];
        while (monitorRunning) {
            int n = line.read(buffer, 0, buffer.length);
            if (n <= 0) {
                continue;
            }
            recordActivity(peakLevel(buffer, n));
        }
    }

    private void monitorViaTap() throws Exception {
        Process process = NativeSystemAudioTap.startHelper();
        monitorProcess = process;
        java.io.InputStream pcm = new java.io.BufferedInputStream(process.getInputStream());
        NativeSystemAudioTap.readHeader(pcm);
        byte[] buffer = new byte[BUFFER_BYTES];
        while (monitorRunning) {
            int n = pcm.read(buffer, 0, buffer.length);
            if (n < 0) {
                break;
            }
            if (n > 0) {
                recordActivity(peakLevel(buffer, n));
            }
        }
    }

    private void recordActivity(int level) {
        monitorLevel = level;
        if (level >= MONITOR_ACTIVE_LEVEL) {
            monitorLastLoud = System.currentTimeMillis();
        }
    }

    /** Loudest 16-bit LE sample in the buffer as 0-100. */
    private static int peakLevel(byte[] buffer, int length) {
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = Math.abs((short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF)));
            if (sample > peak) {
                peak = sample;
            }
        }
        return peak * 100 / 32767;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
    }

    public Status status() {
        return status.get();
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

    /** Live input level (0–100) per source label, e.g. {mic=42, meeting=0}. */
    public java.util.Map<String, Integer> levels() {
        java.util.Map<String, Integer> levels = new java.util.LinkedHashMap<>();
        for (CaptureWorker worker : workers) {
            levels.merge(worker.selection.label(), worker.level, Integer::max);
        }
        return levels;
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
        private volatile Thread thread;

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
            log.info("Recording '{}' as [{}] at {} Hz {} → 16 kHz into session {}",
                    selection.displayName(), selection.label(),
                    (int) deviceRate, stereo ? "stereo" : "mono", session.id());
            byte[] buffer = new byte[BUFFER_BYTES * format.getFrameSize()];
            while (running) {
                int n = line.read(buffer, 0, buffer.length);
                if (n <= 0) {
                    continue;
                }
                int monoLen = stereo ? downmixToMono(buffer, n) : n;
                level = peakPercent(buffer, monoLen);
                record(AudioResampler.to16k(buffer, monoLen, deviceRate));
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
            log.info("Recording system audio via native tap at {} Hz → 16 kHz into session {}",
                    (int) sampleRate, session.id());
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
                record(AudioResampler.to16k(buffer, n, sampleRate));
            }
            if (running && !process.isAlive() && process.exitValue() != 0) {
                throw new java.io.IOException("system-audio helper exited with code "
                        + process.exitValue() + " (see [system-tap] log lines for the reason)");
            }
        }

        /** Writes one 16 kHz mono chunk to this source's recording. */
        private void record(byte[] pcm16k) {
            MeetingRecorder rec = recorder;
            if (rec != null) {
                rec.record(selection.label(), pcm16k, pcm16k.length);
            }
        }

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
                    ? new Status(State.LISTENING, session.id(), deviceLabels, "Recording meeting audio")
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

package com.aiassist.audio;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the meeting audio locally while it is live: one raw 16&nbsp;kHz
 * mono 16-bit PCM file per source label ("you", "other"), in OS temp space.
 * Appends across pause/resume. On {@link #finish()} the files are handed to
 * the meeting-end flow, which transcribes them with Whisper for the accurate
 * complete-conversation document, then deletes them. Fully local — nothing
 * leaves the machine.
 */
final class MeetingRecorder {

    private static final Logger log = LoggerFactory.getLogger(MeetingRecorder.class);

    private final Path dir;
    private final Map<String, OutputStream> streams = new ConcurrentHashMap<>();
    private final Map<String, Path> files = new ConcurrentHashMap<>();

    MeetingRecorder(String sessionId) throws IOException {
        this.dir = Path.of(System.getProperty("java.io.tmpdir"), "ai-assist", "recordings", sessionId);
        Files.createDirectories(dir);
    }

    /** Appends 16 kHz mono PCM for a source; opens the file on first write. */
    void record(String label, byte[] pcm, int length) {
        try {
            OutputStream out = streams.computeIfAbsent(label, this::openStream);
            if (out != null) {
                synchronized (out) {
                    out.write(pcm, 0, length);
                }
            }
        } catch (IOException e) {
            log.warn("Could not record {} audio: {}", label, e.getMessage());
        }
    }

    private OutputStream openStream(String label) {
        try {
            Path file = dir.resolve(label + ".pcm");
            files.put(label, file);
            return new BufferedOutputStream(Files.newOutputStream(file));
        } catch (IOException e) {
            log.warn("Could not open recording file for {}: {}", label, e.getMessage());
            return null;
        }
    }

    /** Closes the files and returns them by source label (in first-seen order). */
    Map<String, Path> finish() {
        Map<String, Path> result = new LinkedHashMap<>();
        for (var entry : files.entrySet()) {
            OutputStream out = streams.get(entry.getKey());
            if (out != null) {
                try {
                    synchronized (out) {
                        out.flush();
                        out.close();
                    }
                } catch (IOException e) {
                    log.warn("Could not close recording for {}: {}", entry.getKey(), e.getMessage());
                }
            }
            if (Files.exists(entry.getValue())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** Deletes the recording files after they have been transcribed. */
    void discard() {
        try {
            for (Path file : files.values()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.debug("Could not clean up recording dir {}: {}", dir, e.getMessage());
        }
    }
}

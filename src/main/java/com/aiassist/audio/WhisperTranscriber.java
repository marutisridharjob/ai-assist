package com.aiassist.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Accurate, fully-offline speech-to-text with OpenAI's Whisper (whisper.cpp,
 * MIT) via whisper-jni. The native libraries are bundled in the jar; the
 * model is a local {@code ggml-*.bin} file the user drops in the app folder.
 * Used to transcribe the whole recorded meeting on Stop (Vosk still drives
 * the real-time live captions). Nothing leaves the machine.
 */
@Service
public class WhisperTranscriber {

    private static final Logger log = LoggerFactory.getLogger(WhisperTranscriber.class);

    private WhisperJNI whisper;
    private WhisperContext context;
    private String loadedModel;
    private boolean libraryFailed;

    /** A recognized segment with its start time (seconds) for cross-source ordering. */
    public record Segment(double start, String text) {
    }

    /** The ggml-*.bin whisper model found next to the app, if any. */
    public synchronized Optional<Path> findModel() {
        for (Path root : VoskModelManager.modelSearchRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var files = Files.list(root)) {
                var match = files.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.startsWith("ggml-") && n.endsWith(".bin");
                }).sorted().findFirst();
                if (match.isPresent()) {
                    return match;
                }
            } catch (IOException ignored) {
                // best-effort
            }
        }
        return Optional.empty();
    }

    public boolean isAvailable() {
        return !libraryFailed && findModel().isPresent();
    }

    /**
     * Releases the loaded model, if any — e.g. before deleting the models
     * folder, so an in-progress uninstall never fights a native library over
     * a file it still has open (llama.cpp/whisper.cpp can mmap model files,
     * which Windows in particular refuses to delete while mapped).
     */
    public synchronized void releaseModel() {
        if (context != null) {
            whisper.free(context);
            context = null;
            loadedModel = null;
        }
    }

    private synchronized void ensureLoaded(Path modelPath) throws IOException {
        if (whisper == null) {
            WhisperJNI.loadLibrary();      // extracts bundled natives, offline
            whisper = new WhisperJNI();
        }
        if (context == null || !modelPath.toString().equals(loadedModel)) {
            if (context != null) {
                whisper.free(context);
                context = null;
            }
            long t0 = System.currentTimeMillis();
            context = whisper.init(modelPath);
            loadedModel = modelPath.toString();
            log.info("Whisper model '{}' loaded in {} ms", modelPath.getFileName(),
                    System.currentTimeMillis() - t0);
        }
    }

    /**
     * Transcribes a raw 16 kHz mono 16-bit PCM file into timestamped
     * segments. Returns an empty list if Whisper is unavailable or fails.
     */
    public synchronized List<Segment> transcribe(Path pcmFile) {
        Optional<Path> modelPath = findModel();
        if (libraryFailed || modelPath.isEmpty()) {
            return List.of();
        }
        try {
            ensureLoaded(modelPath.get());
            float[] samples = readPcmAsFloat(pcmFile);
            if (samples.length == 0) {
                return List.of();
            }
            WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
            params.nThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            params.printProgress = false;
            // Leave whisper.cpp's temperature-fallback loop at its own default
            // (re-decodes a chunk at a higher temperature whenever its
            // confidence thresholds aren't met, e.g. a pause, room noise,
            // cross-talk). This costs real time — several-fold on a long
            // recording — but accuracy is the priority for the saved notes,
            // and MeetingEndService's TRANSCRIPTION_TIMEOUT is sized with
            // that trade in mind, falling back to the live captions rather
            // than blocking forever if it's still not done.
            // Don't transcribe non-speech audio as if it were words: whisper.cpp
            // can otherwise emit tokens for music, applause, ringtones, hold
            // music, etc. as if someone said something. noSpeechThold (0.6,
            // whisper-jni's own default) already skips silent/paused stretches
            // on its own.
            params.suppressNonSpeechTokens = true;
            long t0 = System.currentTimeMillis();
            int rc = whisper.full(context, params, samples, samples.length);
            double audioSeconds = samples.length / 16000.0;
            log.info("Whisper transcribed {} ({}s of audio) in {} ms", pcmFile.getFileName(),
                    (long) audioSeconds, System.currentTimeMillis() - t0);
            if (rc != 0) {
                log.warn("Whisper transcription returned code {}", rc);
                return List.of();
            }
            List<Segment> segments = new ArrayList<>();
            int n = whisper.fullNSegments(context);
            for (int i = 0; i < n; i++) {
                String text = whisper.fullGetSegmentText(context, i).strip();
                // whisper timestamps are in centiseconds.
                double start = whisper.fullGetSegmentTimestamp0(context, i) / 100.0;
                double end = whisper.fullGetSegmentTimestamp1(context, i) / 100.0;
                if (!text.isBlank() && !isLikelyHallucinatedFiller(text, end - start)) {
                    segments.add(new Segment(start, text));
                }
            }
            return segments;
        } catch (Throwable e) {
            log.warn("Whisper transcription unavailable ({}); using the live captions instead",
                    e.getMessage());
            libraryFailed = true;
            return List.of();
        }
    }

    /** A quiet stretch this short still gets a segment; a real spoken word doesn't take this long. */
    private static final double HALLUCINATION_DURATION_SECONDS = 4.0;

    /**
     * whisper.cpp's own no-speech detection ({@code noSpeechThold}) works on
     * whole ~30-second windows, so a window with a little real speech and a
     * long quiet tail can still get a short generic word ("the", "you",
     * "thank you") hallucinated out of the silent part — a real spoken word
     * or two takes at most a second or so, so a segment whose reported
     * duration is much longer than that for so little text is almost
     * certainly this, not genuine speech, and is dropped.
     */
    static boolean isLikelyHallucinatedFiller(String text, double durationSeconds) {
        int words = text.split("\\s+").length;
        return words <= 3 && durationSeconds > HALLUCINATION_DURATION_SECONDS;
    }

    /** Reads 16-bit little-endian mono PCM into normalized float samples. */
    static float[] readPcmAsFloat(Path pcmFile) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(pcmFile)) {
            bytes = in.readAllBytes();
        }
        int n = bytes.length / 2;
        float[] samples = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = bytes[2 * i] & 0xFF;
            int hi = bytes[2 * i + 1];
            short s = (short) ((hi << 8) | lo);
            samples[i] = s / 32768f;
        }
        return samples;
    }
}

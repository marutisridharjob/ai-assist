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
        return findModel(n -> n.startsWith("ggml-") && n.endsWith(".bin"));
    }

    /**
     * A small, fast ggml model (tiny/base) suitable for live captioning —
     * unlike the full accurate pass on Stop, this one has to keep up with
     * the conversation in near-real-time, and a medium/large model simply
     * can't decode fast enough for that. {@code null} (via the caller
     * checking {@link Optional#isEmpty()}) means live Whisper captions have
     * nothing to run on even if an accurate (medium/large) model is present
     * for the final transcript.
     */
    public synchronized Optional<Path> findFastModel() {
        return findModel(n -> n.startsWith("ggml-")
                && (n.contains("tiny") || n.contains("base"))
                && n.endsWith(".bin"));
    }

    private Optional<Path> findModel(java.util.function.Predicate<String> nameMatches) {
        for (Path root : VoskModelManager.modelSearchRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var files = Files.list(root)) {
                var match = files.filter(p -> nameMatches.test(p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .max(java.util.Comparator.comparingInt(WhisperTranscriber::accuracyRank)
                                .thenComparing(p -> p.getFileName().toString()));
                if (match.isPresent()) {
                    return match;
                }
            } catch (IOException ignored) {
                // best-effort
            }
        }
        return Optional.empty();
    }

    // Worst to best: a plain alphabetical pick would put "ggml-base.bin"
    // ahead of "ggml-large-v3.bin" (b < l) and silently keep using the
    // smaller, less accurate model even after a better one was added
    // alongside it. Rank by the size name in the file instead so the most
    // accurate installed model always wins; unrecognized names (or a tie,
    // e.g. two "large" variants) fall back to the file name itself so the
    // choice is still deterministic rather than filesystem-order-dependent.
    private static final List<String> ACCURACY_RANK = List.of("tiny", "base", "small", "medium", "large");

    private static int accuracyRank(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (int i = ACCURACY_RANK.size() - 1; i >= 0; i--) {
            if (name.contains(ACCURACY_RANK.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public boolean isAvailable() {
        return !libraryFailed && findModel().isPresent();
    }

    /** True when a small/fast model is available for live-caption use. */
    public boolean isFastModelAvailable() {
        return !libraryFailed && findFastModel().isPresent();
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
            float[] samples = readPcmAsFloat(pcmFile);
            return decode(modelPath.get(), samples, pcmFile.getFileName().toString());
        } catch (IOException e) {
            log.warn("Could not read recording {} ({})", pcmFile, e.getMessage());
            return List.of();
        }
    }

    /**
     * Transcribes one short in-memory chunk of live-captured 16 kHz mono
     * audio (no temp file, for lower latency) with the small/fast model —
     * see {@link #findFastModel()} — joining any recognized segments into a
     * single line. Empty when no fast model is installed, the chunk decoded
     * to nothing (silence), or Whisper is unavailable.
     */
    public synchronized String transcribeChunk(float[] samples) {
        Optional<Path> modelPath = findFastModel();
        if (libraryFailed || modelPath.isEmpty()) {
            return "";
        }
        List<Segment> segments = decode(modelPath.get(), samples, "live chunk");
        return segments.stream().map(Segment::text)
                .collect(java.util.stream.Collectors.joining(" ")).strip();
    }

    /** Loads {@code modelPath} if needed and runs whisper.cpp's full decode over {@code samples}. */
    private List<Segment> decode(Path modelPath, float[] samples, String logLabel) {
        if (samples.length == 0) {
            return List.of();
        }
        try {
            ensureLoaded(modelPath);
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
            log.info("Whisper transcribed {} ({}s of audio) in {} ms", logLabel,
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
            return dropRepeatedFillerLoops(segments);
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

    /** A run of at least this many identical consecutive segments is a stuck-decoder loop, not real speech. */
    private static final int REPEAT_LOOP_MIN_RUN = 3;

    /**
     * Collapses a run of 3+ back-to-back segments with the same text down to
     * a single occurrence. Whisper.cpp is well known to occasionally get
     * "stuck" on a noisy or ambiguous stretch of audio and emit the same
     * phrase over and over ("I don't know. I don't know. I don't know.")
     * instead of actually transcribing anything new. This is a safe signal
     * specifically because it only fires on an exact, repeated, 3-or-more
     * run: real conversation essentially never repeats an identical phrase
     * that many times consecutively, so unlike a duration- or
     * similarity-based filter, this can't mistake a genuine short reply
     * (including a real double "no, no") for a hallucination.
     */
    static List<Segment> dropRepeatedFillerLoops(List<Segment> segments) {
        List<Segment> result = new ArrayList<>();
        int i = 0;
        while (i < segments.size()) {
            int runEnd = i + 1;
            String normalized = normalizeForRepeatCheck(segments.get(i).text());
            while (runEnd < segments.size()
                    && normalizeForRepeatCheck(segments.get(runEnd).text()).equals(normalized)) {
                runEnd++;
            }
            int runLength = runEnd - i;
            if (runLength >= REPEAT_LOOP_MIN_RUN) {
                result.add(segments.get(i)); // collapse the whole loop to its first occurrence
            } else {
                result.addAll(segments.subList(i, runEnd)); // a short repeat (e.g. a real "no, no") stays untouched
            }
            i = runEnd;
        }
        return result;
    }

    private static String normalizeForRepeatCheck(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").strip();
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

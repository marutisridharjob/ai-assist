package com.aiassist.draft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.aiassist.audio.VoskModelManager;

import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import de.kherud.llama.Pair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Optional, fully-offline in-process LLM (llama.cpp, MIT) via de.kherud:llama.
 * The native libraries are bundled in the jar; the model is a single GGUF
 * instruct file the user drops next to the jar (same place as the Vosk and
 * Whisper models). When a model is present it powers the meeting summary and
 * the Editor/Compose rewrites; otherwise the app falls back to its offline
 * rule-based drafter. No server, no Ollama, nothing leaves the machine.
 */
@Service
public class LocalLlmService {

    private static final Logger log = LoggerFactory.getLogger(LocalLlmService.class);

    /** Context window; leaves room for the reply. Kept modest for tiny models. */
    private static final int CONTEXT_TOKENS = 8192;
    /** Roughly 4 chars per token — cap the prompt so it fits the context. */
    private static final int MAX_INPUT_CHARS = 12_000;

    private LlamaModel model;
    private String loadedModel;
    private boolean libraryFailed;
    /** Human-readable outcome of the last attempt, surfaced in the UI. */
    private volatile String lastReport = "no request yet";

    /** The first *.gguf model found next to the app, if any. */
    public synchronized Optional<Path> findModel() {
        for (Path root : VoskModelManager.modelSearchRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var files = Files.list(root)) {
                var match = files
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                        .sorted()
                        .findFirst();
                if (match.isPresent()) {
                    return match;
                }
            } catch (IOException ignored) {
                // best-effort
            }
        }
        return Optional.empty();
    }

    /** True when a GGUF model is present and the native library loads. */
    public boolean isAvailable() {
        return !libraryFailed && findModel().isPresent();
    }

    /**
     * Releases the loaded model, if any — e.g. before deleting the models
     * folder, so an in-progress uninstall never fights llama.cpp over a file
     * it still has open (it mmaps GGUF files by default, which Windows in
     * particular refuses to delete while mapped).
     */
    public synchronized void unload() {
        if (model != null) {
            model.close();
            model = null;
            loadedModel = null;
        }
    }

    /** One-line outcome of the last generate() call, for the UI/status line. */
    public String report() {
        return lastReport;
    }

    /** Where the app looked and what it found, without loading anything. */
    public synchronized String describe() {
        if (libraryFailed) {
            return "local model disabled — native library failed to load (" + lastReport + ")";
        }
        Optional<Path> model = findModel();
        if (model.isEmpty()) {
            return "no .gguf model found. Put one next to the jar or in models/. Looked in: "
                    + searchedPaths();
        }
        return "model found: " + model.get().getFileName()
                + (loadedModel != null && loadedModel.equals(model.get().toString()) ? " (loaded)" : "");
    }

    private String searchedPaths() {
        StringBuilder sb = new StringBuilder();
        for (Path root : VoskModelManager.modelSearchRoots()) {
            sb.append(sb.isEmpty() ? "" : "; ").append(root.toAbsolutePath());
        }
        return sb.toString();
    }

    /**
     * Generates a reply for the given system instruction and user content,
     * using the model's own chat template. Returns empty (so the caller falls
     * back to the rules) when no model is installed or generation fails.
     */
    public synchronized Optional<String> generate(String systemPrompt, String userContent, int maxTokens) {
        if (userContent == null || userContent.isBlank()) {
            return Optional.empty();
        }
        if (libraryFailed) {
            lastReport = "native library unavailable on this OS; used the offline rules";
            return Optional.empty();
        }
        Optional<Path> modelPath = findModel();
        if (modelPath.isEmpty()) {
            lastReport = "no .gguf model found (looked in: " + searchedPaths() + "); used the offline rules";
            return Optional.empty();
        }
        String name = modelPath.get().getFileName().toString();
        try {
            ensureLoaded(modelPath.get());
            String content = userContent.length() > MAX_INPUT_CHARS
                    ? userContent.substring(0, MAX_INPUT_CHARS)
                    : userContent;
            long t0 = System.currentTimeMillis();
            // Build the actual prompt from the chat messages using the model's
            // own template — complete() generates from the "prompt", so the
            // content must be baked into it (setMessages alone leaves it empty).
            String prompt = model.applyTemplate(new InferenceParameters("")
                    .setMessages(systemPrompt, List.of(new Pair<>("user", content)))
                    .setUseChatTemplate(true));
            InferenceParameters params = new InferenceParameters(prompt)
                    .setTemperature(0.2f)
                    .setTopP(0.9f)
                    .setRepeatPenalty(1.15f) // stop tiny models looping/repeating
                    .setNPredict(maxTokens);
            String out = model.complete(params);
            Optional<String> result = Optional.ofNullable(out).map(String::strip).filter(s -> !s.isBlank());
            lastReport = result.isPresent()
                    ? "used " + name + " (" + (System.currentTimeMillis() - t0) + " ms)"
                    : name + " returned nothing; used the offline rules";
            return result;
        } catch (Throwable t) {
            // UnsatisfiedLinkError / NoClassDefFoundError => unsupported platform:
            // disable for the rest of the run so we don't retry the native load.
            if (t instanceof UnsatisfiedLinkError || t instanceof NoClassDefFoundError) {
                libraryFailed = true;
            }
            lastReport = name + " failed to load/run: " + rootCause(t);
            log.warn("Local LLM unavailable ({}); using the offline rules instead", lastReport, t);
            return Optional.empty();
        }
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private void ensureLoaded(Path modelPath) {
        String path = modelPath.toString();
        if (model != null && path.equals(loadedModel)) {
            return;
        }
        if (model != null) {
            model.close();
            model = null;
        }
        long t0 = System.currentTimeMillis();
        ModelParameters params = new ModelParameters()
                .setModel(path)
                .setGpuLayers(0) // CPU only
                .setCtxSize(CONTEXT_TOKENS)
                .setThreads(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
        model = new LlamaModel(params);
        loadedModel = path;
        log.info("Local LLM '{}' loaded in {} ms", modelPath.getFileName(),
                System.currentTimeMillis() - t0);
    }
}

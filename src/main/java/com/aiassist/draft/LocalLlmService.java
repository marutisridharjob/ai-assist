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
     * Generates a reply for the given system instruction and user content,
     * using the model's own chat template. Returns empty (so the caller falls
     * back to the rules) when no model is installed or generation fails.
     */
    public synchronized Optional<String> generate(String systemPrompt, String userContent, int maxTokens) {
        if (libraryFailed || userContent == null || userContent.isBlank()) {
            return Optional.empty();
        }
        Optional<Path> modelPath = findModel();
        if (modelPath.isEmpty()) {
            return Optional.empty();
        }
        try {
            ensureLoaded(modelPath.get());
            String content = userContent.length() > MAX_INPUT_CHARS
                    ? userContent.substring(0, MAX_INPUT_CHARS)
                    : userContent;
            InferenceParameters params = new InferenceParameters("")
                    .setMessages(systemPrompt, List.of(new Pair<>("user", content)))
                    .setUseChatTemplate(true)
                    .setTemperature(0.3f)
                    .setNPredict(maxTokens);
            String out = model.complete(params);
            return Optional.ofNullable(out).map(String::strip).filter(s -> !s.isBlank());
        } catch (Throwable t) {
            // UnsatisfiedLinkError / NoClassDefFoundError => unsupported platform:
            // disable for the rest of the run so we don't retry the native load.
            if (t instanceof UnsatisfiedLinkError || t instanceof NoClassDefFoundError) {
                libraryFailed = true;
            }
            log.warn("Local LLM unavailable, using the offline rules instead: {}", t.toString());
            return Optional.empty();
        }
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

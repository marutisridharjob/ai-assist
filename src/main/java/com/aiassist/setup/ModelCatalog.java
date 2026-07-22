package com.aiassist.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The models ai-assist can use and how to recognise them on disk. The app
 * ships with no models; the user downloads and drops them into the models
 * folder. Exactly one model is <em>required</em> for the app to do anything
 * useful (a Vosk English speech model for live captions); the rest are
 * <em>recommended</em> upgrades.
 */
public final class ModelCatalog {

    private ModelCatalog() {
    }

    public enum Tier { REQUIRED, RECOMMENDED }

    /**
     * A catalogued model: how to describe it, where to download it, and how to
     * tell whether it is already present in a given folder.
     */
    public record ModelSpec(String id, String title, Tier tier, String purpose,
                            String fileName, String downloadUrl, String instructions,
                            Predicate<Path> presentIn) {
    }

    /** Present-in-folder tests, kept next to the catalogue so they stay in sync. */
    private static boolean hasVoskModel(Path dir) {
        // A Vosk model is a folder with an "am" subfolder or a "final.mdl" file,
        // sitting either directly at dir or one level below it.
        if (isVoskDir(dir)) {
            return true;
        }
        return anyChild(dir, ModelCatalog::isVoskDir);
    }

    private static boolean isVoskDir(Path p) {
        return Files.isDirectory(p.resolve("am")) || Files.isRegularFile(p.resolve("final.mdl"));
    }

    private static boolean hasFile(Path dir, String prefix, String suffix) {
        return anyChild(dir, p -> {
            if (!Files.isRegularFile(p)) {
                return false;
            }
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            return (prefix.isEmpty() || n.startsWith(prefix)) && n.endsWith(suffix);
        });
    }

    private static boolean anyChild(Path dir, Predicate<Path> test) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var children = Files.list(dir)) {
            return children.anyMatch(test);
        } catch (IOException e) {
            return false;
        }
    }

    /** The catalogue, most important first. */
    public static final List<ModelSpec> MODELS = List.of(
            new ModelSpec("vosk", "Vosk small English speech model", Tier.REQUIRED,
                    "live captions while the meeting runs",
                    "vosk-model-small-en-us-0.15.zip",
                    "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                    "Download the file and drop the .zip straight into the models folder — the app "
                            + "unpacks it for you. Bigger, more accurate models are at "
                            + "https://alphacephei.com/vosk/models",
                    dir -> hasVoskModel(dir)),
            new ModelSpec("whisper", "Whisper transcription model", Tier.RECOMMENDED,
                    "an accurate, complete transcript when you press Stop",
                    "ggml-base.bin",
                    "https://github.com/NoMercy-Entertainment/nomercy-whisper-models/releases/latest/download/ggml-base.bin",
                    "Download from the GitHub mirror (no Hugging Face account needed) and put the .bin "
                            + "file in the models folder.",
                    dir -> hasFile(dir, "ggml-", ".bin")),
            new ModelSpec("llm", "Instruct LLM model (GGUF)", Tier.RECOMMENDED,
                    "richer AI summaries and rewrites",
                    "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                    "https://github.com/marutisridharjob/ai-assist/blob/main/models/README.md",
                    "Optional. Open the models guide for direct links, then put a single GGUF instruct "
                            + "model in the models folder.",
                    dir -> hasFile(dir, "", ".gguf")));

    /** The outcome of checking the catalogue against the model folders. */
    public record Status(List<ModelSpec> present, List<ModelSpec> missing) {
        public boolean allRequiredPresent() {
            return missing.stream().noneMatch(m -> m.tier() == Tier.REQUIRED);
        }

        public List<ModelSpec> missingRequired() {
            return missing.stream().filter(m -> m.tier() == Tier.REQUIRED).toList();
        }

        public List<ModelSpec> missingRecommended() {
            return missing.stream().filter(m -> m.tier() == Tier.RECOMMENDED).toList();
        }
    }

    /** Classifies every catalogued model as present or missing across the given folders. */
    public static Status scan(List<Path> roots) {
        List<ModelSpec> present = new ArrayList<>();
        List<ModelSpec> missing = new ArrayList<>();
        for (ModelSpec spec : MODELS) {
            boolean found = roots.stream().anyMatch(spec.presentIn());
            (found ? present : missing).add(spec);
        }
        return new Status(present, missing);
    }
}
